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

/// <summary>HasScript is only a UI hint for the phone (null = no script, omitted from the JSON). The script text never leaves the PC.</summary>
public sealed record ProjectMeta(string Name, string DevBranch, bool? HasScript = null);

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
    /// <summary>Runs the PC-local shell script of the project in the background. Carries no script text.</summary>
    public const string RunScript = "project.script.run";
}

public sealed record ControlPlain(string Command, Guid ProjectId, Guid? SessionId = null, string? Name = null, string? Text = null, string? CommitHash = null);

public sealed record ControlResultPlain(bool Ok, string? Error = null, string? Text = null, ProjectStatusPlain? Status = null);

public sealed record ProjectStatusPlain(string Branch, bool Dirty, string? LastCommitHash, string? LastCommitMessage, Guid? RunningJobId, bool BranchOk);
