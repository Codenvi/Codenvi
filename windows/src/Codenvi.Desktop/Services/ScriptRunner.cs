using System.Collections.Concurrent;
using Codenvi.Desktop.Claude;
using Codenvi.Desktop.Core;
using Microsoft.Extensions.Logging;

namespace Codenvi.Desktop.Services;

/// <summary>
/// Runs a project's PC-local shell script (PowerShell, one command per line) in the background when the phone asks.
/// The script text only ever comes from the local database, never from the phone. Output streams to the Activity tab.
/// </summary>
public sealed class ScriptRunner(AppState state, ILogger<ScriptRunner> log)
{
    private readonly ConcurrentDictionary<Guid, CancellationTokenSource> _running = new();

    public bool IsRunning(Guid projectId) => _running.ContainsKey(projectId);

    /// <summary>Starts the script and returns immediately. Returns an error text, or null when it started.</summary>
    public string? Start(LocalProject project)
    {
        if (string.IsNullOrWhiteSpace(project.ShellScript)) return "No script is configured for this project on the PC.";
        if (!Directory.Exists(project.FolderPath)) return "The project folder is missing on the PC.";
        var powershell = ProcessRunner.FindOnPath("powershell");
        if (powershell is null) return "PowerShell was not found on the PC.";

        var cts = new CancellationTokenSource();
        if (!_running.TryAdd(project.Id, cts)) { cts.Dispose(); return "The script is already running."; }

        string file;
        try
        {
            AppPaths.Ensure();
            file = Path.Combine(AppPaths.Scripts, $"{project.Id:N}-{Guid.NewGuid():N}.ps1");
            // UTF-8 with BOM so Windows PowerShell 5 reads non-ASCII characters correctly.
            File.WriteAllText(file, project.ShellScript, new System.Text.UTF8Encoding(true));
        }
        catch (Exception e)
        {
            Finish(project.Id, cts);
            log.LogWarning(e, "Could not prepare the script for {Project}", project.Id);
            return "Could not prepare the script: " + e.Message;
        }

        MarkRunning(project.Id, true);
        state.AddActivity(project.Id, "▶ Script started from the phone");
        _ = Task.Run(async () =>
        {
            try
            {
                var result = await ProcessRunner.RunAsync(powershell,
                    ["-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File", file],
                    project.FolderPath,
                    onStdOutLine: line => state.AddActivity(project.Id, line),
                    onStdErrLine: line => state.AddActivity(project.Id, line),
                    ct: cts.Token, maxCaptureChars: 4096);
                state.AddActivity(project.Id, result.Cancelled
                    ? "■ Script stopped"
                    : $"■ Script finished (exit code {result.ExitCode})");
            }
            catch (Exception e)
            {
                log.LogWarning(e, "Script for {Project} failed", project.Id);
                state.AddActivity(project.Id, "■ Script failed: " + e.Message);
            }
            finally
            {
                try { File.Delete(file); } catch { }
                Finish(project.Id, cts);
            }
        });
        return null;
    }

    public void Stop(Guid projectId)
    {
        if (_running.TryGetValue(projectId, out var cts)) try { cts.Cancel(); } catch (ObjectDisposedException) { }
    }

    /// <summary>Called on quit so no dev server is left running without Codenvi.</summary>
    public void StopAll()
    {
        foreach (var id in _running.Keys.ToList()) Stop(id);
        var deadline = DateTime.UtcNow.AddSeconds(5);
        while (!_running.IsEmpty && DateTime.UtcNow < deadline) Thread.Sleep(50);
    }

    private void Finish(Guid projectId, CancellationTokenSource cts)
    {
        _running.TryRemove(projectId, out _);
        cts.Dispose();
        MarkRunning(projectId, false);
    }

    private void MarkRunning(Guid projectId, bool running) => state.UpdateRuntime(projectId, r => r.ScriptRunning = running);
}
