using System.Diagnostics;
using System.Text;

namespace Codenvi.Desktop.Claude;

public sealed record ProcessResult(int ExitCode, string StdOut, string StdErr, bool Cancelled, bool TimedOut);

/// <summary>
/// Runs a process without a shell (ProcessStartInfo.ArgumentList, docs/design.md §8.6), optionally feeding
/// stdin and streaming stdout lines. Cancellation kills the whole process tree.
/// </summary>
public static class ProcessRunner
{
    public static async Task<ProcessResult> RunAsync(
        string fileName,
        IEnumerable<string> args,
        string? workingDirectory = null,
        string? stdin = null,
        Action<string>? onStdOutLine = null,
        TimeSpan? timeout = null,
        CancellationToken ct = default,
        int maxCaptureChars = 2_000_000,
        Action<string>? onStdErrLine = null)
    {
        var psi = new ProcessStartInfo(fileName)
        {
            UseShellExecute = false,
            CreateNoWindow = true,
            RedirectStandardInput = true,
            RedirectStandardOutput = true,
            RedirectStandardError = true,
            StandardOutputEncoding = Encoding.UTF8,
            StandardErrorEncoding = Encoding.UTF8,
            WorkingDirectory = workingDirectory ?? Environment.CurrentDirectory,
        };
        foreach (var a in args) psi.ArgumentList.Add(a);
        // The environment is inherited unchanged (Claude Code uses the user's own sign-in, docs/design.md §0.5).

        using var process = new Process { StartInfo = psi, EnableRaisingEvents = true };
        var stdout = new StringBuilder();
        var stderr = new StringBuilder();
        var stdoutDone = new TaskCompletionSource();
        var stderrDone = new TaskCompletionSource();

        process.OutputDataReceived += (_, e) =>
        {
            if (e.Data is null) { stdoutDone.TrySetResult(); return; }
            if (stdout.Length < maxCaptureChars) stdout.AppendLine(e.Data);
            try { onStdOutLine?.Invoke(e.Data); } catch { /* never break the reader */ }
        };
        process.ErrorDataReceived += (_, e) =>
        {
            if (e.Data is null) { stderrDone.TrySetResult(); return; }
            if (stderr.Length < maxCaptureChars) stderr.AppendLine(e.Data);
            try { onStdErrLine?.Invoke(e.Data); } catch { /* never break the reader */ }
        };

        process.Start();
        process.BeginOutputReadLine();
        process.BeginErrorReadLine();

        try
        {
            if (stdin is not null)
            {
                var input = new StreamWriter(process.StandardInput.BaseStream, new UTF8Encoding(false));
                await input.WriteAsync(stdin);
                await input.FlushAsync(CancellationToken.None);
            }
            process.StandardInput.Close();
        }
        catch (IOException) { /* process exited early */ }

        using var timeoutCts = new CancellationTokenSource(timeout ?? Timeout.InfiniteTimeSpan);
        using var linked = CancellationTokenSource.CreateLinkedTokenSource(ct, timeoutCts.Token);
        var cancelled = false;
        try
        {
            await process.WaitForExitAsync(linked.Token);
        }
        catch (OperationCanceledException)
        {
            cancelled = true;
            try { process.Kill(entireProcessTree: true); } catch { }
            try { await process.WaitForExitAsync(CancellationToken.None).WaitAsync(TimeSpan.FromSeconds(10)); } catch { }
        }
        await Task.WhenAny(Task.WhenAll(stdoutDone.Task, stderrDone.Task), Task.Delay(5000));

        var timedOut = cancelled && timeoutCts.IsCancellationRequested && !ct.IsCancellationRequested;
        return new ProcessResult(process.HasExited ? process.ExitCode : -1, stdout.ToString(), stderr.ToString(),
            cancelled && !timedOut, timedOut);
    }

    /// <summary>Finds an executable on PATH (with PATHEXT on Windows).</summary>
    public static string? FindOnPath(string name, params string[] extraDirectories)
    {
        var exts = OperatingSystem.IsWindows()
            ? (Environment.GetEnvironmentVariable("PATHEXT") ?? ".EXE;.CMD;.BAT").Split(';', StringSplitOptions.RemoveEmptyEntries)
            : [""];
        // Prefer real executables over .cmd shims (no cmd.exe argument parsing involved).
        exts = exts.OrderBy(e => e.Equals(".EXE", StringComparison.OrdinalIgnoreCase) ? 0 : 1).ToArray();
        var dirs = (Environment.GetEnvironmentVariable("PATH") ?? "").Split(Path.PathSeparator, StringSplitOptions.RemoveEmptyEntries)
            .Concat(extraDirectories);
        foreach (var ext in exts)
            foreach (var dir in dirs)
            {
                try
                {
                    var candidate = Path.Combine(dir.Trim('"'), name + ext.ToLowerInvariant());
                    if (File.Exists(candidate)) return candidate;
                }
                catch (ArgumentException) { }
            }
        return null;
    }
}
