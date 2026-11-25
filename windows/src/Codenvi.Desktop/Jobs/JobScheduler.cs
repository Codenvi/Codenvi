using System.Security.Cryptography;
using System.Text;
using System.Threading.Channels;
using Codenvi.Contracts;
using Codenvi.Contracts.Crypto;
using Codenvi.Contracts.Protocol;
using Codenvi.Desktop.Claude;
using Codenvi.Desktop.Core;
using Codenvi.Desktop.Git;
using Codenvi.Desktop.Net;
using Codenvi.Desktop.Services;
using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging;

namespace Codenvi.Desktop.Jobs;

/// <summary>
/// Job execution engine (docs/design.md §8.6): one FIFO queue per project (one job per project at a time),
/// projects in parallel up to "max parallel".
/// </summary>
public sealed class JobScheduler(
    LocalDb db, CryptoService crypto, WsClient ws, ClaudeCli claude, GitService git, ProjectService projects,
    AppState state, ILogger<JobScheduler> log) : BackgroundService
{
    private const int ResultChunkChars = 250_000; // keeps each ciphertext well under the 1 MB cap
    private static readonly TimeSpan ProgressInterval = TimeSpan.FromMilliseconds(500); // ≤ 2 lines/s

    private readonly object _lock = new();
    private readonly Dictionary<Guid, LinkedList<JobDispatchPayload>> _queues = new();
    private readonly Dictionary<Guid, (Guid jobId, CancellationTokenSource cts)> _running = new();
    private readonly List<JobDispatchPayload> _waitingLogin = new();
    private readonly HashSet<Guid> _known = new();
    private readonly Channel<bool> _wake = Channel.CreateUnbounded<bool>();

    public bool IsRunning(Guid projectId) { lock (_lock) return _running.ContainsKey(projectId); }
    public Guid? RunningJob(Guid projectId) { lock (_lock) return _running.TryGetValue(projectId, out var r) ? r.jobId : null; }

    // ---------------- Intake ----------------
    public async Task EnqueueAsync(JobDispatchPayload payload)
    {
        var job = payload.Job;
        lock (_lock)
        {
            if (!_known.Add(job.Id)) return; // already queued or running here
        }

        // Already finished here (e.g. the status message was lost): report the final status again.
        var local = db.Job(job.Id);
        if (local is not null && JobStatuses.IsFinal(local.Status))
        {
            lock (_lock) _known.Remove(job.Id);
            await ws.SendReliableAsync(MessageTypes.JobStatus, new JobStatusPayload(job.Id, local.Status, ErrorCode: local.ErrorCode));
            return;
        }

        await ws.SendAsync(MessageTypes.JobAck, new JobAckPayload(job.Id));
        lock (_lock)
        {
            if (!_queues.TryGetValue(job.ProjectId, out var q)) _queues[job.ProjectId] = q = new LinkedList<JobDispatchPayload>();
            q.AddLast(payload);
        }
        db.UpsertJob(new LocalJob
        {
            Id = job.Id, ProjectId = job.ProjectId, SessionId = job.SessionId, PromptMessageId = job.PromptMessageId,
            Status = JobStatuses.Delivered, ReceivedAt = DateTime.UtcNow,
        });
        RefreshQueueCount(job.ProjectId);
        Wake();
    }

    public async Task CancelAsync(Guid jobId)
    {
        JobDispatchPayload? removed = null;
        CancellationTokenSource? running = null;
        lock (_lock)
        {
            foreach (var q in _queues.Values)
            {
                var node = q.First;
                while (node is not null)
                {
                    if (node.Value.Job.Id == jobId) { removed = node.Value; q.Remove(node); break; }
                    node = node.Next;
                }
                if (removed is not null) break;
            }
            removed ??= _waitingLogin.FirstOrDefault(w => w.Job.Id == jobId);
            if (removed is not null) { _waitingLogin.Remove(removed); _known.Remove(jobId); }
            running = _running.Values.FirstOrDefault(r => r.jobId == jobId).cts;
        }
        if (removed is not null)
        {
            await FinishAsync(removed.Job, JobStatuses.Cancelled, null, null);
            RefreshQueueCount(removed.Job.ProjectId);
        }
        running?.Cancel();
    }

    /// <summary>Re-checks Claude Code and releases jobs held as "Waiting for Claude sign-in".</summary>
    public async Task RetryWaitingAsync()
    {
        var status = await claude.CheckAsync();
        state.SetClaude(status);
        if (status.State != ClaudeState.Ready) return;
        List<JobDispatchPayload> release;
        lock (_lock)
        {
            release = _waitingLogin.ToList();
            _waitingLogin.Clear();
            foreach (var p in release)
            {
                if (!_queues.TryGetValue(p.Job.ProjectId, out var q)) _queues[p.Job.ProjectId] = q = new LinkedList<JobDispatchPayload>();
                q.AddFirst(p);
            }
        }
        foreach (var p in release)
        {
            state.UpdateRuntime(p.Job.ProjectId, r => r.WaitingLogin = false);
            RefreshQueueCount(p.Job.ProjectId);
        }
        Wake();
    }

    public void Wake() => _wake.Writer.TryWrite(true);

    private void RefreshQueueCount(Guid projectId)
    {
        int n;
        lock (_lock) n = _queues.TryGetValue(projectId, out var q) ? q.Count : 0;
        state.UpdateRuntime(projectId, r => r.QueuedCount = n);
    }

    // ---------------- Scheduling loop ----------------
    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        _ = Task.Run(async () =>
        {
            // Periodically re-check sign-in while jobs are waiting for it.
            while (!stoppingToken.IsCancellationRequested)
            {
                try
                {
                    await Task.Delay(TimeSpan.FromSeconds(60), stoppingToken);
                    bool any;
                    lock (_lock) any = _waitingLogin.Count > 0;
                    if (any) await RetryWaitingAsync();
                }
                catch (OperationCanceledException) { return; }
                catch (Exception e) { log.LogWarning(e, "Sign-in re-check failed"); }
            }
        }, stoppingToken);

        while (await _wake.Reader.WaitToReadAsync(stoppingToken))
        {
            while (_wake.Reader.TryRead(out _)) { }
            StartEligible(stoppingToken);
        }
    }

    private void StartEligible(CancellationToken stoppingToken)
    {
        var settings = state.Settings;
        if (settings.PauseAll) return;
        lock (_lock)
        {
            foreach (var (projectId, queue) in _queues)
            {
                if (_running.Count >= Math.Max(1, settings.MaxParallelProjects)) return;
                if (queue.Count == 0 || _running.ContainsKey(projectId)) continue;
                var project = db.Project(projectId);
                if (project?.Paused == true) continue;

                var payload = queue.First!.Value;
                queue.RemoveFirst();
                var cts = CancellationTokenSource.CreateLinkedTokenSource(stoppingToken);
                _running[projectId] = (payload.Job.Id, cts);
                _ = Task.Run(async () =>
                {
                    try { await RunAsync(payload, cts.Token); }
                    catch (Exception e) { log.LogError(e, "Job {JobId} crashed", payload.Job.Id); }
                    finally
                    {
                        lock (_lock)
                        {
                            _running.Remove(projectId);
                            if (!_waitingLogin.Any(w => w.Job.Id == payload.Job.Id)) _known.Remove(payload.Job.Id);
                        }
                        cts.Dispose();
                        state.UpdateRuntime(projectId, r => { r.RunningJobId = null; r.RunningSince = null; });
                        RefreshQueueCount(projectId);
                        Wake();
                    }
                });
            }
        }
    }

    // ---------------- One job ----------------
    private async Task RunAsync(JobDispatchPayload payload, CancellationToken ct)
    {
        var job = payload.Job;
        var project = db.Project(job.ProjectId);
        if (project is null) { await FailAsync(job, ErrorCodes.ProjectMissing, "This project is not registered on the PC.", null); return; }

        // 1. Decrypt and verify (docs/design.md §5). A bad prompt is never run.
        PromptPlain prompt;
        try
        {
            if (payload.Prompt.MessageId != job.PromptMessageId) throw new CryptographicException("Prompt id mismatch.");
            prompt = crypto.Decrypt<PromptPlain>(ObjectTypes.Message, payload.Prompt.MessageId, Directions.PhoneToDesktop,
                payload.Prompt.ClientCreatedAtMs, payload.Prompt.Ciphertext);
            if (prompt.ProjectId != job.ProjectId || prompt.SessionId != job.SessionId ||
                Norm(prompt.Model) != Norm(job.Model) || Norm(prompt.Effort) != Norm(job.Effort))
                throw new CryptographicException("Prompt context does not match the job.");
        }
        catch (Exception e) when (e is CryptographicException or System.Text.Json.JsonException or InvalidOperationException)
        {
            log.LogWarning("Rejected job {JobId}: {Reason}", job.Id, e.Message);
            await FailAsync(job, ErrorCodes.BadCiphertext, "The prompt could not be verified with this PC's key.", null);
            return;
        }

        // 2. Replay and staleness (docs/design.md §5.4).
        if (db.WasSeen(job.Id) || db.WasSeen(job.PromptMessageId))
        {
            await FailAsync(job, ErrorCodes.Replay, "This prompt was already executed.", null);
            return;
        }
        var age = DateTimeOffset.UtcNow - DateTimeOffset.FromUnixTimeMilliseconds(payload.Prompt.ClientCreatedAtMs);
        if (age > TimeSpan.FromHours(Math.Max(1, state.Settings.StaleJobHours)))
        {
            db.MarkSeen(job.Id, "job");
            db.MarkSeen(job.PromptMessageId, "prompt");
            await FinishAsync(job, JobStatuses.RejectedStale, ErrorCodes.Stale, null);
            return;
        }

        // 3. Claude Code present and signed in (docs/design.md §4.4).
        var status = await claude.CheckAsync(ct);
        state.SetClaude(status);
        if (status.State == ClaudeState.NotSignedIn)
        {
            lock (_lock) { _waitingLogin.Add(payload); _known.Add(job.Id); }
            state.UpdateRuntime(job.ProjectId, r => r.WaitingLogin = true);
            await ws.SendReliableAsync(MessageTypes.JobStatus, new JobStatusPayload(job.Id, JobStatuses.WaitingLogin));
            Native.NativeMethods.FlashMainWindow();
            return;
        }
        if (status.State == ClaudeState.NotFound) { await FailAsync(job, ErrorCodes.ClaudeNotFound, status.Banner, null); return; }
        if (status.State == ClaudeState.TooOld) { await FailAsync(job, ErrorCodes.ClaudeTooOld, status.Banner, null); return; }

        // 4. Dev branch checked out.
        string? preHash;
        try
        {
            if (!Directory.Exists(project.FolderPath)) { await FailAsync(job, ErrorCodes.ProjectMissing, $"Folder not found: {project.FolderPath}", null); return; }
            var branch = await git.CurrentBranchAsync(project.FolderPath);
            if (branch != project.DevBranch)
            {
                if (state.Settings.AutoSwitchBranch && !await git.IsDirtyAsync(project.FolderPath))
                    await git.SwitchToAsync(project.FolderPath, project.DevBranch);
                else
                {
                    await FailAsync(job, ErrorCodes.WrongBranch, $"The PC is on branch '{branch}', not '{project.DevBranch}'.", null);
                    return;
                }
            }
            preHash = await git.HeadAsync(project.FolderPath);
        }
        catch (GitException e) { await FailAsync(job, ErrorCodes.GitFailed, e.Message, null); return; }

        // 5. From here on the job counts as executed.
        db.MarkSeen(job.Id, "job");
        db.MarkSeen(job.PromptMessageId, "prompt");

        var session = db.Session(job.SessionId) ?? await projects.EnsureSessionAsync(project, job.SessionId);
        var startedAt = DateTime.UtcNow;
        var local = db.Job(job.Id) ?? new LocalJob { Id = job.Id, ProjectId = job.ProjectId, SessionId = job.SessionId, PromptMessageId = job.PromptMessageId, ReceivedAt = DateTime.UtcNow };
        local.Status = JobStatuses.Running;
        local.StartedAt = startedAt;
        local.PromptPreview = Preview(prompt.Text);
        db.UpsertJob(local);
        state.UpdateRuntime(job.ProjectId, r => { r.RunningJobId = job.Id; r.RunningSince = startedAt; r.LastError = null; r.WaitingLogin = false; });
        await ws.SendReliableAsync(MessageTypes.JobStatus, new JobStatusPayload(job.Id, JobStatuses.Running, StartedAtMs: Time.NowMs()));
        state.AddActivity(job.ProjectId, $"▶ {Preview(prompt.Text)}");

        // 6. Run claude -p.
        var text = session.RollbackNote is { Length: > 0 } note ? $"{note}\n\n{prompt.Text}" : prompt.Text;
        // "default" from the phone falls back to the project's default model/effort (Settings tab).
        var args = ClaudeCli.BuildArguments(!session.Started, session.ClaudeSessionId, session.Name,
            job.Model ?? project.DefaultModel, job.Effort ?? project.DefaultEffort,
            project.PermissionMode, project.AllowedTools, project.MaxTurns);

        ResultEvent? result = null;
        string? resolvedModel = null;
        var lastProgress = DateTime.MinValue;
        var sessionMarked = false;
        void OnLine(string line)
        {
            foreach (var ev in StreamJsonParser.Parse(line, project.FolderPath))
            {
                switch (ev)
                {
                    case InitEvent init when !sessionMarked:
                        sessionMarked = true;
                        session.Started = true;
                        resolvedModel = init.Model;
                        db.UpsertSession(session);
                        break;
                    case ToolUseEvent tool:
                        state.AddActivity(job.ProjectId, tool.ActivityLine);
                        if (DateTime.UtcNow - lastProgress >= ProgressInterval)
                        {
                            lastProgress = DateTime.UtcNow;
                            _ = SendProgressAsync(job, tool.ActivityLine);
                        }
                        break;
                    case ResultEvent r:
                        result = r;
                        break;
                }
            }
        }

        ProcessResult run;
        try
        {
            run = await ProcessRunner.RunAsync(status.Path!, args, project.FolderPath, text, OnLine,
                TimeSpan.FromMinutes(Math.Max(1, project.TimeoutMinutes)), ct);
        }
        catch (Exception e) when (e is System.ComponentModel.Win32Exception or IOException)
        {
            await FailAsync(job, ErrorCodes.ClaudeFailed, "Could not start Claude Code: " + e.Message, null);
            return;
        }
        WriteJobLog(job.Id, run);
        if (result?.SessionId is not null && !session.Started) { session.Started = true; db.UpsertSession(session); }

        if (run.Cancelled || run.TimedOut)
        {
            var dirty = await SafeDirty(project.FolderPath);
            local.HasUncommittedChanges = dirty;
            db.UpsertJob(local);
            state.UpdateRuntime(job.ProjectId, r => r.HasUncommittedChanges = dirty);
            if (run.TimedOut)
                await FailAsync(job, ErrorCodes.Timeout, $"Timed out after {project.TimeoutMinutes} minutes.", dirty ? "Changes were left uncommitted on the PC." : null);
            else
                await FinishAsync(job, JobStatuses.Cancelled, null, null);
            state.AddActivity(job.ProjectId, run.TimedOut ? "⏱ Timed out" : "■ Cancelled");
            return;
        }

        if (run.ExitCode != 0 || result is null || result.IsError)
        {
            var dirty = await SafeDirty(project.FolderPath);
            local.HasUncommittedChanges = dirty;
            db.UpsertJob(local);
            state.UpdateRuntime(job.ProjectId, r => r.HasUncommittedChanges = dirty);
            var summary = result?.Text is { Length: > 0 } t ? Preview(t, 300) : $"Claude Code exited with code {run.ExitCode}.";
            await FailAsync(job, ErrorCodes.ClaudeFailed, summary, Tail(run.StdErr, 30));
            return;
        }

        // 7. No automatic commit: changes stay in the working tree until the user taps Commit in the Android app.
        // Only commits Claude made itself are recorded.
        if (session.RollbackNote is not null) { session.RollbackNote = null; db.UpsertSession(session); }
        string? commitHash;
        int filesChanged = 0;
        try
        {
            (commitHash, filesChanged) = await projects.RecordTaskCommitsAsync(project, job.Id, preHash);
        }
        catch (GitException e)
        {
            await FailAsync(job, ErrorCodes.GitFailed, "Claude finished, but reading git state failed: " + e.Message, null);
            return;
        }
        var leftover = await SafeDirty(project.FolderPath);
        local.HasUncommittedChanges = leftover;
        state.UpdateRuntime(job.ProjectId, r => r.HasUncommittedChanges = leftover);

        // 8. Reply.
        var chunks = Chunk(result.Text.Length == 0 ? "(no text reply)" : result.Text, ResultChunkChars);
        for (var i = 0; i < chunks.Count; i++)
        {
            var last = i == chunks.Count - 1;
            var messageId = Guid.NewGuid();
            var createdAt = Time.NowMs();
            var plain = new ResultPlain(chunks[i], last ? result.CostUsd : null, last ? result.DurationMs : null,
                last ? commitHash : null, last ? filesChanged : null, i + 1, chunks.Count);
            var ciphertext = crypto.Encrypt(ObjectTypes.Message, messageId, Directions.DesktopToPhone, createdAt, plain);
            await ws.SendReliableAsync(MessageTypes.JobResult,
                new JobResultPayload(job.Id, job.SessionId, messageId, ciphertext, createdAt, last ? commitHash : null, last));
        }

        local.CostUsd = result.CostUsd;
        local.CommitHash = commitHash;
        db.UpsertJob(local);
        await FinishAsync(job, JobStatuses.Succeeded, null, null);
        state.AddActivity(job.ProjectId, commitHash is null ? (leftover ? "✓ Done (changes left uncommitted)" : "✓ Done (no file changes)") : $"✓ Done - commit {commitHash[..7]}" +
            (result.CostUsd is { } cost ? $" · ${cost:0.####}" : ""));
    }

    // ---------------- Helpers ----------------
    private async Task SendProgressAsync(DispatchedJob job, string line)
    {
        try
        {
            var id = Guid.NewGuid();
            var at = Time.NowMs();
            var ct = crypto.Encrypt(ObjectTypes.Progress, id, Directions.DesktopToPhone, at, new ProgressPlain(line));
            await ws.SendAsync(MessageTypes.JobProgress, new JobProgressPayload(job.Id, job.SessionId, id, ct, at));
        }
        catch (Exception e) { log.LogDebug("Progress send failed: {Message}", e.Message); }
    }

    private async Task FailAsync(DispatchedJob job, string code, string summary, string? detail)
    {
        log.LogWarning("Job {JobId} failed: {Code} {Summary}", job.Id, code, summary);
        state.UpdateRuntime(job.ProjectId, r => r.LastError = summary);
        state.AddActivity(job.ProjectId, "✗ " + summary);
        byte[]? enc = null;
        try { enc = crypto.Encrypt(ObjectTypes.JobError, job.Id, Directions.DesktopToPhone, 0, new ErrorPlain(summary, detail)); }
        catch (Exception e) { log.LogWarning(e, "Could not encrypt error"); }
        await FinishAsync(job, JobStatuses.Failed, code, enc);
        Native.NativeMethods.FlashMainWindow();
    }

    private async Task FinishAsync(DispatchedJob job, string status, string? code, byte[]? encError)
    {
        var local = db.Job(job.Id) ?? new LocalJob { Id = job.Id, ProjectId = job.ProjectId, SessionId = job.SessionId, PromptMessageId = job.PromptMessageId, ReceivedAt = DateTime.UtcNow };
        local.Status = status;
        local.ErrorCode = code;
        local.FinishedAt = DateTime.UtcNow;
        db.UpsertJob(local);
        await ws.SendReliableAsync(MessageTypes.JobStatus,
            new JobStatusPayload(job.Id, status, FinishedAtMs: Time.NowMs(), ErrorCode: code, EncError: encError));
        state.RaiseProjectsListChanged();
    }

    private async Task<bool> SafeDirty(string folder)
    {
        try { return await git.IsDirtyAsync(folder); } catch { return false; }
    }

    private void WriteJobLog(Guid jobId, ProcessResult run)
    {
        try
        {
            File.WriteAllText(Path.Combine(AppPaths.JobLogs, $"{DateTime.UtcNow:yyyyMMdd-HHmmss}-{jobId:N}.log"),
                $"exit={run.ExitCode} cancelled={run.Cancelled} timedOut={run.TimedOut}\n--- stderr ---\n{run.StdErr}");
            foreach (var f in new DirectoryInfo(AppPaths.JobLogs).GetFiles("*.log").Where(f => f.CreationTimeUtc < DateTime.UtcNow.AddDays(-7)))
                f.Delete();
        }
        catch (Exception e) { log.LogDebug("Job log write failed: {Message}", e.Message); }
    }

    private static string? Norm(string? s) => string.IsNullOrWhiteSpace(s) || s == "default" ? null : s;

    public static string Preview(string text, int max = 80)
    {
        var t = text.ReplaceLineEndings(" ").Trim();
        return t.Length <= max ? t : t[..(max - 1)] + "…";
    }

    public static string Tail(string text, int lines) =>
        string.Join('\n', text.Split('\n').Select(l => l.TrimEnd('\r')).Where(l => l.Length > 0).TakeLast(lines));

    /// <summary>Splits long replies into parts (docs/design.md §7.6), preferring line breaks.</summary>
    public static List<string> Chunk(string text, int size)
    {
        var parts = new List<string>();
        var i = 0;
        while (i < text.Length)
        {
            var len = Math.Min(size, text.Length - i);
            if (i + len < text.Length)
            {
                var nl = text.LastIndexOf('\n', i + len - 1, len);
                if (nl > i + size / 2) len = nl - i + 1;
                if (char.IsHighSurrogate(text[i + len - 1])) len--;
            }
            parts.Add(text.Substring(i, len));
            i += len;
        }
        if (parts.Count == 0) parts.Add("");
        return parts;
    }
}
