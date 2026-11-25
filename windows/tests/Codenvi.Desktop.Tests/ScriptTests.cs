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

public class ScriptCommandLineTests
{
    [Fact]
    public void Lines_are_joined_into_one_command_line_and_blank_lines_dropped()
    {
        var line = ScriptRunner.BuildCommandLine("code D:\\PROJECTS\\Benefy\r\n\r\n  start chrome --new-window http://localhost:3002  \n");
        Assert.Equal("chcp 65001 >nul & code D:\\PROJECTS\\Benefy & start chrome --new-window http://localhost:3002", line);
    }

    [Fact]
    public void Too_long_scripts_are_refused() =>
        Assert.Null(ScriptRunner.BuildCommandLine(string.Join("\n", Enumerable.Repeat("echo " + new string('x', 100), 100))));
}

public class ScriptRunnerTests
{
    private static (ScriptRunner runner, List<string> lines, AppState state) Make()
    {
        var db = new LocalDb(Path.Combine(Path.GetTempPath(), $"codenvi-test-{Guid.NewGuid():N}.db"));
        var state = new AppState(db);
        var lines = new List<string>();
        state.TerminalLine += (_, l) => { lock (lines) lines.Add(l); };
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
        var p = Project("echo first\r\n\r\n   echo second\necho oops 1>&2");
        Assert.Null(runner.Start(p));
        Assert.True(await WaitAsync(() => { lock (lines) return lines.Any(l => l.Contains("Script finished")); }));
        string[] all; lock (lines) all = lines.ToArray();
        Assert.True(Array.FindIndex(all, l => l == "first") < Array.FindIndex(all, l => l == "second"));
        Assert.Contains(all, l => l.Contains("oops"));
        Assert.False(runner.IsRunning(p.Id));
    }

    [Fact]
    public async Task Commands_that_start_other_batch_files_do_not_stop_the_following_lines_and_cd_carries_over()
    {
        AppPaths.Root = Path.Combine(Path.GetTempPath(), "codenvi-tests-" + Guid.NewGuid().ToString("N"));
        var (runner, lines, _) = Make();
        // npm/npx/code are .cmd shims. A batch file would end at the first one; a single command line does not.
        var dir = Path.Combine(Path.GetTempPath(), "codenvi-shim-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(Path.Combine(dir, "sub"));
        File.WriteAllText(Path.Combine(dir, "shim.cmd"), "@echo off\r\necho from-shim\r\n");
        var p = Project(".\\shim.cmd\ncd sub\ncd\nstart \"\" cmd /c exit\necho last");
        p.FolderPath = dir;
        Assert.Null(runner.Start(p));
        Assert.True(await WaitAsync(() => { lock (lines) return lines.Any(l => l.Contains("Script finished")); }));
        string[] all; lock (lines) all = lines.ToArray();
        Assert.Contains("from-shim", all);
        Assert.Contains(all, l => l.EndsWith(Path.Combine(dir, "sub")));
        Assert.Contains("last", all);
    }

    [Fact]
    public async Task Long_script_is_rejected_when_already_running_and_can_be_stopped()
    {
        AppPaths.Root = Path.Combine(Path.GetTempPath(), "codenvi-tests-" + Guid.NewGuid().ToString("N"));
        var (runner, lines, state) = Make();
        var p = Project("echo started\nping -n 120 127.0.0.1 >nul");
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

public class ScriptStopTests
{
    [Fact]
    public void Running_hint_is_omitted_when_not_running_and_written_when_running()
    {
        Assert.DoesNotContain("scriptRunning", System.Text.Json.JsonSerializer.Serialize(new ProjectMeta("S", "b", true), CodenviJson.Options));
        Assert.Contains("\"scriptRunning\":true", System.Text.Json.JsonSerializer.Serialize(new ProjectMeta("S", "b", true, true), CodenviJson.Options));
        Assert.Equal("project.script.stop", ControlCommands.StopScript);
    }

    [Fact]
    public async Task Stop_reports_whether_a_script_was_running_and_start_and_end_raise_RunningChanged()
    {
        AppPaths.Root = Path.Combine(Path.GetTempPath(), "codenvi-tests-" + Guid.NewGuid().ToString("N"));
        var state = new AppState(new LocalDb(Path.Combine(Path.GetTempPath(), $"codenvi-test-{Guid.NewGuid():N}.db")));
        var runner = new ScriptRunner(state, NullLogger<ScriptRunner>.Instance);
        var events = new List<bool>();
        var id = Guid.NewGuid();
        runner.RunningChanged += i => { lock (events) events.Add(state.Runtime(i).ScriptRunning); };
        Assert.False(runner.Stop(id));
        var p = new LocalProject { Id = id, Name = "t", FolderPath = Path.GetTempPath(), ShellScript = "ping -n 120 127.0.0.1 >nul" };
        Assert.Null(runner.Start(p));
        Assert.True(runner.Stop(id));
        var end = DateTime.UtcNow.AddSeconds(30);
        while (runner.IsRunning(id) && DateTime.UtcNow < end) await Task.Delay(100);
        lock (events) Assert.Equal(new[] { true, false }, events.ToArray());
    }
}
