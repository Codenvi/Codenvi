namespace Codenvi.Contracts.Api;

// ---------- Pairing & tokens ----------
public sealed record PairRequest(string Code, Guid InstallId, string Kind, string Name, string AppVersion, int AppBuild, string? OsVersion, string? DeviceModel);
public sealed record TokenResponse(string AccessToken, long AccessExpiresAtMs, string RefreshToken, long RefreshExpiresAtMs, Guid InstallId);
public sealed record RefreshRequest(Guid InstallId, string RefreshToken);
public sealed record CreatePairingCodeRequest(string? IntendedKind);
public sealed record PairingCodeResponse(string Code, string Host, long ExpiresAtMs);

// ---------- Installs ----------
public sealed record InstallDto(Guid Id, string Kind, string Name, string AppVersion, int AppBuild, string? OsVersion,
    string? DeviceModel, string? LastIp, long PairedAtMs, long LastSeenAtMs, bool Revoked, bool IsSelf);
public sealed record RenameRequest(string Name);

// ---------- Devices ----------
public sealed record RegisterDeviceRequest(Guid? Id, string Name, byte[] KeyCheck);
public sealed record DeviceDto(Guid Id, Guid InstallId, string Name, int KeyVersion, byte[] KeyCheck, bool Online, long? LastOnlineAtMs, long CreatedAtMs);
public sealed record RotateKeyRequest(int KeyVersion, byte[] KeyCheck);

// ---------- Projects & sessions ----------
public sealed record ProjectDto(Guid Id, Guid DeviceId, byte[] EncMeta, Guid? DefaultSessionId, int SortOrder, long CreatedAtMs, long UpdatedAtMs);
public sealed record PutProjectRequest(Guid DeviceId, byte[] EncMeta, Guid? DefaultSessionId, int SortOrder);
public sealed record PatchProjectRequest(byte[]? EncMeta, int? SortOrder, Guid? DefaultSessionId);

public sealed record SessionDto(Guid Id, Guid ProjectId, byte[] EncMeta, long CreatedAtMs, long UpdatedAtMs);
public sealed record PutSessionRequest(Guid ProjectId, byte[] EncMeta);
public sealed record PatchSessionRequest(byte[] EncMeta);

// ---------- Messages & jobs ----------
public sealed record MessageDto(Guid Id, Guid SessionId, long Seq, string Role, Guid? JobId, byte[] Ciphertext, long ClientCreatedAtMs, long CreatedAtMs);
public sealed record SendPromptRequest(Guid MessageId, byte[] Ciphertext, long ClientCreatedAtMs, string? Model, string? Effort);
public sealed record SendPromptResponse(Guid MessageId, Guid JobId, long Seq);
public sealed record JobDto(Guid Id, Guid DeviceId, Guid ProjectId, Guid SessionId, Guid PromptMessageId, string Kind,
    string? Model, string? Effort, string Status, string? ErrorCode, byte[]? EncError,
    long QueuedAtMs, long? StartedAtMs, long? FinishedAtMs);

// ---------- Commits ----------
public sealed record CommitDto(Guid Id, Guid ProjectId, Guid? JobId, string GitHash, string Kind, byte[] EncMeta, Guid? RevertedBy, long CreatedAtMs);
public sealed record PutCommitRequest(Guid? JobId, string GitHash, string Kind, byte[] EncMeta, Guid? RevertedBy);

// ---------- Control ----------
public sealed record ControlRequest(Guid RequestId, byte[] Ciphertext, long ClientCreatedAtMs);

// ---------- Catalog / health ----------
public sealed record ModelCatalogEntry(string Alias, string Label, IReadOnlyList<string> Efforts, bool Enabled, int SortOrder);
public sealed record HealthResponse(string Status, string Version, bool Database);
