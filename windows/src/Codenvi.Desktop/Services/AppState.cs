using System.Collections.Concurrent;
using Codenvi.Desktop.Claude;
using Codenvi.Desktop.Core;

namespace Codenvi.Desktop.Services;

public enum ProjectActivity { Idle, Queued, Running, Error, WaitingLogin }

public sealed class ProjectRuntime
{
    public int QueuedCount { get; set; }
    public Guid? RunningJobId { get; set; }
    public DateTime? RunningSince { get; set; }
    public string? LastError { get; set; }
    public bool WaitingLogin { get; set; }
    public bool HasUncommittedChanges { get; set; }
    /// <summary>A phone-started project script is running (independent of Claude jobs).</summary>
    public bool ScriptRunning { get; set; }

    public ProjectActivity Activity =>
        RunningJobId is not null ? ProjectActivity.Running
        : WaitingLogin ? ProjectActivity.WaitingLogin
        : QueuedCount > 0 ? ProjectActivity.Queued
        : LastError is not null ? ProjectActivity.Error
        : ProjectActivity.Idle;
}

/// <summary>
/// Process-wide state shared by background services and the UI. Events are raised on background
/// threads; view models marshal to the dispatcher.
/// </summary>
public sealed class AppState
{
    private readonly LocalDb _db;
    private readonly ConcurrentDictionary<Guid, ProjectRuntime> _runtime = new();

    public AppState(LocalDb db)
    {
        _db = db;
        Settings = db.LoadSettings();
    }

    public AppSettings Settings { get; private set; }
    public bool ServerConnected { get; private set; }
    public bool Revoked { get; private set; }
    public bool UpgradeRequired { get; private set; }
    public ClaudeStatus Claude { get; private set; } = new(ClaudeState.Unknown, null, null);

    public event Action? Changed;
    public event Action<Guid>? ProjectChanged;
    public event Action<Guid, string>? ActivityLine;
    public event Action<Guid, string>? TerminalLine;
    public event Action? ProjectsListChanged;
    public event Action<string>? Notify;

    public void SaveSettings(AppSettings s)
    {
        Settings = s;
        _db.SaveSettings(s);
        Changed?.Invoke();
    }

    public ProjectRuntime Runtime(Guid projectId) => _runtime.GetOrAdd(projectId, _ => new ProjectRuntime());

    public void UpdateRuntime(Guid projectId, Action<ProjectRuntime> change)
    {
        var r = Runtime(projectId);
        lock (r) change(r);
        ProjectChanged?.Invoke(projectId);
        Changed?.Invoke();
    }

    public int RunningCount => _runtime.Values.Count(r => r.RunningJobId is not null);

    public void SetConnected(bool connected) { ServerConnected = connected; Changed?.Invoke(); }
    public void SetRevoked() { Revoked = true; Changed?.Invoke(); Notify?.Invoke("This app was disconnected from the server."); }
    public void SetUpgradeRequired() { UpgradeRequired = true; Changed?.Invoke(); }
    public void SetClaude(ClaudeStatus status) { Claude = status; Changed?.Invoke(); }
    public void AddActivity(Guid projectId, string line) => ActivityLine?.Invoke(projectId, line);
    public void AddTerminal(Guid projectId, string line) => TerminalLine?.Invoke(projectId, line);
    public void RaiseProjectsListChanged() => ProjectsListChanged?.Invoke();
    public void RaiseNotify(string message) => Notify?.Invoke(message);
}
