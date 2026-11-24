using System.Text.Json;
using Codenvi.Contracts;
using Dapper;
using Microsoft.Data.Sqlite;

namespace Codenvi.Desktop.Core;

/// <summary>
/// SQLite store in %LOCALAPPDATA%\Codenvi\codenvi.db (docs/design.md §8.3): projects, sessions, jobs,
/// commits, seen_jobs (replay protection) and settings.
/// </summary>
public sealed class LocalDb
{
    private readonly string _connectionString;
    public const int SeenLimit = 10_000;

    static LocalDb()
    {
        DefaultTypeMap.MatchNamesWithUnderscores = true;
        SqlMapper.AddTypeHandler(new GuidHandler());
        SqlMapper.RemoveTypeMap(typeof(Guid));
        SqlMapper.RemoveTypeMap(typeof(Guid?));
    }

    public LocalDb() : this(AppPaths.Database) { }

    public LocalDb(string path)
    {
        _connectionString = new SqliteConnectionStringBuilder { DataSource = path, Cache = SqliteCacheMode.Shared }.ToString();
        Migrate();
    }

    private SqliteConnection Open()
    {
        var c = new SqliteConnection(_connectionString);
        c.Open();
        c.Execute("PRAGMA foreign_keys = ON; PRAGMA journal_mode = WAL; PRAGMA busy_timeout = 5000;");
        return c;
    }

    private void Migrate()
    {
        using var c = Open();
        c.Execute("""
            CREATE TABLE IF NOT EXISTS projects (
              id TEXT PRIMARY KEY, name TEXT NOT NULL, folder_path TEXT NOT NULL, dev_branch TEXT NOT NULL,
              permission_mode TEXT NOT NULL, allowed_tools TEXT NOT NULL, max_turns INTEGER NOT NULL,
              timeout_minutes INTEGER NOT NULL DEFAULT 60, default_model TEXT, default_effort TEXT,
              sort_order INTEGER NOT NULL DEFAULT 0, paused INTEGER NOT NULL DEFAULT 0);
            CREATE TABLE IF NOT EXISTS sessions (
              id TEXT PRIMARY KEY, project_id TEXT NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
              name TEXT NOT NULL, claude_session_id TEXT NOT NULL, started INTEGER NOT NULL DEFAULT 0,
              rollback_note TEXT, created_at TEXT NOT NULL);
            CREATE TABLE IF NOT EXISTS jobs (
              id TEXT PRIMARY KEY, project_id TEXT NOT NULL, session_id TEXT NOT NULL, prompt_message_id TEXT NOT NULL,
              status TEXT NOT NULL, error_code TEXT, prompt_preview TEXT, received_at TEXT NOT NULL,
              started_at TEXT, finished_at TEXT, cost_usd REAL, commit_hash TEXT,
              has_uncommitted_changes INTEGER NOT NULL DEFAULT 0);
            CREATE INDEX IF NOT EXISTS jobs_project ON jobs (project_id, received_at);
            CREATE TABLE IF NOT EXISTS commits (
              id TEXT PRIMARY KEY, project_id TEXT NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
              job_id TEXT, git_hash TEXT NOT NULL, kind TEXT NOT NULL, message TEXT NOT NULL,
              files_changed INTEGER NOT NULL DEFAULT 0, insertions INTEGER NOT NULL DEFAULT 0,
              deletions INTEGER NOT NULL DEFAULT 0, reverted_by TEXT, created_at TEXT NOT NULL,
              UNIQUE (project_id, git_hash));
            CREATE TABLE IF NOT EXISTS seen_jobs (id TEXT PRIMARY KEY, kind TEXT NOT NULL, seen_at TEXT NOT NULL);
            CREATE INDEX IF NOT EXISTS seen_jobs_time ON seen_jobs (seen_at);
            CREATE TABLE IF NOT EXISTS settings (key TEXT PRIMARY KEY, value TEXT NOT NULL);
            """);
    }

    // ---------------- Settings ----------------
    public AppSettings LoadSettings()
    {
        using var c = Open();
        var json = c.QuerySingleOrDefault<string>("SELECT value FROM settings WHERE key = 'app'");
        return json is null ? new AppSettings() : JsonSerializer.Deserialize<AppSettings>(json, CodenviJson.Options) ?? new AppSettings();
    }

    public void SaveSettings(AppSettings s)
    {
        using var c = Open();
        c.Execute("INSERT INTO settings (key, value) VALUES ('app', @v) ON CONFLICT(key) DO UPDATE SET value = excluded.value",
            new { v = JsonSerializer.Serialize(s, CodenviJson.Options) });
    }

    // ---------------- Projects ----------------
    public List<LocalProject> Projects()
    {
        using var c = Open();
        return c.Query<LocalProject>("SELECT * FROM projects ORDER BY sort_order, name").ToList();
    }

    public LocalProject? Project(Guid id)
    {
        using var c = Open();
        return c.QuerySingleOrDefault<LocalProject>("SELECT * FROM projects WHERE id = @id", new { id });
    }

    public void UpsertProject(LocalProject p)
    {
        using var c = Open();
        c.Execute("""
            INSERT INTO projects (id, name, folder_path, dev_branch, permission_mode, allowed_tools, max_turns, timeout_minutes,
                                  default_model, default_effort, sort_order, paused)
            VALUES (@Id, @Name, @FolderPath, @DevBranch, @PermissionMode, @AllowedTools, @MaxTurns, @TimeoutMinutes,
                    @DefaultModel, @DefaultEffort, @SortOrder, @Paused)
            ON CONFLICT(id) DO UPDATE SET name = excluded.name, folder_path = excluded.folder_path,
              dev_branch = excluded.dev_branch, permission_mode = excluded.permission_mode,
              allowed_tools = excluded.allowed_tools, max_turns = excluded.max_turns,
              timeout_minutes = excluded.timeout_minutes, default_model = excluded.default_model,
              default_effort = excluded.default_effort, sort_order = excluded.sort_order, paused = excluded.paused
            """, p);
    }

    public void DeleteProject(Guid id)
    {
        using var c = Open();
        c.Execute("DELETE FROM projects WHERE id = @id; DELETE FROM jobs WHERE project_id = @id;", new { id });
    }

    /// <summary>Forgets all registrations (used by "Disconnect this PC"). Folders on disk are untouched.</summary>
    public void ClearRegistrations()
    {
        using var c = Open();
        c.Execute("DELETE FROM sessions; DELETE FROM commits; DELETE FROM jobs; DELETE FROM projects;");
    }

    // ---------------- Sessions ----------------
    public List<LocalSession> Sessions(Guid projectId)
    {
        using var c = Open();
        return c.Query<LocalSession>("SELECT * FROM sessions WHERE project_id = @projectId ORDER BY created_at", new { projectId }).ToList();
    }

    public LocalSession? Session(Guid id)
    {
        using var c = Open();
        return c.QuerySingleOrDefault<LocalSession>("SELECT * FROM sessions WHERE id = @id", new { id });
    }

    public void UpsertSession(LocalSession s)
    {
        using var c = Open();
        c.Execute("""
            INSERT INTO sessions (id, project_id, name, claude_session_id, started, rollback_note, created_at)
            VALUES (@Id, @ProjectId, @Name, @ClaudeSessionId, @Started, @RollbackNote, @CreatedAt)
            ON CONFLICT(id) DO UPDATE SET name = excluded.name, claude_session_id = excluded.claude_session_id,
              started = excluded.started, rollback_note = excluded.rollback_note
            """, s);
    }

    public void DeleteSession(Guid id)
    {
        using var c = Open();
        c.Execute("DELETE FROM sessions WHERE id = @id", new { id });
    }

    public void SetRollbackNote(Guid projectId, string note)
    {
        using var c = Open();
        c.Execute("UPDATE sessions SET rollback_note = @note WHERE project_id = @projectId", new { projectId, note });
    }

    // ---------------- Jobs ----------------
    public LocalJob? Job(Guid id)
    {
        using var c = Open();
        return c.QuerySingleOrDefault<LocalJob>("SELECT * FROM jobs WHERE id = @id", new { id });
    }

    public List<LocalJob> RecentJobs(Guid projectId, int limit = 50)
    {
        using var c = Open();
        return c.Query<LocalJob>("SELECT * FROM jobs WHERE project_id = @projectId ORDER BY received_at DESC LIMIT @limit",
            new { projectId, limit }).ToList();
    }

    public void UpsertJob(LocalJob j)
    {
        using var c = Open();
        c.Execute("""
            INSERT INTO jobs (id, project_id, session_id, prompt_message_id, status, error_code, prompt_preview, received_at,
                              started_at, finished_at, cost_usd, commit_hash, has_uncommitted_changes)
            VALUES (@Id, @ProjectId, @SessionId, @PromptMessageId, @Status, @ErrorCode, @PromptPreview, @ReceivedAt,
                    @StartedAt, @FinishedAt, @CostUsd, @CommitHash, @HasUncommittedChanges)
            ON CONFLICT(id) DO UPDATE SET status = excluded.status, error_code = excluded.error_code,
              prompt_preview = coalesce(excluded.prompt_preview, jobs.prompt_preview),
              started_at = excluded.started_at, finished_at = excluded.finished_at, cost_usd = excluded.cost_usd,
              commit_hash = excluded.commit_hash, has_uncommitted_changes = excluded.has_uncommitted_changes
            """, j);
        c.Execute("DELETE FROM jobs WHERE id NOT IN (SELECT id FROM jobs ORDER BY received_at DESC LIMIT 5000)");
    }

    // ---------------- Replay protection (docs/design.md §5.4) ----------------
    public bool WasSeen(Guid id)
    {
        using var c = Open();
        return c.ExecuteScalar<long>("SELECT count(*) FROM seen_jobs WHERE id = @id", new { id }) > 0;
    }

    /// <summary>Atomically records an ID. Returns false if it was already present.</summary>
    public bool MarkSeen(Guid id, string kind)
    {
        using var c = Open();
        var inserted = c.Execute("INSERT OR IGNORE INTO seen_jobs (id, kind, seen_at) VALUES (@id, @kind, @now)",
            new { id, kind, now = DateTime.UtcNow.ToString("O") });
        c.Execute("DELETE FROM seen_jobs WHERE id NOT IN (SELECT id FROM seen_jobs ORDER BY seen_at DESC LIMIT @limit)",
            new { limit = SeenLimit * 2 });
        return inserted == 1;
    }

    // ---------------- Commits ----------------
    public List<LocalCommit> Commits(Guid projectId)
    {
        using var c = Open();
        return c.Query<LocalCommit>("SELECT * FROM commits WHERE project_id = @projectId ORDER BY created_at DESC", new { projectId }).ToList();
    }

    public LocalCommit? CommitByHash(Guid projectId, string hash)
    {
        using var c = Open();
        return c.QuerySingleOrDefault<LocalCommit>("SELECT * FROM commits WHERE project_id = @projectId AND git_hash = @hash",
            new { projectId, hash });
    }

    public void UpsertCommit(LocalCommit commit)
    {
        using var c = Open();
        c.Execute("""
            INSERT INTO commits (id, project_id, job_id, git_hash, kind, message, files_changed, insertions, deletions, reverted_by, created_at)
            VALUES (@Id, @ProjectId, @JobId, @GitHash, @Kind, @Message, @FilesChanged, @Insertions, @Deletions, @RevertedBy, @CreatedAt)
            ON CONFLICT(id) DO UPDATE SET reverted_by = excluded.reverted_by
            ON CONFLICT(project_id, git_hash) DO NOTHING
            """, commit);
    }

    private sealed class GuidHandler : SqlMapper.TypeHandler<Guid>
    {
        public override Guid Parse(object value) => Guid.Parse((string)value);
        public override void SetValue(System.Data.IDbDataParameter parameter, Guid value) => parameter.Value = value.ToString("D");
    }
}
