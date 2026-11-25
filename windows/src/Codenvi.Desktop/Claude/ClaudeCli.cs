using System.Text.RegularExpressions;
using Microsoft.Extensions.Logging;

namespace Codenvi.Desktop.Claude;

public enum ClaudeState { Unknown, NotFound, TooOld, NotSignedIn, Ready }

public sealed record ClaudeStatus(ClaudeState State, string? Path, string? Version)
{
    public string Banner => State switch
    {
        ClaudeState.NotFound => "Claude Code CLI not found - install it and sign in, then restart Codenvi.",
        ClaudeState.TooOld => $"Claude Code {Version} is too old. Codenvi needs {ClaudeCli.MinimumVersion} or newer - update it, then press Retry.",
        ClaudeState.NotSignedIn => "Claude Code is not signed in on this PC. Open a terminal, sign in to Claude Code, then press Retry.",
        _ => "",
    };
}

/// <summary>
/// Checks the user-managed Claude Code CLI (docs/design.md §4.4). Codenvi never opens a sign-in flow,
/// reads credential files or stores any Claude token; it only looks at `claude auth status`'s exit code.
/// </summary>
public sealed partial class ClaudeCli(ILogger<ClaudeCli> log)
{
    /// <summary>`--permission-prompts none` needs this version (V6 in docs/design.md §14).</summary>
    public static readonly Version MinimumVersion = new(2, 1, 259);

    public ClaudeStatus Status { get; private set; } = new(ClaudeState.Unknown, null, null);
    public event Action<ClaudeStatus>? StatusChanged;

    public static string? Locate() => ProcessRunner.FindOnPath("claude",
        Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), ".local", "bin"),
        Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "npm"));

    public async Task<ClaudeStatus> CheckAsync(CancellationToken ct = default)
    {
        var status = await CheckCoreAsync(ct);
        var changed = status != Status;
        Status = status;
        if (changed) StatusChanged?.Invoke(status);
        return status;
    }

    private async Task<ClaudeStatus> CheckCoreAsync(CancellationToken ct)
    {
        var path = Locate();
        if (path is null) return new ClaudeStatus(ClaudeState.NotFound, null, null);
        try
        {
            var v = await ProcessRunner.RunAsync(path, ["--version"], timeout: TimeSpan.FromSeconds(30), ct: ct);
            var version = ParseVersion(v.StdOut + " " + v.StdErr);
            if (version is not null && version < MinimumVersion)
                return new ClaudeStatus(ClaudeState.TooOld, path, version.ToString());

            var auth = await ProcessRunner.RunAsync(path, ["auth", "status"], timeout: TimeSpan.FromSeconds(30), ct: ct);
            return new ClaudeStatus(auth.ExitCode == 0 ? ClaudeState.Ready : ClaudeState.NotSignedIn, path, version?.ToString());
        }
        catch (Exception e) when (e is not OperationCanceledException)
        {
            log.LogWarning(e, "Claude Code check failed");
            return new ClaudeStatus(ClaudeState.NotFound, path, null);
        }
    }

    public static Version? ParseVersion(string text)
    {
        var m = VersionRegex().Match(text);
        return m.Success ? new Version(int.Parse(m.Groups[1].Value), int.Parse(m.Groups[2].Value), int.Parse(m.Groups[3].Value)) : null;
    }

    /// <summary>
    /// Session names go on the command line. When claude is a .cmd shim, cmd.exe re-parses arguments,
    /// so only plain characters are allowed.
    /// </summary>
    public static string SafeSessionName(string name)
    {
        var cleaned = new string(name.Where(c => char.IsLetterOrDigit(c) || c is ' ' or '-' or '_' or '.').ToArray()).Trim();
        if (cleaned.Length > 60) cleaned = cleaned[..60];
        return cleaned.Length == 0 ? "Codenvi session" : "Codenvi: " + cleaned;
    }

    /// <summary>Builds the `claude -p` arguments from docs/design.md §8.6. The prompt itself goes on stdin.</summary>
    public static List<string> BuildArguments(bool firstMessage, Guid claudeSessionId, string sessionName, string? model,
        string? effort, string permissionMode, string allowedTools, int maxTurns)
    {
        var args = new List<string> { "-p" };
        if (firstMessage)
        {
            args.AddRange(["--session-id", claudeSessionId.ToString("D"), "-n", SafeSessionName(sessionName)]);
        }
        else
        {
            args.AddRange(["--resume", claudeSessionId.ToString("D")]);
        }
        args.AddRange(["--output-format", "stream-json", "--verbose"]);
        if (!string.IsNullOrWhiteSpace(model) && model != "default") args.AddRange(["--model", model]);
        if (!string.IsNullOrWhiteSpace(effort) && effort != "default") args.AddRange(["--effort", effort]);
        args.AddRange(["--permission-mode", string.IsNullOrWhiteSpace(permissionMode) ? "acceptEdits" : permissionMode]);
        var tools = SplitToolRules(allowedTools);
        if (tools.Count > 0)
        {
            args.Add("--allowedTools");
            args.AddRange(tools);
        }
        // Codenvi never commits for the user, and Claude must not either: commits are made manually from the Android app.
        args.AddRange(["--disallowedTools", "Bash(git commit:*)"]);
        args.AddRange(["--permission-prompts", "none"]);
        if (maxTurns > 0) args.AddRange(["--max-turns", maxTurns.ToString()]);
        return args;
    }

    /// <summary>
    /// Splits "Read Edit Bash(npm test:*)" into rules on spaces, commas and newlines, but not inside
    /// parentheses, so a rule such as <c>Bash(npm test:*)</c> stays one argument.
    /// </summary>
    public static List<string> SplitToolRules(string allowedTools)
    {
        var rules = new List<string>();
        var current = new System.Text.StringBuilder();
        var depth = 0;
        foreach (var c in allowedTools ?? "")
        {
            if (c == '(') depth++;
            else if (c == ')' && depth > 0) depth--;
            if (depth == 0 && (c == ' ' || c == ',' || c == '\n' || c == '\r' || c == '\t'))
            {
                if (current.Length > 0) { rules.Add(current.ToString()); current.Clear(); }
                continue;
            }
            current.Append(c);
        }
        if (current.Length > 0) rules.Add(current.ToString());
        return rules;
    }

    [GeneratedRegex(@"(\d+)\.(\d+)\.(\d+)")]
    private static partial Regex VersionRegex();
}
