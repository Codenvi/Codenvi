using System.Text;
using Codenvi.Desktop.Claude;

namespace Codenvi.Desktop.Git;

public sealed record GitCommitInfo(string Hash, string Subject, int FilesChanged, int Insertions, int Deletions, DateTimeOffset When);

public sealed class GitException(string message) : Exception(message);

/// <summary>
/// All git work goes through git.exe with porcelain output (Project.md §9). Codenvi never pushes,
/// merges to main or rewrites history.
/// </summary>
public sealed class GitService
{
    public static string? GitPath => ProcessRunner.FindOnPath("git");
    public bool IsAvailable => GitPath is not null;

    private static async Task<ProcessResult> Git(string folder, IEnumerable<string> args, string? author = null,
        string? email = null, bool check = true, CancellationToken ct = default)
    {
        var git = GitPath ?? throw new GitException("git was not found on PATH. Install Git for Windows.");
        var all = new List<string> { "-c", "core.quotepath=false", "-c", "core.autocrlf=false" };
        if (author is not null) all.AddRange(["-c", $"user.name={author}", "-c", $"user.email={email}"]);
        all.AddRange(args);
        var r = await ProcessRunner.RunAsync(git, all, folder, timeout: TimeSpan.FromMinutes(5), ct: ct);
        if (check && r.ExitCode != 0)
            throw new GitException($"git {string.Join(' ', args.Take(3))} failed: {FirstLines(r.StdErr + r.StdOut, 5)}");
        return r;
    }

    private static string FirstLines(string s, int n) => string.Join('\n', s.Split('\n', StringSplitOptions.RemoveEmptyEntries).Take(n)).Trim();

    public async Task<bool> IsRepoAsync(string folder) =>
        Directory.Exists(folder) && (await Git(folder, ["rev-parse", "--is-inside-work-tree"], check: false)).StdOut.Trim() == "true";

    public async Task<bool> IsRepoRootAsync(string folder)
    {
        var r = await Git(folder, ["rev-parse", "--show-toplevel"], check: false);
        return r.ExitCode == 0 && Path.GetFullPath(r.StdOut.Trim()).TrimEnd('\\', '/')
            .Equals(Path.GetFullPath(folder).TrimEnd('\\', '/'), StringComparison.OrdinalIgnoreCase);
    }

    public async Task<bool> HasCommitsAsync(string folder) =>
        (await Git(folder, ["rev-parse", "--verify", "HEAD"], check: false)).ExitCode == 0;

    public async Task InitAsync(string folder, string author, string email)
    {
        await Git(folder, ["init"]);
        await Git(folder, ["add", "-A"]);
        await Git(folder, ["commit", "--allow-empty", "-m", "Codenvi baseline"], author, email);
    }

    public async Task<bool> IsDirtyAsync(string folder) =>
        (await Git(folder, ["status", "--porcelain=v1", "--untracked-files=all"])).StdOut.Trim().Length > 0;

    public async Task<string> CurrentBranchAsync(string folder) =>
        (await Git(folder, ["rev-parse", "--abbrev-ref", "HEAD"])).StdOut.Trim();

    public async Task<string?> HeadAsync(string folder)
    {
        var r = await Git(folder, ["rev-parse", "HEAD"], check: false);
        return r.ExitCode == 0 ? r.StdOut.Trim() : null;
    }

    public async Task<bool> BranchExistsAsync(string folder, string branch) =>
        (await Git(folder, ["rev-parse", "--verify", "--quiet", "refs/heads/" + branch], check: false)).ExitCode == 0;

    public async Task CommitBaselineAsync(string folder, string author, string email)
    {
        await Git(folder, ["add", "-A"]);
        await Git(folder, ["commit", "-m", "Codenvi baseline"], author, email);
    }

    public async Task StashAsync(string folder) => await Git(folder, ["stash", "push", "--include-untracked", "-m", "Codenvi: stashed before registering"]);

    /// <summary>git switch -c &lt;branch&gt;, or git switch &lt;branch&gt; if it exists.</summary>
    public async Task SwitchToAsync(string folder, string branch)
    {
        if (await BranchExistsAsync(folder, branch)) await Git(folder, ["switch", branch]);
        else await Git(folder, ["switch", "-c", branch]);
    }

    public async Task<bool> IsValidBranchNameAsync(string folder, string branch) =>
        (await Git(folder, ["check-ref-format", "--branch", branch], check: false)).ExitCode == 0;

    /// <summary>Commits in preHash..HEAD, oldest first.</summary>
    public async Task<List<string>> RevListAsync(string folder, string? fromExclusive)
    {
        var range = fromExclusive is null ? "HEAD" : $"{fromExclusive}..HEAD";
        var r = await Git(folder, ["rev-list", "--reverse", range], check: false);
        return r.ExitCode != 0 ? [] : r.StdOut.Split('\n', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries).ToList();
    }

    public async Task<GitCommitInfo> CommitInfoAsync(string folder, string hash)
    {
        var meta = await Git(folder, ["show", "-s", "--format=%H%x1f%s%x1f%cI", hash]);
        var parts = meta.StdOut.Trim().Split('\x1f');
        var stat = await Git(folder, ["show", "--numstat", "--format=", hash]);
        int files = 0, ins = 0, del = 0;
        foreach (var line in stat.StdOut.Split('\n', StringSplitOptions.RemoveEmptyEntries))
        {
            var cols = line.Split('\t');
            if (cols.Length < 3) continue;
            files++;
            if (int.TryParse(cols[0], out var a)) ins += a;
            if (int.TryParse(cols[1], out var d)) del += d;
        }
        return new GitCommitInfo(parts[0], parts.Length > 1 ? parts[1] : "", files, ins, del,
            parts.Length > 2 && DateTimeOffset.TryParse(parts[2], out var when) ? when : DateTimeOffset.UtcNow);
    }

    /// <summary>
    /// Commit step after a task (Project.md §9.2). Returns the new commit hash, or null if nothing changed.
    /// </summary>
    public async Task<string?> CommitTaskAsync(string folder, string summary, string body, string author, string email)
    {
        await Git(folder, ["add", "-A"]);
        var diff = await Git(folder, ["diff", "--cached", "--quiet"], check: false);
        if (diff.ExitCode == 0) return null;
        await Git(folder, ["commit", "-m", summary, "-m", body], author, email);
        return await HeadAsync(folder);
    }

    public static string TaskSummary(string prompt)
    {
        var oneLine = prompt.ReplaceLineEndings(" ").Trim();
        if (oneLine.Length > 60) oneLine = oneLine[..60].TrimEnd();
        return "Codenvi: " + (oneLine.Length == 0 ? "(empty prompt)" : oneLine);
    }

    public static string TaskBody(string? model, string reply, Guid jobId, Guid sessionId)
    {
        var sb = new StringBuilder();
        sb.AppendLine($"Model: {(string.IsNullOrWhiteSpace(model) ? "default" : model)}");
        sb.AppendLine();
        var r = reply.Trim();
        sb.AppendLine(r.Length > 500 ? r[..500] + "…" : r);
        sb.AppendLine();
        sb.AppendLine($"Codenvi-Job: {jobId:D}");
        sb.Append($"Codenvi-Session: {sessionId:D}");
        return sb.ToString();
    }

    /// <summary>
    /// Rollback by revert (Project.md §9.3): reverts every commit after <paramref name="target"/>, newest
    /// first, as one new commit. Returns the rollback commit hash and the reverted hashes.
    /// </summary>
    public async Task<(string hash, List<string> reverted)> RollbackAsync(string folder, string target, string author, string email)
    {
        if (await IsDirtyAsync(folder)) throw new GitException("The working tree has uncommitted changes - commit or stash first.");
        var targetFull = (await Git(folder, ["rev-parse", "--verify", target + "^{commit}"])).StdOut.Trim();
        var isAncestor = await Git(folder, ["merge-base", "--is-ancestor", targetFull, "HEAD"], check: false);
        if (isAncestor.ExitCode != 0) throw new GitException("That commit is not on the current branch.");

        var commits = await RevListAsync(folder, targetFull);
        if (commits.Count == 0) throw new GitException("Already at that commit.");

        // Merge commits are not expected on the dev branch; if any exist, revert them against parent 1.
        var merges = (await Git(folder, ["rev-list", "--merges", $"{targetFull}..HEAD"])).StdOut
            .Split('\n', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries).ToHashSet();
        var newestFirst = Enumerable.Reverse(commits).ToList();
        foreach (var c in newestFirst)
        {
            var args = new List<string> { "revert", "--no-commit" };
            if (merges.Contains(c)) args.AddRange(["-m", "1"]);
            args.Add(c);
            var r = await Git(folder, args, author, email, check: false);
            if (r.ExitCode != 0)
            {
                await Git(folder, ["revert", "--abort"], check: false);
                await Git(folder, ["reset", "--hard", "HEAD"], check: false); // only undoes the partial revert; tree was clean before
                throw new GitException("Rollback conflicts - resolve manually.");
            }
        }

        var shortTarget = targetFull[..7];
        var message = $"Codenvi: roll back to {shortTarget}";
        var body = "Reverts: " + string.Join(", ", newestFirst.Select(h => h[..10]));
        var staged = await Git(folder, ["diff", "--cached", "--quiet"], check: false);
        var commitArgs = staged.ExitCode == 0
            ? new[] { "commit", "--allow-empty", "-m", message, "-m", body }
            : new[] { "commit", "-m", message, "-m", body };
        await Git(folder, commitArgs, author, email);
        return ((await HeadAsync(folder))!, newestFirst);
    }

    public async Task DiscardAsync(string folder)
    {
        await Git(folder, ["restore", "--staged", "--worktree", "--", "."], check: false);
        await Git(folder, ["clean", "-fd"]);
    }

    public async Task<string> StatusShortAsync(string folder) => (await Git(folder, ["status", "--short", "--branch"])).StdOut.Trim();
}
