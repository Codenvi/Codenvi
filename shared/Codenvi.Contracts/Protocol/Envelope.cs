using System.Text.Json;

namespace Codenvi.Contracts.Protocol;

/// <summary>WebSocket envelope: <c>{ "type": "...", "id": "uuid", "ts": 1764000000000, "payload": { } }</c>.</summary>
public sealed record Envelope(string Type, Guid Id, long Ts, JsonElement Payload)
{
    public static Envelope Create<T>(string type, T payload) =>
        new(type, Guid.NewGuid(), Time.NowMs(), JsonSerializer.SerializeToElement(payload, CodenviJson.Options));

    public T PayloadAs<T>() => Payload.Deserialize<T>(CodenviJson.Options)
        ?? throw new JsonException($"Empty payload for {Type}.");

    public byte[] ToUtf8() => JsonSerializer.SerializeToUtf8Bytes(this, CodenviJson.Options);

    public static Envelope? FromUtf8(ReadOnlySpan<byte> json) =>
        JsonSerializer.Deserialize<Envelope>(json, CodenviJson.Options);
}

public static class MessageTypes
{
    public const string Hello = "hello";
    public const string Welcome = "welcome";
    public const string JobDispatch = "job.dispatch";
    public const string JobAck = "job.ack";
    public const string JobStatus = "job.status";
    public const string JobProgress = "job.progress";
    public const string JobResult = "job.result";
    public const string JobCancel = "job.cancel";
    public const string ControlRequest = "control.request";
    public const string ControlResponse = "control.response";
    public const string DevicePresence = "device.presence";
    public const string SyncChanged = "sync.changed";
    public const string Ping = "ping";
    public const string Pong = "pong";
    public const string Error = "error";
}

public static class JobStatuses
{
    public const string Queued = "queued";
    public const string Delivered = "delivered";
    public const string Running = "running";
    public const string Succeeded = "succeeded";
    public const string Failed = "failed";
    public const string Cancelled = "cancelled";
    public const string RejectedStale = "rejected_stale";
    public const string WaitingLogin = "waiting_login";

    public static readonly IReadOnlySet<string> All = new HashSet<string>
        { Queued, Delivered, Running, Succeeded, Failed, Cancelled, RejectedStale, WaitingLogin };

    public static readonly IReadOnlySet<string> Final = new HashSet<string>
        { Succeeded, Failed, Cancelled, RejectedStale };

    public static bool IsFinal(string status) => Final.Contains(status);
}

public static class ErrorCodes
{
    public const string BadCiphertext = "bad_ciphertext";
    public const string Replay = "replay";
    public const string Stale = "stale";
    public const string WrongBranch = "wrong_branch";
    public const string ClaudeNotFound = "claude_not_found";
    public const string ClaudeTooOld = "claude_too_old";
    public const string ClaudeFailed = "claude_failed";
    public const string Timeout = "timeout";
    public const string ProjectMissing = "project_missing";
    public const string GitFailed = "git_failed";
    public const string KeyRotated = "key_rotated";
    public const string Internal = "internal";
}

public static class SyncEntities
{
    public const string Device = "device";
    public const string Project = "project";
    public const string Session = "session";
    public const string Messages = "messages";
    public const string Commits = "commits";
    public const string Installs = "installs";
}
