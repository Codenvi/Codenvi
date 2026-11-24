namespace Codenvi.Contracts.Protocol;

// All byte[] fields serialize as Base64 strings (System.Text.Json default).

public sealed record HelloPayload(string Role, Guid? DeviceId);

public sealed record WelcomePayload(Guid InstallId, string ServerVersion, long ServerTimeMs);

public sealed record DispatchedJob(
    Guid Id, Guid DeviceId, Guid ProjectId, Guid SessionId, Guid PromptMessageId,
    string Kind, string? Model, string? Effort, long QueuedAtMs);

public sealed record EncryptedBlob(Guid MessageId, byte[] Ciphertext, long ClientCreatedAtMs);

/// <param name="RecentMessageIds">IDs of the most recent messages in the session (context hint only).</param>
public sealed record JobDispatchPayload(DispatchedJob Job, EncryptedBlob Prompt, IReadOnlyList<Guid> RecentMessageIds);

public sealed record JobAckPayload(Guid JobId);

public sealed record JobStatusPayload(
    Guid JobId, string Status, long? StartedAtMs = null, long? FinishedAtMs = null,
    string? ErrorCode = null, byte[]? EncError = null, Guid? SessionId = null, Guid? ProjectId = null);

public sealed record JobProgressPayload(Guid JobId, Guid SessionId, Guid MessageId, byte[] Ciphertext, long ClientCreatedAtMs);

/// <param name="Final">False for intermediate parts of a reply that was split (ciphertext cap 1 MB).</param>
public sealed record JobResultPayload(
    Guid JobId, Guid SessionId, Guid MessageId, byte[] Ciphertext, long ClientCreatedAtMs,
    string? CommitHash = null, bool Final = true);

public sealed record JobCancelPayload(Guid JobId);

public sealed record ControlRequestPayload(Guid RequestId, Guid DeviceId, Guid ProjectId, byte[] Ciphertext, long ClientCreatedAtMs);

public sealed record ControlResponsePayload(Guid RequestId, Guid DeviceId, Guid ProjectId, byte[] Ciphertext, long ClientCreatedAtMs);

public sealed record DevicePresencePayload(Guid DeviceId, bool Online);

public sealed record SyncChangedPayload(string Entity, Guid Id, Guid? ParentId = null);

public sealed record ErrorPayload(string Code, string Message);
