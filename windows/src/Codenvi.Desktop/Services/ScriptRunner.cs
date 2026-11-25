using System.Collections.Concurrent;
using Codenvi.Desktop.Claude;
using Codenvi.Desktop.Core;
using Microsoft.Extensions.Logging;

namespace Codenvi.Desktop.Services;

/// <summary>
/// Runs a project's PC-local shell script (cmd.exe commands, one per line) in the background when the phone asks.
/// The script text only ever comes from the local database, never from the phone. Output streams to the Activity tab.
/// </summary>
public sealed class ScriptRunner(AppState state, ILogger<ScriptRunner> log)
{
    private readonly ConcurrentDictionary<Guid, CancellationTokenSource> _running = new();

    /// <summary>Raised when a script starts or ends, so the phone can be told (it shows Run or Stop).</summary>
    public event Action<Guid>? RunningChanged;

    public bool IsRunning(Guid projectId) => _running.ContainsKey(projectId);

    /// <summary>Starts the script and returns immediately. Returns an error text, or null when it started.</summary>
    public string? Start(LocalProject project)
    {
        if (string.IsNullOrWhiteSpace(project.ShellScript)) return "No script is configured for this project on the PC.";
        if (!Directory.Exists(project.FolderPath)) return "The project folder is missing on the PC.";
        var cmd = Environment.GetEnvironmentVariable("ComSpec") is { Length: > 0 } comSpec && File.Exists(comSpec)
            ? comSpec : ProcessRunner.FindOnPath("cmd");
        if (cmd is null) return "cmd.exe was not found on the PC.";
        var commandLine = BuildCommandLine(project.ShellScript);
        if (commandLine is null) return "The script is too long. Shorten it or move the commands into a script file in the project.";

        var cts = new CancellationTokenSource();
        if (!_running.TryAdd(project.Id, cts)) { cts.Dispose(); return "The script is already running."; }

        MarkRunning(project.Id, true);
        state.AddTerminal(project.Id, "▶ Script started from the phone");
        _ = Task.Run(async () =>
        {
            try
            {
                var result = await ProcessRunner.RunAsync(cmd, [], project.FolderPath,
                    rawArguments: $"/d /s /c \"{commandLine}\"",
                    onStdOutLine: line => state.AddTerminal(project.Id, line.TrimEnd()),
                    onStdErrLine: line => state.AddTerminal(project.Id, line.TrimEnd()),
                    ct: cts.Token, maxCaptureChars: 4096);
                state.AddTerminal(project.Id, result.Cancelled
                    ? "■ Script stopped"
                    : $"■ Script finished (exit code {result.ExitCode})");
            }
            catch (Exception e)
            {
                log.LogWarning(e, "Script for {Project} failed", project.Id);
                state.AddTerminal(project.Id, "■ Script failed: " + e.Message);
            }
            finally
            {
                Finish(project.Id, cts);
            }
        });
        return null;
    }

    /// <summary>
    /// Joins the lines into ONE cmd command line ("a &amp; b &amp; c"). Unlike a batch file, a line that starts another
    /// batch file (such as <c>code</c>, <c>npm</c> or <c>npx</c>, which are .cmd shims) returns and the next line still runs,
    /// and <c>cd</c> and environment changes carry over to the following lines. Returns null when it is too long for cmd.exe.
    /// </summary>
    public static string? BuildCommandLine(string script)
    {
        var lines = script.Split(['\r', '\n'], StringSplitOptions.RemoveEmptyEntries)
            .Select(l => l.Trim()).Where(l => l.Length > 0);
        // chcp 65001 makes cmd write UTF-8, which is what the output reader expects.
        var joined = "chcp 65001 >nul & " + string.Join(" & ", lines);
        return joined.Length <= 7000 ? joined : null;
    }

    /// <summary>Stops the running script. Returns false when none was running.</summary>
    public bool Stop(Guid projectId)
    {
        if (!_running.TryGetValue(projectId, out var cts)) return false;
        try { cts.Cancel(); } catch (ObjectDisposedException) { }
        return true;
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

    private void MarkRunning(Guid projectId, bool running)
    {
        state.UpdateRuntime(projectId, r => r.ScriptRunning = running);
        try { RunningChanged?.Invoke(projectId); } catch (Exception e) { log.LogDebug(e, "RunningChanged handler failed"); }
    }
}
