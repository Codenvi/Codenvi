using System.Text.Json;
using Codenvi.Contracts;
using Codenvi.Desktop.Core;
using Codenvi.Desktop.Services;
using Microsoft.Data.Sqlite;
using Microsoft.Extensions.Logging.Abstractions;
using Xunit;

namespace Codenvi.Desktop.Tests;

public class ScriptStorageTests
{
    private static string TempDb() => Path.Combine(Path.GetTempPath(), $"codenvi-test-{Guid.NewGuid():N}.db");

    [Fact]
    public void ShellScript_roundtrips()
    {
        var db = new LocalDb(TempDb());
        var p = new LocalProject { Id = Guid.NewGuid(), Name = "Site", FolderPath = @"C:\src\site", ShellScript = "cd web\nnpm run dev" };
        db.UpsertProject(p);
        Assert.Equal("cd web\nnpm run dev", db.Project(p.Id)!.ShellScript);
        p.ShellScript = null;
        db.UpsertProject(p);
        Assert.Null(db.Project(p.Id)!.ShellScript);
    }

    [Fact]
    public void Existing_database_without_the_column_is_upgraded()
    {
        var path = TempDb();
        using (var c = new SqliteConnection(new SqliteConnectionStringBuilder { DataSource = path }.ToString()))
        {
            c.Open();
            using var cmd = c.CreateCommand();
            cmd.CommandText = """
                CREATE TABLE projects (
                  id TEXT PRIMARY KEY, name TEXT NOT NULL, folder_path TEXT NOT NULL, dev_branch TEXT NOT NULL,
                  permission_mode TEXT NOT NULL, allowed_tools TEXT NOT NULL, max_turns INTEGER NOT NULL,
                  timeout_minutes INTEGER NOT NULL DEFAULT 60, default_model TEXT, default_effort TEXT,
                  sort_order INTEGER NOT NULL DEFAULT 0, paused INTEGER NOT NULL DEFAULT 0);
                INSERT INTO projects (id, name, folder_path, dev_branch, permission_mode, allowed_tools, max_turns)
                VALUES ('11111111-1111-1111-1111-111111111111', 'Old', 'C:\old', 'codenvi-dev', 'acceptEdits', 'Read', 5);
                """;
            cmd.ExecuteNonQuery();
        }
        SqliteConnection.ClearAllPools();
        var db = new LocalDb(path);
        var old = db.Project(Guid.Parse("11111111-1111-1111-1111-111111111111"))!;
        Assert.Equal("Old", old.Name);
        Assert.Null(old.ShellScript);
        old.ShellScript = "echo hi";
        db.UpsertProject(old);
        Assert.Equal("echo hi", db.Project(old.Id)!.ShellScript);
        _ = new LocalDb(path); // opening twice must not fail on the existing column
    }

    [Fact]
    public void ProjectMeta_hint_is_omitted_when_absent_and_old_json_still_reads()
    {
        var without = JsonSerializer.Serialize(new ProjectMeta("Site", "codenvi-dev"), CodenviJson.Options);
        Assert.DoesNotContain("hasScript", without);
        var with = JsonSerializer.Serialize(new ProjectMeta("Site", "codenvi-dev", true), CodenviJson.Options);
        Assert.Contains("\"hasScript\":true", with);
        var old = JsonSerializer.Deserialize<ProjectMeta>("{\"name\":\"Site\",\"devBranch\":\"b\"}", CodenviJson.Options)!;
        Assert.Null(old.HasScript);
        // Written by the phone (Moshi writes false explicitly).
        var phone = JsonSerializer.Deserialize<ProjectMeta>("{\"name\":\"Site\",\"devBranch\":\"b\",\"hasScript\":false}", CodenviJson.Options)!;
        Assert.False(phone.HasScript);
    }
}

public class ScriptRunnerTests
{
    private static (ScriptRunner runner, List<string> lines, AppState state) Make()
    {
        var db = new LocalDb(Path.Combine(Path.GetTempPath(), $"codenvi-test-{Guid.NewGuid():N}.db"));
        var state = new AppState(db);
        var lines = new List<string>();
        state.ActivityLine += (_, l) => { lock (lines) lines.Add(l); };
        return (new ScriptRunner(state, NullLogger<ScriptRunner>.Instance), lines, state);
    }

    private static LocalProject Project(string? script) => new()
    {
        Id = Guid.NewGuid(), Name = "t", FolderPath = Path.GetTempPath(), ShellScript = script,
    };

    private static async Task<bool> WaitAsync(Func<bool> condition, int seconds = 30)
    {
        var end = DateTime.UtcNow.AddSeconds(seconds);
        while (DateTime.UtcNow < end) { if (condition()) return true; await Task.Delay(100); }
        return condition();
    }

    [Fact]
    public void Empty_script_or_missing_folder_is_refused()
    {
        var (runner, _, _) = Make();
        Assert.NotNull(runner.Start(Project(null)));
        Assert.NotNull(runner.Start(Project("   \n ")));
        var p = Project("echo hi");
        p.FolderPath = Path.Combine(Path.GetTempPath(), "does-not-exist-" + Guid.NewGuid());
        Assert.NotNull(runner.Start(p));
    }

    [Fact]
    public async Task Lines_run_in_order_and_output_streams_to_activity()
    {
        AppPaths.Root = Path.Combine(Path.GetTempPath(), "codenvi-tests-" + Guid.NewGuid().ToString("N"));
        var (runner, lines, _) = Make();
        var p = Project("Write-Output first\nWrite-Output second\nWrite-Error oops");
        Assert.Null(runner.Start(p));
        Assert.True(await WaitAsync(() => { lock (lines) return lines.Any(l => l.Contains("Script finished")); }));
        string[] all; lock (lines) all = lines.ToArray();
        Assert.True(Array.FindIndex(all, l => l == "first") < Array.FindIndex(all, l => l == "second"));
        Assert.Contains(all, l => l.Contains("oops"));
        Assert.False(runner.IsRunning(p.Id));
    }

    [Fact]
    public async Task Long_script_is_rejected_when_already_running_and_can_be_stopped()
    {
        AppPaths.Root = Path.Combine(Path.GetTempPath(), "codenvi-tests-" + Guid.NewGuid().ToString("N"));
        var (runner, lines, state) = Make();
        var p = Project("Write-Output started\nStart-Sleep -Seconds 120");
        Assert.Null(runner.Start(p));
        Assert.True(runner.IsRunning(p.Id));
        Assert.True(state.Runtime(p.Id).ScriptRunning);
        Assert.Equal("The script is already running.", runner.Start(p));
        runner.Stop(p.Id);
        Assert.True(await WaitAsync(() => !runner.IsRunning(p.Id)));
        Assert.False(state.Runtime(p.Id).ScriptRunning);
        lock (lines) Assert.Contains(lines, l => l.Contains("Script stopped"));
    }
}
