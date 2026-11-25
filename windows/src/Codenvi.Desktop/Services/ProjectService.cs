using Codenvi.Contracts;
using Codenvi.Contracts.Api;
using Codenvi.Contracts.Crypto;
using Codenvi.Desktop.Claude;
using Codenvi.Desktop.Core;
using Codenvi.Desktop.Git;
using Codenvi.Desktop.Jobs;
using Codenvi.Desktop.Net;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Logging;

namespace Codenvi.Desktop.Services;

/// <summary>Questions the UI asks during project registration (nothing is ever discarded silently).</summary>
public interface IUserPrompts
{
    bool Confirm(string title, string message);
    /// <summary>Returns the index of the chosen option, or -1 for cancel.</summary>
    int Choose(string title, string message, params string[] options);
    void Info(string title, string message);
}

public sealed class ProjectException(string message) : Exception(message);

/// <summary>Project, session and commit management on the PC (docs/design.md §8.5, §8.7, §9).</summary>
public sealed class ProjectService(LocalDb db, ApiClient api, CryptoService crypto, GitService git, AppState state,
    IServiceProvider services, ILogger<ProjectService> log)
{
    private JobScheduler Scheduler => services.GetRequiredService<JobScheduler>();
    private string Author => state.Settings.GitAuthorName;
    private string Email => state.Settings.GitAuthorEmail;
    public const string MainBranch = "main";

    // ---------------- Register ----------------
    public async Task<LocalProject?> RegisterAsync(string folder, string name, IUserPrompts ui)
    {
        folder = Path.GetFullPath(folder);
        if (!git.IsAvailable)
        {
            ui.Info("Git not found", "Codenvi needs Git for Windows on PATH.\n\nInstall it from https://git-scm.com/download/win and restart Codenvi.");
            return null;
        }
        if (db.Projects().Any(p => string.Equals(p.FolderPath, folder, StringComparison.OrdinalIgnoreCase)))
            throw new ProjectException("This folder is already registered.");

        var branch = state.Settings.DefaultDevBranch;
        if (!await git.IsRepoAsync(folder))
        {
            if (!ui.Confirm("Initialize git", $"{folder}\n\nis not a git repository. Initialize git and commit the current files as a baseline?"))
                return null;
            await git.InitAsync(folder, Author, Email);
        }
        else if (!await git.IsRepoRootAsync(folder))
        {
            throw new ProjectException("Pick the repository's top-level folder.");
        }
        else if (!await git.HasCommitsAsync(folder))
        {
            await git.CommitBaselineAsync(folder, Author, Email);
        }

        if (await git.IsDirtyAsync(folder))
        {
            var choice = ui.Choose("Uncommitted changes",
                "The folder has uncommitted changes. What should Codenvi do with them before switching to its branch?",
                "Commit current changes as baseline", "Stash them", "Cancel");
            if (choice == 0) await git.CommitBaselineAsync(folder, Author, Email);
            else if (choice == 1) await git.StashAsync(folder);
            else return null;
        }

        await git.SwitchToAsync(folder, branch);
        var head = await git.HeadAsync(folder) ?? throw new ProjectException("Could not read HEAD.");

        var project = new LocalProject
        {
            Id = Guid.NewGuid(), Name = name.Trim(), FolderPath = folder, DevBranch = branch,
            SortOrder = db.Projects().Count,
        };
        db.UpsertProject(project);

        var session = NewLocalSession(project.Id, Guid.NewGuid(), "Main");
        db.UpsertSession(session);
        try
        {
            await UploadProjectAsync(project, session.Id);
            await UploadSessionAsync(session);
            await RecordCommitAsync(project, null, head, "baseline");
        }
        catch (Exception e)
        {
            log.LogWarning(e, "Project registered locally; server sync will retry");
        }
        state.RaiseProjectsListChanged();

        ui.Info("Project registered",
            "Claude Code may ask you to trust this folder the first time it runs here.\n\n" +
            "If a job fails with a trust message, open a terminal in the folder, run `claude` once, accept the trust prompt, " +
            "then press Retry (or use \"Test Claude here\" on the project Settings tab).");
        return project;
    }

    public static LocalSession NewLocalSession(Guid projectId, Guid id, string name) => new()
    {
        Id = id, ProjectId = projectId, Name = name, ClaudeSessionId = Guid.NewGuid(), Started = false, CreatedAt = DateTime.UtcNow,
    };

    public Task UploadProjectAsync(LocalProject p, Guid? defaultSessionId = null) =>
        api.PutProjectAsync(p.Id, new PutProjectRequest(crypto.DeviceId,
            crypto.EncryptMeta(ObjectTypes.ProjectMeta, p.Id, new ProjectMeta(p.Name, p.DevBranch, string.IsNullOrWhiteSpace(p.ShellScript) ? null : true,
                state.Runtime(p.Id).ScriptRunning ? true : null)), defaultSessionId, p.SortOrder));

    public Task UploadSessionAsync(LocalSession s) =>
        api.PutSessionAsync(s.Id, new PutSessionRequest(s.ProjectId, crypto.EncryptMeta(ObjectTypes.SessionMeta, s.Id, new SessionMeta(s.Name))));

    // ---------------- Rename / folder / delete ----------------
    public async Task RenameAsync(LocalProject p, string name)
    {
        p.Name = name.Trim();
        db.UpsertProject(p);
        await UploadProjectAsync(p);
        state.RaiseProjectsListChanged();
    }

    public async Task ChangeFolderAsync(LocalProject p, string newFolder, IUserPrompts ui)
    {
        newFolder = Path.GetFullPath(newFolder);
        if (Scheduler.IsRunning(p.Id)) throw new ProjectException("Wait for the running job to finish.");
        if (!await git.IsRepoAsync(newFolder) || !await git.BranchExistsAsync(newFolder, p.DevBranch))
        {
            if (!ui.Confirm("Prepare folder", $"The new folder must be a git repository with the branch '{p.DevBranch}'. Set it up now?"))
                return;
            if (!await git.IsRepoAsync(newFolder)) await git.InitAsync(newFolder, Author, Email);
            if (await git.IsDirtyAsync(newFolder))
            {
                var choice = ui.Choose("Uncommitted changes", "Commit or stash the current changes?", "Commit as baseline", "Stash", "Cancel");
                if (choice == 0) await git.CommitBaselineAsync(newFolder, Author, Email);
                else if (choice == 1) await git.StashAsync(newFolder);
                else return;
            }
            await git.SwitchToAsync(newFolder, p.DevBranch);
        }
        // Claude sessions keep working: `claude --resume <id>` searches every project on the machine.
        p.FolderPath = newFolder;
        db.UpsertProject(p);
        state.RaiseProjectsListChanged();
    }

    /// <summary>Removes the project from Codenvi and the server. Never deletes the folder, branch or commits.</summary>
    public async Task DeleteAsync(LocalProject p)
    {
        if (Scheduler.IsRunning(p.Id)) throw new ProjectException("Wait for the running job to finish, or cancel it first.");
        try { await api.DeleteProjectAsync(p.Id); }
        catch (ApiException e) when (e.Status == System.Net.HttpStatusCode.NotFound) { }
        db.DeleteProject(p.Id);
        state.RaiseProjectsListChanged();
    }

    // ---------------- Sessions ----------------
    public async Task<LocalSession> CreateSessionAsync(Guid projectId, string name, Guid? id = null)
    {
        var s = NewLocalSession(projectId, id ?? Guid.NewGuid(), name.Trim());
        db.UpsertSession(s);
        await UploadSessionAsync(s);
        return s;
    }

    public async Task RenameSessionAsync(LocalSession s, string name)
    {
        s.Name = name.Trim();
        db.UpsertSession(s);
        await api.PatchSessionAsync(s.Id, new PatchSessionRequest(crypto.EncryptMeta(ObjectTypes.SessionMeta, s.Id, new SessionMeta(s.Name))));
    }

    /// <summary>
    /// Clears the server chat and starts a fresh Claude session UUID (docs/design.md §8.5). The old transcript is
    /// deleted only when the setting is on.
    /// </summary>
    public async Task ClearMemoryAsync(LocalSession s, bool clearServerMessages = true)
    {
        if (clearServerMessages)
        {
            try { await api.ClearMessagesAsync(s.Id); }
            catch (ApiException e) when (e.Status == System.Net.HttpStatusCode.NotFound) { }
        }
        var old = s.ClaudeSessionId;
        s.ClaudeSessionId = Guid.NewGuid();
        s.Started = false;
        s.RollbackNote = null;
        db.UpsertSession(s);
        if (state.Settings.DeleteTranscriptsOnClear) DeleteTranscript(old);
    }

    public async Task DeleteSessionAsync(LocalSession s, bool alsoOnServer = true)
    {
        if (alsoOnServer)
        {
            try { await api.DeleteSessionAsync(s.Id); }
            catch (ApiException e) when (e.Status == System.Net.HttpStatusCode.NotFound) { }
        }
        db.DeleteSession(s.Id);
        if (state.Settings.DeleteTranscriptsOnClear) DeleteTranscript(s.ClaudeSessionId);
    }

    /// <summary>
    /// Best effort (docs/design.md §8.7): the transcript layout is internal to Claude Code, so we search
    /// ~/.claude/projects/*/&lt;uuid&gt;.jsonl and otherwise leave it to Claude Code's own cleanup.
    /// </summary>
    public void DeleteTranscript(Guid claudeSessionId)
    {
        try
        {
            var root = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), ".claude", "projects");
            if (!Directory.Exists(root)) return;
            foreach (var file in Directory.EnumerateFiles(root, $"{claudeSessionId:D}.jsonl", SearchOption.AllDirectories))
                File.Delete(file);
        }
        catch (Exception e) { log.LogDebug("Transcript delete skipped: {Message}", e.Message); }
    }

    /// <summary>Creates the local mapping for a session the phone created.</summary>
    public async Task<LocalSession> EnsureSessionAsync(LocalProject project, Guid sessionId)
    {
        var existing = db.Session(sessionId);
        if (existing is not null) return existing;
        var name = "Session";
        try
        {
            var remote = (await api.SessionsAsync(project.Id)).FirstOrDefault(s => s.Id == sessionId);
            if (remote is not null && crypto.TryDecryptMeta<SessionMeta>(ObjectTypes.SessionMeta, sessionId, remote.EncMeta, out var meta) && meta is not null)
                name = meta.Name;
        }
        catch (Exception e) { log.LogDebug("Session meta fetch failed: {Message}", e.Message); }
        var s = NewLocalSession(project.Id, sessionId, name);
        db.UpsertSession(s);
        return s;
    }

    // ---------------- Server → PC sync (renames and sessions made on the phone) ----------------
    public async Task SyncFromServerAsync()
    {
        if (crypto.KeyVersion == 0) return;
        var remoteProjects = await api.ProjectsAsync(crypto.DeviceId);
        var locals = db.Projects();
        foreach (var local in locals)
        {
            var remote = remoteProjects.FirstOrDefault(r => r.Id == local.Id);
            if (remote is null)
            {
                // Created while offline, or server lost it: upload again.
                try { await UploadProjectAsync(local, db.Sessions(local.Id).FirstOrDefault()?.Id); } catch (Exception e) { log.LogDebug("{M}", e.Message); }
                foreach (var s in db.Sessions(local.Id)) try { await UploadSessionAsync(s); } catch { }
                continue;
            }
            if (crypto.TryDecryptMeta<ProjectMeta>(ObjectTypes.ProjectMeta, local.Id, remote.EncMeta, out var meta) && meta is not null &&
                meta.Name != local.Name)
            {
                local.Name = meta.Name;
                db.UpsertProject(local);
            }
            await SyncSessionsAsync(local);
        }
        state.RaiseProjectsListChanged();
    }

    public async Task SyncSessionsAsync(LocalProject project)
    {
        var remote = await api.SessionsAsync(project.Id);
        var local = db.Sessions(project.Id);
        foreach (var r in remote)
        {
            if (!crypto.TryDecryptMeta<SessionMeta>(ObjectTypes.SessionMeta, r.Id, r.EncMeta, out var meta) || meta is null) continue;
            var l = local.FirstOrDefault(x => x.Id == r.Id);
            if (l is null) db.UpsertSession(NewLocalSession(project.Id, r.Id, meta.Name));
            else if (l.Name != meta.Name) { l.Name = meta.Name; db.UpsertSession(l); }
        }
        foreach (var l in local.Where(l => remote.All(r => r.Id != l.Id)))
        {
            // Deleted on the phone.
            db.DeleteSession(l.Id);
            if (state.Settings.DeleteTranscriptsOnClear) DeleteTranscript(l.ClaudeSessionId);
        }
    }

    // ---------------- Commits & rollback ----------------
    /// <summary>
    /// Records commits Claude made itself during the job. Codenvi never commits automatically: leftover changes stay in the
    /// working tree until the user taps the Commit button in the Android app (see <see cref="CommitLeftoversAsync"/>).
    /// </summary>
    public async Task<(string? hash, int filesChanged)> RecordTaskCommitsAsync(LocalProject project, Guid jobId, string? preHash)
    {
        var own = await git.RevListAsync(project.FolderPath, preHash);
        foreach (var h in own) await RecordCommitAsync(project, jobId, h, "task");
        var files = own.Count > 0 ? db.CommitByHash(project.Id, own[^1])?.FilesChanged ?? 0 : 0;
        return (own.LastOrDefault(), files);
    }

    /// <summary>Commits whatever is left in the working tree after a cancelled/timed-out/failed job (docs/design.md §9.2).</summary>
    public async Task<string?> CommitLeftoversAsync(LocalProject project, string body = "Committed from the Codenvi Activity tab.")
    {
        var hash = await git.CommitTaskAsync(project.FolderPath, "Codenvi: keep changes from an unfinished job", body, Author, Email);
        if (hash is not null) await RecordCommitAsync(project, null, hash, "task");
        state.UpdateRuntime(project.Id, r => r.HasUncommittedChanges = false);
        return hash;
    }

    /// <summary>Publish (docs/design.md §9.4): merges the dev branch into "main" and pushes "main" to origin.</summary>
    public async Task<string> PublishAsync(Guid projectId)
    {
        var project = db.Project(projectId) ?? throw new ProjectException("Unknown project.");
        if (Scheduler.IsRunning(projectId)) throw new ProjectException("A job is running in this project. Wait for it or cancel it first.");
        if (project.DevBranch == MainBranch) throw new ProjectException($"The dev branch is already '{MainBranch}'.");
        var hash = await git.PublishAsync(project.FolderPath, project.DevBranch, MainBranch, Author, Email);
        state.RaiseProjectsListChanged();
        return hash;
    }

    public async Task<LocalCommit> RecordCommitAsync(LocalProject project, Guid? jobId, string hash, string kind)
    {
        var existing = db.CommitByHash(project.Id, hash);
        if (existing is not null) return existing;
        var info = await git.CommitInfoAsync(project.FolderPath, hash);
        var c = new LocalCommit
        {
            Id = Guid.NewGuid(), ProjectId = project.Id, JobId = jobId, GitHash = info.Hash, Kind = kind, Message = info.Subject,
            FilesChanged = info.FilesChanged, Insertions = info.Insertions, Deletions = info.Deletions, CreatedAt = info.When.UtcDateTime,
        };
        db.UpsertCommit(c);
        await UploadCommitAsync(c);
        return c;
    }

    private async Task UploadCommitAsync(LocalCommit c)
    {
        try
        {
            await api.PutCommitAsync(c.ProjectId, c.Id, new PutCommitRequest(c.JobId, c.GitHash, c.Kind,
                crypto.EncryptMeta(ObjectTypes.CommitMeta, c.Id, new CommitMeta(c.Message, c.FilesChanged, c.Insertions, c.Deletions)),
                c.RevertedBy));
        }
        catch (Exception e) { log.LogWarning("Commit upload failed: {Message}", e.Message); }
    }

    /// <summary>Rollback by revert, history kept (docs/design.md §9.3).</summary>
    public async Task<LocalCommit> RollbackAsync(Guid projectId, string targetHash)
    {
        var project = db.Project(projectId) ?? throw new ProjectException("Unknown project.");
        if (Scheduler.IsRunning(projectId)) throw new ProjectException("A job is running in this project. Wait for it or cancel it first.");
        var (hash, reverted) = await git.RollbackAsync(project.FolderPath, targetHash, Author, Email);
        var rollback = await RecordCommitAsync(project, null, hash, "rollback");
        foreach (var h in reverted)
        {
            var c = db.CommitByHash(projectId, h);
            if (c is null) continue;
            c.RevertedBy = rollback.Id;
            db.UpsertCommit(c);
            await UploadCommitAsync(c);
        }
        var shortHash = targetHash.Length >= 7 ? targetHash[..7] : targetHash;
        db.SetRollbackNote(projectId,
            $"Note: the code was rolled back to commit {shortHash}; earlier changes discussed above have been reverted.");
        state.RaiseProjectsListChanged();
        return rollback;
    }

    public async Task<ProjectStatusPlain> StatusAsync(Guid projectId)
    {
        var p = db.Project(projectId) ?? throw new ProjectException("Unknown project.");
        var branch = await git.CurrentBranchAsync(p.FolderPath);
        var dirty = await git.IsDirtyAsync(p.FolderPath);
        var head = await git.HeadAsync(p.FolderPath);
        var info = head is null ? null : await git.CommitInfoAsync(p.FolderPath, head);
        var ahead = await git.CountAheadAsync(p.FolderPath, p.DevBranch, MainBranch);
        await git.FetchOriginAsync(p.FolderPath, MainBranch);
        var behind = await git.CountBehindOriginAsync(p.FolderPath, p.DevBranch, MainBranch);
        return new ProjectStatusPlain(branch, dirty, head, info?.Subject, Scheduler.RunningJob(projectId), branch == p.DevBranch, ahead, behind);
    }

    /// <summary>Pull: fetches origin/main and merges it into the dev branch.</summary>
    public async Task<string> PullAsync(Guid projectId)
    {
        var project = db.Project(projectId) ?? throw new ProjectException("Unknown project.");
        if (Scheduler.IsRunning(projectId)) throw new ProjectException("A job is running in this project. Wait for it or cancel it first.");
        var hash = await git.PullAsync(project.FolderPath, project.DevBranch, MainBranch, Author, Email);
        state.RaiseProjectsListChanged();
        return hash;
    }

    public static string ClaudeMdPath(LocalProject p) => Path.Combine(p.FolderPath, "CLAUDE.md");

    public string ReadMemory(LocalProject p) => File.Exists(ClaudeMdPath(p)) ? File.ReadAllText(ClaudeMdPath(p)) : "";

    public void WriteMemory(LocalProject p, string text) => File.WriteAllText(ClaudeMdPath(p), text);

    /// <summary>Runs a tiny `claude -p` in the folder to detect a workspace-trust problem (V3 in docs/design.md §14).</summary>
    public async Task<string> TestClaudeAsync(LocalProject p, ClaudeCli claude)
    {
        var status = await claude.CheckAsync();
        if (status.State != ClaudeState.Ready) return status.Banner;
        var r = await ProcessRunner.RunAsync(status.Path!, ["-p", "--max-turns", "1", "--permission-prompts", "none"],
            p.FolderPath, "Reply with the single word OK.", timeout: TimeSpan.FromMinutes(2));
        var output = (r.StdOut + "\n" + r.StdErr).Trim();
        if (r.ExitCode == 0) return "Claude Code runs in this folder. Reply: " + JobScheduler.Preview(r.StdOut, 60);
        if (output.Contains("trust", StringComparison.OrdinalIgnoreCase))
            return "Claude Code needs you to trust this folder. Open a terminal here, run `claude`, accept the prompt, then try again.";
        return $"Claude Code exited with code {r.ExitCode}: {JobScheduler.Tail(output, 5)}";
    }
}
