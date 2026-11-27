namespace Codenvi.Contracts;

// Plaintext JSON shapes that are encrypted end-to-end (docs/design.md §5.3).
// Android mirrors these in app/src/main/java/app/codenvi/crypto/Plaintexts.kt.

/// <summary>
/// User prompt. The project/session/model/effort are repeated inside the ciphertext so the desktop can
/// check that the server did not move the prompt to another project or change the model.
/// </summary>
public sealed record PromptPlain(string Text, Guid ProjectId, Guid SessionId, string? Model, string? Effort);

public sealed record ResultPlain(string Text, decimal? CostUsd, long? DurationMs, string? CommitHash, int? FilesChanged,
    int Part = 1, int Parts = 1);

public sealed record ProgressPlain(string Line);

/// <summary>HasScript and ScriptRunning are only UI hints for the phone (null = no / not running, omitted from the JSON). The script text never leaves the PC.</summary>
public sealed record ProjectMeta(string Name, string DevBranch, bool? HasScript = null, bool? ScriptRunning = null);

public sealed record SessionMeta(string Name);

public sealed record CommitMeta(string Message, int FilesChanged, int Insertions, int Deletions);

public sealed record ErrorPlain(string Summary, string? Detail);

/// <summary>Control commands (docs/design.md §7.5).</summary>
public static class ControlCommands
{
    public const string SessionCreate = "session.create";
    public const string SessionClearMemory = "session.clearMemory";
    public const string MemoryGet = "project.memory.get";
    public const string MemorySet = "project.memory.set";
    public const string Rollback = "project.rollback";
    public const string Status = "project.status";
    /// <summary>Lists the uncommitted files (paths only) as text lines "N|U|D, tab, path" for the phone's Code changes page.</summary>
    public const string Changes = "project.changes";
    /// <summary>The unified diff of one uncommitted file against HEAD; the path (as listed by project.changes) is in Text.</summary>
    public const string Diff = "project.diff";
    /// <summary>Runs the PC-local shell script of the project in the background. Carries no script text.</summary>
    public const string RunScript = "project.script.run";
    /// <summary>Stops the script the PC started for this project (kills its process tree).</summary>
    public const string StopScript = "project.script.stop";
    /// <summary>Commits whatever is left uncommitted in the dev branch working tree (e.g. after a cancelled/failed job).</summary>
    public const string Commit = "project.commit";
    /// <summary>Merges the dev branch into "main" and pushes "main" to origin, on the PC.</summary>
    public const string Publish = "project.publish";
    /// <summary>Fetches origin/main and merges it into the dev branch, on the PC.</summary>
    public const string Pull = "project.pull";
    /// <summary>PC-level command (ProjectId is Guid.Empty): creates a folder named Name in the PC's projects location, initializes git and registers it as a project.</summary>
    public const string ProjectCreate = "project.create";
    /// <summary>Removes the project from Codenvi and the server. Never deletes the folder, branch or commits on the PC.</summary>
    public const string ProjectDelete = "project.delete";
}

public sealed record ControlPlain(string Command, Guid ProjectId, Guid? SessionId = null, string? Name = null, string? Text = null, string? CommitHash = null);

public sealed record ControlResultPlain(bool Ok, string? Error = null, string? Text = null, ProjectStatusPlain? Status = null);

/// <summary>
/// AheadOfMain is how much Publish still has to push: commits not yet on origin/main (or, without an origin/main, not yet on the local "main"), 0 when nothing differs.
/// BehindOrigin is how many commits on origin/main are not yet in the dev branch (0 when there is no origin/main).
/// </summary>
public sealed record ProjectStatusPlain(string Branch, bool Dirty, string? LastCommitHash, string? LastCommitMessage,
    Guid? RunningJobId, bool BranchOk, int AheadOfMain = 0, int BehindOrigin = 0);
