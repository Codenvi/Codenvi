using System.Net;
using System.Net.Http.Json;
using System.Text.Json;
using Codenvi.Contracts;
using Codenvi.Contracts.Api;
using Codenvi.Desktop.Core;
using Microsoft.Extensions.Logging;

namespace Codenvi.Desktop.Net;

public class ApiException(HttpStatusCode status, string message) : Exception(message)
{
    public HttpStatusCode Status { get; } = status;
}

public sealed class RevokedException() : ApiException(HttpStatusCode.Unauthorized, "This app was disconnected from the server.");

public sealed class UpgradeRequiredException() : ApiException(HttpStatusCode.UpgradeRequired, "Update Codenvi - this version is no longer supported by the server.");

/// <summary>REST client for /api/v1 with automatic access-token refresh (Project.md §4.3, §7.3).</summary>
public sealed class ApiClient : IDisposable
{
    private readonly SecretStore _secrets;
    private readonly ILogger<ApiClient> _log;
    private readonly SemaphoreSlim _refreshLock = new(1, 1);
    private HttpClient? _http;
    private string? _httpFor;
    private string? _accessToken;
    private DateTimeOffset _accessExpires = DateTimeOffset.MinValue;

    public event EventHandler? Revoked;
    public event EventHandler? UpgradeRequired;

    public ApiClient(SecretStore secrets, ILogger<ApiClient> log)
    {
        _secrets = secrets;
        _log = log;
    }

    private HttpClient Http
    {
        get
        {
            var s = _secrets.Current;
            var key = $"{s.ServerHost}|{s.Pin}";
            if (_http is null || _httpFor != key)
            {
                _http?.Dispose();
                _http = new HttpClient(HttpFactory.CreateHandler(s.Pin))
                {
                    BaseAddress = new Uri($"https://{s.ServerHost}/"),
                    Timeout = TimeSpan.FromSeconds(60),
                };
                _httpFor = key;
            }
            return _http;
        }
    }

    // ---------------- Pairing (no auth) ----------------
    public static async Task<TokenResponse> PairAsync(string host, string? pin, PairRequest request, CancellationToken ct = default)
    {
        using var http = new HttpClient(HttpFactory.CreateHandler(pin)) { BaseAddress = new Uri($"https://{host}/") };
        using var req = new HttpRequestMessage(HttpMethod.Post, "api/v1/pair") { Content = JsonContent.Create(request, options: CodenviJson.Options) };
        HttpFactory.AddCodenviHeaders(req, request.InstallId);
        using var res = await http.SendAsync(req, ct);
        if (!res.IsSuccessStatusCode) throw new ApiException(res.StatusCode, await ProblemTitle(res) ?? "Pairing failed");
        return (await res.Content.ReadFromJsonAsync<TokenResponse>(CodenviJson.Options, ct))!;
    }

    public void AcceptTokens(TokenResponse t)
    {
        _accessToken = t.AccessToken;
        _accessExpires = DateTimeOffset.FromUnixTimeMilliseconds(t.AccessExpiresAtMs);
        _secrets.Update(s => s.RefreshToken = t.RefreshToken);
    }

    /// <summary>Valid access token (for the WebSocket query string).</summary>
    public async Task<string> GetAccessTokenAsync(CancellationToken ct = default, bool forceRefresh = false)
    {
        if (!forceRefresh && _accessToken is not null && _accessExpires > DateTimeOffset.UtcNow.AddSeconds(60)) return _accessToken;
        await _refreshLock.WaitAsync(ct);
        try
        {
            if (!forceRefresh && _accessToken is not null && _accessExpires > DateTimeOffset.UtcNow.AddSeconds(60)) return _accessToken;
            var s = _secrets.Current;
            if (s.RefreshToken is null) throw new RevokedException();
            using var req = new HttpRequestMessage(HttpMethod.Post, "api/v1/token/refresh")
            {
                Content = JsonContent.Create(new RefreshRequest(s.InstallId, s.RefreshToken), options: CodenviJson.Options),
            };
            HttpFactory.AddCodenviHeaders(req, s.InstallId);
            using var res = await Http.SendAsync(req, ct);
            if (res.StatusCode == HttpStatusCode.Unauthorized)
            {
                _log.LogWarning("Refresh rejected; this install was revoked");
                Revoked?.Invoke(this, EventArgs.Empty);
                throw new RevokedException();
            }
            if (res.StatusCode == HttpStatusCode.UpgradeRequired) { UpgradeRequired?.Invoke(this, EventArgs.Empty); throw new UpgradeRequiredException(); }
            if (!res.IsSuccessStatusCode) throw new ApiException(res.StatusCode, await ProblemTitle(res) ?? "Token refresh failed");
            AcceptTokens((await res.Content.ReadFromJsonAsync<TokenResponse>(CodenviJson.Options, ct))!);
            return _accessToken!;
        }
        finally { _refreshLock.Release(); }
    }

    private async Task<HttpResponseMessage> SendAsync(HttpMethod method, string path, object? body, CancellationToken ct)
    {
        for (var attempt = 0; ; attempt++)
        {
            var token = await GetAccessTokenAsync(ct, forceRefresh: attempt > 0);
            var req = new HttpRequestMessage(method, path.TrimStart('/'));
            if (body is not null) req.Content = JsonContent.Create(body, body.GetType(), options: CodenviJson.Options);
            req.Headers.Authorization = new System.Net.Http.Headers.AuthenticationHeaderValue("Bearer", token);
            HttpFactory.AddCodenviHeaders(req, _secrets.Current.InstallId);
            var res = await Http.SendAsync(req, ct);
            if (res.StatusCode == HttpStatusCode.Unauthorized && attempt == 0) { res.Dispose(); continue; }
            if (res.StatusCode == HttpStatusCode.UpgradeRequired) { UpgradeRequired?.Invoke(this, EventArgs.Empty); throw new UpgradeRequiredException(); }
            if (res.StatusCode == HttpStatusCode.Unauthorized) { Revoked?.Invoke(this, EventArgs.Empty); throw new RevokedException(); }
            if (!res.IsSuccessStatusCode)
            {
                var title = await ProblemTitle(res);
                res.Dispose();
                throw new ApiException(res.StatusCode, title ?? $"Server returned {(int)res.StatusCode}");
            }
            return res;
        }
    }

    private async Task<T> GetJson<T>(string path, CancellationToken ct = default)
    {
        using var res = await SendAsync(HttpMethod.Get, path, null, ct);
        return (await res.Content.ReadFromJsonAsync<T>(CodenviJson.Options, ct))!;
    }

    private async Task<T> SendJson<T>(HttpMethod m, string path, object? body, CancellationToken ct = default)
    {
        using var res = await SendAsync(m, path, body, ct);
        return (await res.Content.ReadFromJsonAsync<T>(CodenviJson.Options, ct))!;
    }

    private async Task Send(HttpMethod m, string path, object? body = null, CancellationToken ct = default)
    {
        using var _ = await SendAsync(m, path, body, ct);
    }

    private static async Task<string?> ProblemTitle(HttpResponseMessage res)
    {
        try
        {
            using var doc = JsonDocument.Parse(await res.Content.ReadAsStringAsync());
            return doc.RootElement.TryGetProperty("title", out var t) ? t.GetString() : null;
        }
        catch { return null; }
    }

    // ---------------- Endpoints ----------------
    public Task<PairingCodeResponse> CreatePairingCodeAsync(string? kind) => SendJson<PairingCodeResponse>(HttpMethod.Post, "api/v1/pairing-codes", new CreatePairingCodeRequest(kind));
    public Task<List<InstallDto>> InstallsAsync() => GetJson<List<InstallDto>>("api/v1/installs");
    public Task RenameInstallAsync(Guid id, string name) => Send(HttpMethod.Patch, $"api/v1/installs/{id}", new RenameRequest(name));
    public Task RevokeInstallAsync(Guid id, bool self) => Send(HttpMethod.Delete, $"api/v1/installs/{id}" + (self ? "?confirmSelf=true" : ""));

    public Task<DeviceDto> RegisterDeviceAsync(Guid id, string name, byte[] kcv) => SendJson<DeviceDto>(HttpMethod.Post, "api/v1/devices", new RegisterDeviceRequest(id, name, kcv));
    public Task<List<DeviceDto>> DevicesAsync() => GetJson<List<DeviceDto>>("api/v1/devices");
    public Task RenameDeviceAsync(Guid id, string name) => Send(HttpMethod.Patch, $"api/v1/devices/{id}", new RenameRequest(name));
    public Task RotateKeyAsync(Guid id, int version, byte[] kcv) => Send(HttpMethod.Post, $"api/v1/devices/{id}/key", new RotateKeyRequest(version, kcv));
    public Task DeleteDeviceAsync(Guid id) => Send(HttpMethod.Delete, $"api/v1/devices/{id}");

    public Task<List<ProjectDto>> ProjectsAsync(Guid deviceId) => GetJson<List<ProjectDto>>($"api/v1/devices/{deviceId}/projects");
    public Task PutProjectAsync(Guid id, PutProjectRequest req) => Send(HttpMethod.Put, $"api/v1/projects/{id}", req);
    public Task DeleteProjectAsync(Guid id) => Send(HttpMethod.Delete, $"api/v1/projects/{id}");

    public Task<List<SessionDto>> SessionsAsync(Guid projectId) => GetJson<List<SessionDto>>($"api/v1/projects/{projectId}/sessions");
    public Task PutSessionAsync(Guid id, PutSessionRequest req) => Send(HttpMethod.Put, $"api/v1/sessions/{id}", req);
    public Task PatchSessionAsync(Guid id, PatchSessionRequest req) => Send(HttpMethod.Patch, $"api/v1/sessions/{id}", req);
    public Task DeleteSessionAsync(Guid id) => Send(HttpMethod.Delete, $"api/v1/sessions/{id}");
    public Task ClearMessagesAsync(Guid sessionId) => Send(HttpMethod.Delete, $"api/v1/sessions/{sessionId}/messages");

    public Task PutCommitAsync(Guid projectId, Guid commitId, PutCommitRequest req) => Send(HttpMethod.Put, $"api/v1/projects/{projectId}/commits/{commitId}", req);

    public void Dispose() => _http?.Dispose();
}
