using System.Security.Cryptography;
using Codenvi.Contracts;
using Codenvi.Contracts.Crypto;
using Codenvi.Contracts.Protocol;
using Codenvi.Desktop.Core;
using Codenvi.Desktop.Net;
using Codenvi.Desktop.Services;
using Microsoft.Extensions.Logging;

namespace Codenvi.Desktop.Jobs;

/// <summary>Executes encrypted control commands from the phone (Project.md §7.5).</summary>
public sealed class ControlHandler(LocalDb db, CryptoService crypto, WsClient ws, ProjectService projects, AppState state,
    ILogger<ControlHandler> log)
{
    public async Task HandleAsync(ControlRequestPayload req)
    {
        ControlPlain cmd;
        try
        {
            cmd = crypto.Decrypt<ControlPlain>(ObjectTypes.Control, req.RequestId, Directions.PhoneToDesktop, req.ClientCreatedAtMs, req.Ciphertext);
        }
        catch (Exception e) when (e is CryptographicException or System.Text.Json.JsonException or InvalidOperationException)
        {
            log.LogWarning("Rejected control request {RequestId}: {Message}", req.RequestId, e.Message);
            return; // unauthenticated: never answer with plaintext
        }

        ControlResultPlain result;
        if (!db.MarkSeen(req.RequestId, "control"))
            result = new ControlResultPlain(false, "This request was already handled.");
        else if (DateTimeOffset.UtcNow - DateTimeOffset.FromUnixTimeMilliseconds(req.ClientCreatedAtMs) > TimeSpan.FromHours(Math.Max(1, state.Settings.StaleJobHours)))
            result = new ControlResultPlain(false, "This request is too old.");
        else if (cmd.ProjectId != req.ProjectId)
            result = new ControlResultPlain(false, "Request does not match the project.");
        else
        {
            try { result = await ExecuteAsync(cmd); }
            catch (Exception e) when (e is ProjectException or Git.GitException or IOException or UnauthorizedAccessException)
            {
                result = new ControlResultPlain(false, e.Message);
            }
            catch (Exception e)
            {
                log.LogError(e, "Control {Command} failed", cmd.Command);
                result = new ControlResultPlain(false, "The PC could not complete the command.");
            }
        }

        var at = Time.NowMs();
        var ct = crypto.Encrypt(ObjectTypes.ControlResponse, req.RequestId, Directions.DesktopToPhone, at, result);
        await ws.SendReliableAsync(MessageTypes.ControlResponse,
            new ControlResponsePayload(req.RequestId, crypto.DeviceId, req.ProjectId, ct, at));
    }

    private async Task<ControlResultPlain> ExecuteAsync(ControlPlain cmd)
    {
        var project = db.Project(cmd.ProjectId) ?? throw new ProjectException("This project is not registered on the PC.");
        switch (cmd.Command)
        {
            case ControlCommands.SessionCreate:
            {
                if (cmd.SessionId is not { } sid) return new ControlResultPlain(false, "Missing session id.");
                var existing = db.Session(sid);
                if (existing is null)
                {
                    var s = ProjectService.NewLocalSession(project.Id, sid, string.IsNullOrWhiteSpace(cmd.Name) ? "Session" : cmd.Name!);
                    db.UpsertSession(s);
                }
                state.RaiseProjectsListChanged();
                return new ControlResultPlain(true);
            }
            case ControlCommands.SessionClearMemory:
            {
                if (cmd.SessionId is not { } sid) return new ControlResultPlain(false, "Missing session id.");
                var s = db.Session(sid) ?? await projects.EnsureSessionAsync(project, sid);
                await projects.ClearMemoryAsync(s);
                return new ControlResultPlain(true);
            }
            case ControlCommands.MemoryGet:
                return new ControlResultPlain(true, Text: projects.ReadMemory(project));
            case ControlCommands.MemorySet:
                projects.WriteMemory(project, cmd.Text ?? "");
                return new ControlResultPlain(true);
            case ControlCommands.Rollback:
            {
                if (string.IsNullOrWhiteSpace(cmd.CommitHash)) return new ControlResultPlain(false, "Missing commit.");
                var c = await projects.RollbackAsync(project.Id, cmd.CommitHash!);
                return new ControlResultPlain(true, Text: c.GitHash);
            }
            case ControlCommands.Status:
                return new ControlResultPlain(true, Status: await projects.StatusAsync(project.Id));
            default:
                return new ControlResultPlain(false, $"Unknown command '{cmd.Command}'.");
        }
    }
}
