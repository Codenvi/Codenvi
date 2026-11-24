using System.Net.WebSockets;
using Codenvi.Contracts;
using Codenvi.Contracts.Protocol;
using Codenvi.Desktop.Core;
using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging;

namespace Codenvi.Desktop.Net;

/// <summary>
/// Outbound WebSocket to <c>wss://host/ws</c> (docs/design.md §7.4). Reconnects with exponential backoff
/// (1 s → 30 s), pings every 25 s, refreshes the token when the server closes with 4001.
/// </summary>
public sealed class WsClient(SecretStore secrets, ApiClient api, ILogger<WsClient> log) : BackgroundService
{
    private ClientWebSocket? _socket;
    private readonly System.Collections.Concurrent.ConcurrentQueue<byte[]> _outbox = new();
    private readonly SemaphoreSlim _sendLock = new(1, 1);
    private CancellationTokenSource? _reconnectNow;

    public event Action<Envelope>? MessageReceived;
    public event Action<bool>? ConnectionChanged;
    public event Action? Revoked;
    public bool IsConnected => _socket?.State == WebSocketState.Open;

    /// <summary>Forces a reconnect (after pairing, device registration or settings change).</summary>
    public void Reconnect()
    {
        try { _socket?.Abort(); } catch { }
        _reconnectNow?.Cancel();
    }

    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        var delay = TimeSpan.FromSeconds(1);
        while (!stoppingToken.IsCancellationRequested)
        {
            _reconnectNow = CancellationTokenSource.CreateLinkedTokenSource(stoppingToken);
            if (!secrets.IsPaired)
            {
                try { await Task.Delay(TimeSpan.FromSeconds(2), _reconnectNow.Token); } catch (OperationCanceledException) { }
                continue;
            }

            var closeStatus = (WebSocketCloseStatus?)null;
            try
            {
                var token = await api.GetAccessTokenAsync(stoppingToken);
                using var socket = new ClientWebSocket();
                socket.Options.KeepAliveInterval = TimeSpan.FromSeconds(25);
                socket.Options.SetRequestHeader(ClientInfo.HeaderInstallId, secrets.Current.InstallId.ToString("D"));
                socket.Options.SetRequestHeader(ClientInfo.HeaderClient, HttpFactory.ClientHeader());
                if (!string.IsNullOrWhiteSpace(secrets.Current.Pin))
                {
                    var pin = secrets.Current.Pin!;
                    socket.Options.RemoteCertificateValidationCallback = (_, cert, _, _) => HttpFactory.MatchesPin(cert, pin);
                }
                var uri = new Uri($"wss://{secrets.Current.ServerHost}/ws?token={Uri.EscapeDataString(token)}");
                await socket.ConnectAsync(uri, _reconnectNow.Token);
                _socket = socket;
                delay = TimeSpan.FromSeconds(1);
                log.LogInformation("Connected to {Host}", secrets.Current.ServerHost);
                ConnectionChanged?.Invoke(true);

                await SendAsync(MessageTypes.Hello, new HelloPayload("desktop", secrets.Current.DeviceId));
                await FlushOutboxAsync();
                _ = PingLoopAsync(_reconnectNow.Token);
                closeStatus = await ReceiveLoopAsync(socket, _reconnectNow.Token);
            }
            catch (RevokedException)
            {
                Revoked?.Invoke();
                try { await Task.Delay(TimeSpan.FromSeconds(30), stoppingToken); } catch (OperationCanceledException) { }
                continue;
            }
            catch (OperationCanceledException) when (!stoppingToken.IsCancellationRequested) { delay = TimeSpan.FromMilliseconds(200); }
            catch (OperationCanceledException) { break; }
            catch (Exception e)
            {
                log.LogWarning("WebSocket error: {Message}", e.Message);
            }
            finally
            {
                _socket = null;
                ConnectionChanged?.Invoke(false);
            }

            if ((int?)closeStatus == 4003) { Revoked?.Invoke(); continue; }
            if ((int?)closeStatus == 4001) { delay = TimeSpan.FromMilliseconds(100); try { await api.GetAccessTokenAsync(stoppingToken, forceRefresh: true); } catch { } }

            try { await Task.Delay(delay, stoppingToken); } catch (OperationCanceledException) { break; }
            delay = TimeSpan.FromSeconds(Math.Min(30, Math.Max(1, delay.TotalSeconds * 2)));
        }
    }

    private Task PingLoopAsync(CancellationToken ct) => Task.Run(async () =>
    {
        while (!ct.IsCancellationRequested && IsConnected)
        {
            try
            {
                await Task.Delay(TimeSpan.FromSeconds(25), ct);
                await SendAsync(MessageTypes.Ping, new { });
            }
            catch { return; }
        }
    }, ct);

    private async Task<WebSocketCloseStatus?> ReceiveLoopAsync(ClientWebSocket socket, CancellationToken ct)
    {
        var buffer = new byte[64 * 1024];
        while (socket.State == WebSocketState.Open && !ct.IsCancellationRequested)
        {
            using var ms = new MemoryStream();
            WebSocketReceiveResult r;
            do
            {
                r = await socket.ReceiveAsync(buffer, ct);
                if (r.MessageType == WebSocketMessageType.Close) return socket.CloseStatus;
                ms.Write(buffer, 0, r.Count);
            } while (!r.EndOfMessage);

            Envelope? env = null;
            try { env = Envelope.FromUtf8(ms.ToArray()); }
            catch (System.Text.Json.JsonException) { }
            if (env is null) continue;
            try { MessageReceived?.Invoke(env); }
            catch (Exception e) { log.LogError(e, "Handler for {Type} failed", env.Type); }
        }
        return socket.CloseStatus;
    }

    /// <summary>
    /// For job status/results: kept in memory and re-sent after reconnect if the socket is down,
    /// so a finished job is never left "running" on the server.
    /// </summary>
    public async Task SendReliableAsync<T>(string type, T payload)
    {
        var bytes = Envelope.Create(type, payload).ToUtf8();
        if (!await TrySendBytesAsync(bytes)) _outbox.Enqueue(bytes);
    }

    private async Task FlushOutboxAsync()
    {
        var count = _outbox.Count;
        for (var i = 0; i < count && _outbox.TryDequeue(out var bytes); i++)
        {
            if (!await TrySendBytesAsync(bytes)) { _outbox.Enqueue(bytes); return; }
        }
    }

    private async Task<bool> TrySendBytesAsync(byte[] bytes)
    {
        var socket = _socket;
        if (socket is null || socket.State != WebSocketState.Open) return false;
        await _sendLock.WaitAsync();
        try { await socket.SendAsync(bytes, WebSocketMessageType.Text, true, CancellationToken.None); return true; }
        catch { return false; }
        finally { _sendLock.Release(); }
    }

    public async Task SendAsync<T>(string type, T payload)
    {
        var socket = _socket;
        if (socket is null || socket.State != WebSocketState.Open) return;
        var bytes = Envelope.Create(type, payload).ToUtf8();
        await _sendLock.WaitAsync();
        try { await socket.SendAsync(bytes, WebSocketMessageType.Text, true, CancellationToken.None); }
        catch (Exception e) { log.LogDebug("Send {Type} failed: {Message}", type, e.Message); }
        finally { _sendLock.Release(); }
    }
}
