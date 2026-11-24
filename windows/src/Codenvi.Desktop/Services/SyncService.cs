using Codenvi.Contracts.Protocol;
using Codenvi.Desktop.Claude;
using Codenvi.Desktop.Core;
using Codenvi.Desktop.Jobs;
using Codenvi.Desktop.Net;
using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging;

namespace Codenvi.Desktop.Services;

/// <summary>Routes WebSocket messages and keeps local state in sync with the server.</summary>
public sealed class SyncService(WsClient ws, ApiClient api, JobScheduler scheduler, ControlHandler control,
    ProjectService projects, ClaudeCli claude, SecretStore secrets, AppState state, ILogger<SyncService> log) : BackgroundService
{
    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        ws.MessageReceived += OnMessage;
        ws.ConnectionChanged += connected =>
        {
            state.SetConnected(connected);
            if (connected) _ = Task.Run(SafeSyncAsync, stoppingToken);
        };
        ws.Revoked += state.SetRevoked;
        api.Revoked += (_, _) => state.SetRevoked();
        api.UpgradeRequired += (_, _) => state.SetUpgradeRequired();
        claude.StatusChanged += state.SetClaude;

        state.SetClaude(await claude.CheckAsync(stoppingToken));
        using var timer = new PeriodicTimer(TimeSpan.FromMinutes(5));
        while (await timer.WaitForNextTickAsync(stoppingToken))
        {
            try { state.SetClaude(await claude.CheckAsync(stoppingToken)); }
            catch (Exception e) when (e is not OperationCanceledException) { log.LogDebug("Claude check failed: {M}", e.Message); }
        }
    }

    private async Task SafeSyncAsync()
    {
        if (!secrets.HasDevice) return;
        try { await projects.SyncFromServerAsync(); }
        catch (Exception e) { log.LogWarning("Sync failed: {Message}", e.Message); }
    }

    private void OnMessage(Envelope env)
    {
        _ = Task.Run(async () =>
        {
            try
            {
                switch (env.Type)
                {
                    case MessageTypes.JobDispatch:
                        await scheduler.EnqueueAsync(env.PayloadAs<JobDispatchPayload>());
                        break;
                    case MessageTypes.JobCancel:
                        await scheduler.CancelAsync(env.PayloadAs<JobCancelPayload>().JobId);
                        break;
                    case MessageTypes.ControlRequest:
                        await control.HandleAsync(env.PayloadAs<ControlRequestPayload>());
                        break;
                    case MessageTypes.SyncChanged:
                        var p = env.PayloadAs<SyncChangedPayload>();
                        if (p.Entity is SyncEntities.Project or SyncEntities.Session or SyncEntities.Device) await SafeSyncAsync();
                        if (p.Entity == SyncEntities.Installs) state.RaiseProjectsListChanged();
                        break;
                    case MessageTypes.Error:
                        var err = env.PayloadAs<ErrorPayload>();
                        log.LogWarning("Server error: {Code} {Message}", err.Code, err.Message);
                        break;
                }
            }
            catch (Exception e)
            {
                log.LogError(e, "Handling {Type} failed", env.Type);
            }
        });
    }
}
