using System.Collections.ObjectModel;
using System.Diagnostics;
using Codenvi.Desktop.Claude;
using Codenvi.Desktop.Core;
using Codenvi.Desktop.Git;
using Codenvi.Desktop.Jobs;
using Codenvi.Desktop.Services;
using Codenvi.Desktop.Views;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;

namespace Codenvi.Desktop.ViewModels;

public sealed record CommitItem(LocalCommit Commit)
{
    public string ShortHash => Commit.GitHash[..Math.Min(7, Commit.GitHash.Length)];
    public string When => Commit.CreatedAt.ToLocalTime().ToString("g");
    public string Message => Commit.Message;
    public string Files => $"{Commit.FilesChanged} (+{Commit.Insertions} −{Commit.Deletions})";
    public string Kind => Commit.Kind;
    public bool Reverted => Commit.RevertedBy is not null;
    public string Marker => Reverted ? "Reverted" : Commit.Kind == "rollback" ? "Rollback" : Commit.Kind == "baseline" ? "Baseline" : "";
}

public sealed partial class ProjectViewModel : ObservableObject
{
    private readonly MainViewModel _main;
    private readonly AppState _state;
    private readonly LocalDb _db;
    private readonly ProjectService _projects;
    private readonly JobScheduler _scheduler;
    private readonly ClaudeCli _claude;
    private readonly GitService _git;
    private readonly ScriptRunner _scripts;

    public LocalProject Project { get; private set; }
    public Guid Id => Project.Id;

    public ObservableCollection<LocalSession> Sessions { get; } = new();
    public ObservableCollection<CommitItem> Commits { get; } = new();
    public ObservableCollection<string> ActivityLines { get; } = new();
    public IReadOnlyList<string> PermissionModes { get; } = ["acceptEdits", "default", "plan", "bypassPermissions"];
    public IReadOnlyList<string> Models { get; } = ["default", "sonnet", "opus", "haiku", "fable"];
    public IReadOnlyList<string> Efforts { get; } = ["default", "low", "medium", "high", "xhigh", "max"];

    [ObservableProperty] private string _name;
    [ObservableProperty] private string _folderPath;
    [ObservableProperty] private string _devBranch;
    [ObservableProperty] private ProjectActivity _activity;
    [ObservableProperty] private string _statusText = "Idle";
    [ObservableProperty] private string _elapsedText = "";
    [ObservableProperty] private bool _isRunning;
    [ObservableProperty] private bool _paused;
    [ObservableProperty] private string _gitState = "";
    [ObservableProperty] private string _memoryText = "";
    [ObservableProperty] private bool _hasUncommittedChanges;
    [ObservableProperty] private LocalSession? _selectedSession;
    [ObservableProperty] private CommitItem? _selectedCommit;
    [ObservableProperty] private string _testResult = "";

    // Settings tab
    [ObservableProperty] private string _permissionMode;
    [ObservableProperty] private string _allowedTools;
    [ObservableProperty] private int _maxTurns;
    [ObservableProperty] private int _timeoutMinutes;
    [ObservableProperty] private string _defaultModel;
    [ObservableProperty] private string _defaultEffort;
    [ObservableProperty] private string _shellScript;
    [ObservableProperty] private bool _scriptRunning;

    public ProjectViewModel(LocalProject p, MainViewModel main, AppState state, LocalDb db, ProjectService projects,
        JobScheduler scheduler, ClaudeCli claude, GitService git, ScriptRunner scripts)
    {
        _scripts = scripts;
        _main = main; _state = state; _db = db; _projects = projects; _scheduler = scheduler; _claude = claude; _git = git;
        Project = p;
        _name = p.Name; _folderPath = p.FolderPath; _devBranch = p.DevBranch; _paused = p.Paused;
        _permissionMode = p.PermissionMode; _allowedTools = p.AllowedTools; _maxTurns = p.MaxTurns; _timeoutMinutes = p.TimeoutMinutes;
        _defaultModel = p.DefaultModel ?? "default"; _defaultEffort = p.DefaultEffort ?? "default";
        _shellScript = p.ShellScript ?? "";
        RefreshStatus();
    }

    public void Update(LocalProject p)
    {
        Project = p;
        Name = p.Name; FolderPath = p.FolderPath; DevBranch = p.DevBranch;
        if (Paused != p.Paused) Paused = p.Paused;
        RefreshStatus();
        if (_main.Selected == this) LoadSessionsAndCommits();
    }

    public void RefreshStatus()
    {
        var r = _state.Runtime(Id);
        Activity = r.Activity;
        IsRunning = r.RunningJobId is not null;
        ScriptRunning = r.ScriptRunning;
        HasUncommittedChanges = r.HasUncommittedChanges;
        StatusText = Paused ? "Paused" : r.Activity switch
        {
            ProjectActivity.Running => "● Running",
            ProjectActivity.Queued => $"Queued {r.QueuedCount}",
            ProjectActivity.WaitingLogin => "Waiting for Claude sign-in",
            ProjectActivity.Error => "Error",
            _ => "Idle",
        };
        RefreshElapsed();
    }

    public void RefreshElapsed()
    {
        var since = _state.Runtime(Id).RunningSince;
        ElapsedText = since is null ? "" : (DateTime.UtcNow - since.Value).ToString(@"m\:ss");
    }

    public void AddActivity(string line)
    {
        ActivityLines.Add($"{DateTime.Now:HH:mm:ss}  {line}");
        while (ActivityLines.Count > 500) ActivityLines.RemoveAt(0);
    }

    public void LoadDetails()
    {
        LoadSessionsAndCommits();
        MemoryText = SafeRead();
        _ = RefreshGitStateAsync();
    }

    private void LoadSessionsAndCommits()
    {
        var selected = SelectedSession?.Id;
        Sessions.Clear();
        foreach (var s in _db.Sessions(Id)) Sessions.Add(s);
        SelectedSession = Sessions.FirstOrDefault(s => s.Id == selected) ?? Sessions.FirstOrDefault();
        Commits.Clear();
        foreach (var c in _db.Commits(Id)) Commits.Add(new CommitItem(c));
    }

    private string SafeRead()
    {
        try { return _projects.ReadMemory(Project); } catch { return ""; }
    }

    private async Task RefreshGitStateAsync()
    {
        try { GitState = await _git.StatusShortAsync(Project.FolderPath); }
        catch (Exception e) { GitState = e.Message; }
    }

    partial void OnPausedChanged(bool value)
    {
        if (Project.Paused == value) return;
        Project.Paused = value;
        _db.UpsertProject(Project);
        RefreshStatus();
        if (!value) _scheduler.Wake();
    }

    // ---------------- Overview ----------------
    [RelayCommand]
    private void OpenFolder() => Process.Start(new ProcessStartInfo("explorer.exe", $"\"{Project.FolderPath}\"") { UseShellExecute = true });

    [RelayCommand]
    private void OpenInVsCode()
    {
        var code = ProcessRunner.FindOnPath("code");
        if (code is null) { Dialogs.Error(_main.Owner, "VS Code ('code') was not found on PATH."); return; }
        var psi = new ProcessStartInfo(code) { UseShellExecute = false, CreateNoWindow = true };
        psi.ArgumentList.Add(Project.FolderPath);
        Process.Start(psi);
    }

    [RelayCommand]
    private async Task TestClaudeAsync()
    {
        TestResult = "Testing…";
        try { TestResult = await _projects.TestClaudeAsync(Project, _claude); }
        catch (Exception e) { TestResult = e.Message; }
    }

    [RelayCommand]
    private async Task RefreshGitAsync() => await RefreshGitStateAsync();

    // ---------------- Sessions ----------------
    [RelayCommand]
    private async Task NewSessionAsync()
    {
        var name = Dialogs.Input(_main.Owner, "New session", "Session name:", $"Session {Sessions.Count + 1}");
        if (name is null) return;
        await _main.Guard(async () => { await _projects.CreateSessionAsync(Id, name); LoadSessionsAndCommits(); });
    }

    [RelayCommand]
    private async Task RenameSessionAsync()
    {
        if (SelectedSession is not { } s) return;
        var name = Dialogs.Input(_main.Owner, "Rename session", "New name:", s.Name);
        if (name is null) return;
        await _main.Guard(async () => { await _projects.RenameSessionAsync(s, name); LoadSessionsAndCommits(); });
    }

    [RelayCommand]
    private async Task ClearSessionMemoryAsync()
    {
        if (SelectedSession is not { } s) return;
        if (!Dialogs.Confirm(_main.Owner, "Clear chat memory",
                $"Clear the chat history of \"{s.Name}\" and start Claude with empty context on the next prompt?", "Clear")) return;
        await _main.Guard(async () => { await _projects.ClearMemoryAsync(s); LoadSessionsAndCommits(); });
    }

    [RelayCommand]
    private async Task ClearAllMemoryAsync()
    {
        if (!Dialogs.Confirm(_main.Owner, "Clear all sessions",
                "Clear the chat history of every session in this project and start each with empty context?", "Clear all")) return;
        await _main.Guard(async () => { foreach (var s in _db.Sessions(Id)) await _projects.ClearMemoryAsync(s); LoadSessionsAndCommits(); });
    }

    [RelayCommand]
    private async Task DeleteSessionAsync()
    {
        if (SelectedSession is not { } s) return;
        if (!Dialogs.Confirm(_main.Owner, "Delete session", $"Delete \"{s.Name}\" and its chat history?", "Delete")) return;
        await _main.Guard(async () => { await _projects.DeleteSessionAsync(s); LoadSessionsAndCommits(); });
    }

    // ---------------- Activity ----------------
    [RelayCommand]
    private async Task CancelJobAsync()
    {
        if (_scheduler.RunningJob(Id) is { } jobId) await _scheduler.CancelAsync(jobId);
    }

    [RelayCommand]
    private async Task CommitLeftoversAsync() => await _main.Guard(async () =>
    {
        var hash = await _git.CommitTaskAsync(Project.FolderPath, "Codenvi: keep changes from an unfinished job",
            "Committed from the Codenvi Activity tab.", _state.Settings.GitAuthorName, _state.Settings.GitAuthorEmail);
        if (hash is not null) await _projects.RecordCommitAsync(Project, null, hash, "task");
        _state.UpdateRuntime(Id, r => r.HasUncommittedChanges = false);
        LoadDetails();
    });

    [RelayCommand]
    private async Task DiscardLeftoversAsync()
    {
        if (!Dialogs.Confirm(_main.Owner, "Discard changes",
                "Discard ALL uncommitted changes in this project folder (git restore + git clean)? This cannot be undone.", "Discard")) return;
        await _main.Guard(async () =>
        {
            await _git.DiscardAsync(Project.FolderPath);
            _state.UpdateRuntime(Id, r => r.HasUncommittedChanges = false);
            LoadDetails();
        });
    }

    // ---------------- Commits ----------------
    [RelayCommand]
    private async Task RollbackAsync()
    {
        if (SelectedCommit is not { } c) return;
        if (!Dialogs.Confirm(_main.Owner, "Roll back",
                $"Roll back to {c.ShortHash} (\"{c.Message}\")?\n\nEvery later commit is reverted in one new commit. History is kept, so you can roll forward again.",
                "Roll back")) return;
        await _main.Guard(async () => { await _projects.RollbackAsync(Id, c.Commit.GitHash); LoadDetails(); });
    }

    // ---------------- Memory ----------------
    [RelayCommand]
    private void SaveMemory()
    {
        try { _projects.WriteMemory(Project, MemoryText); Dialogs.Info(_main.Owner, "CLAUDE.md", "Saved."); }
        catch (Exception e) { Dialogs.Error(_main.Owner, e.Message); }
    }

    [RelayCommand]
    private void ReloadMemory() => MemoryText = SafeRead();

    // ---------------- Settings ----------------
    [RelayCommand]
    private async Task SaveSettingsAsync()
    {
        var script = string.IsNullOrWhiteSpace(ShellScript) ? null : ShellScript.Replace("\r\n", "\n").Trim();
        var scriptChanged = script != (Project.ShellScript is null ? null : Project.ShellScript.Replace("\r\n", "\n").Trim());
        if (script is not null && scriptChanged &&
            !Dialogs.Confirm(_main.Owner, "Phone-run script",
                "Your phone will be able to run this script on this PC with one tap, as you, with full access to your files. Only save commands you trust. Continue?",
                "Save script"))
            return;
        Project.PermissionMode = PermissionMode;
        Project.AllowedTools = AllowedTools.Trim();
        Project.MaxTurns = Math.Clamp(MaxTurns, 1, 500);
        Project.TimeoutMinutes = Math.Clamp(TimeoutMinutes, 1, 24 * 60);
        Project.DefaultModel = DefaultModel == "default" ? null : DefaultModel;
        Project.DefaultEffort = DefaultEffort == "default" ? null : DefaultEffort;
        if (PermissionMode == "bypassPermissions" &&
            !Dialogs.Confirm(_main.Owner, "bypassPermissions",
                "bypassPermissions lets Claude Code run ANY command on this PC without asking. Only use it for folders you can afford to lose. Continue?",
                "Use it"))
            return;
        Project.ShellScript = script;
        _db.UpsertProject(Project);
        if (scriptChanged)
        {
            // The phone only learns that a script exists (a flag inside the encrypted project info).
            try { await _projects.UploadProjectAsync(Project); }
            catch (Exception e) { Dialogs.Error(_main.Owner, "Saved on this PC, but the phone could not be told yet: " + e.Message); return; }
        }
        Dialogs.Info(_main.Owner, "Project settings", "Saved. They apply to the next job.");
    }

    [RelayCommand]
    private void StopScript() => _scripts.Stop(Id);
}
