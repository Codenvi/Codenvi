using System.Text.Json;
using Codenvi.Contracts.Crypto;
using Codenvi.Desktop.Claude;
using Codenvi.Desktop.Core;
using Codenvi.Desktop.Git;
using Codenvi.Desktop.Jobs;
using Xunit;

namespace Codenvi.Desktop.Tests;

public class StreamJsonParserTests
{
    [Fact]
    public void Parses_init_tool_use_and_result()
    {
        var init = StreamJsonParser.Parse("""{"type":"system","subtype":"init","session_id":"abc","model":"sonnet"}""").Single();
        Assert.Equal(new InitEvent("abc", "sonnet"), init);

        var tool = StreamJsonParser.Parse(
            """{"type":"assistant","message":{"content":[{"type":"tool_use","name":"Edit","input":{"file_path":"C:\\repo\\src\\App.cs"}}]}}""",
            @"C:\repo").OfType<ToolUseEvent>().Single();
        Assert.Equal("Editing src/App.cs", tool.ActivityLine);

        var bash = StreamJsonParser.Parse(
            """{"type":"assistant","message":{"content":[{"type":"tool_use","name":"Bash","input":{"command":"npm test"}}]}}""")
            .OfType<ToolUseEvent>().Single();
        Assert.Equal("Running: npm test", bash.ActivityLine);

        var result = (ResultEvent)StreamJsonParser.Parse(
            """{"type":"result","subtype":"success","is_error":false,"result":"Done.","session_id":"abc","total_cost_usd":0.0123,"duration_ms":4200,"num_turns":3,"usage":{"input_tokens":10,"output_tokens":20}}""").Single();
        Assert.False(result.IsError);
        Assert.Equal("Done.", result.Text);
        Assert.Equal(0.0123m, result.CostUsd);
        Assert.Equal(4200, result.DurationMs);
        Assert.Equal(20, result.OutputTokens);
    }

    [Theory]
    [InlineData("")]
    [InlineData("not json")]
    [InlineData("{broken")]
    [InlineData("""{"type":"something_new","x":1}""")]
    [InlineData("""{"type":"assistant","message":"unexpected shape"}""")]
    public void Ignores_unknown_or_broken_lines(string line) => Assert.Empty(StreamJsonParser.Parse(line));

    [Fact]
    public void Unknown_tool_gets_generic_line() =>
        Assert.Equal("Using FancyTool", StreamJsonParser.Describe("FancyTool", JsonDocument.Parse("{}").RootElement, null));
}

public class ClaudeCliTests
{
    [Fact]
    public void First_message_uses_session_id_and_name()
    {
        var id = Guid.NewGuid();
        var args = ClaudeCli.BuildArguments(true, id, "Main", "sonnet", "high", "acceptEdits", "Read Edit Bash(npm test:*)", 40);
        Assert.Equal("-p", args[0]);
        Assert.Contains("--session-id", args);
        Assert.Equal(id.ToString("D"), args[args.IndexOf("--session-id") + 1]);
        Assert.Equal("Codenvi: Main", args[args.IndexOf("-n") + 1]);
        Assert.DoesNotContain("--resume", args);
        Assert.Equal("sonnet", args[args.IndexOf("--model") + 1]);
        Assert.Equal("high", args[args.IndexOf("--effort") + 1]);
        Assert.Equal("none", args[args.IndexOf("--permission-prompts") + 1]);
        Assert.Contains("Bash(npm test:*)", args);
        Assert.Equal("stream-json", args[args.IndexOf("--output-format") + 1]);
    }

    [Fact]
    public void Later_messages_resume_and_default_model_is_omitted()
    {
        var id = Guid.NewGuid();
        var args = ClaudeCli.BuildArguments(false, id, "Main", "default", null, "acceptEdits", "", 0);
        Assert.Equal(id.ToString("D"), args[args.IndexOf("--resume") + 1]);
        Assert.DoesNotContain("--model", args);
        Assert.DoesNotContain("--effort", args);
        Assert.DoesNotContain("--allowedTools", args);
        Assert.DoesNotContain("--max-turns", args);
    }

    [Fact]
    public void Session_names_are_sanitized_for_cmd_shims() =>
        Assert.Equal("Codenvi: fix  calc", ClaudeCli.SafeSessionName("fix & calc\"|"));

    [Theory]
    [InlineData("2.1.259 (Claude Code)", 2, 1, 259)]
    [InlineData("claude 3.0.1", 3, 0, 1)]
    public void Parses_version(string text, int a, int b, int c) => Assert.Equal(new Version(a, b, c), ClaudeCli.ParseVersion(text));
}

public class JobHelpersTests
{
    [Fact]
    public void Chunking_keeps_all_text_and_respects_size()
    {
        var text = string.Join('\n', Enumerable.Range(0, 5000).Select(i => $"line {i} " + new string('x', i % 70)));
        var parts = JobScheduler.Chunk(text, 10_000);
        Assert.True(parts.Count > 1);
        Assert.All(parts, p => Assert.True(p.Length <= 10_000));
        Assert.Equal(text, string.Concat(parts));
    }

    [Fact]
    public void Commit_message_format()
    {
        Assert.Equal("Codenvi: Add a login page", GitService.TaskSummary("Add a login page"));
        Assert.Equal(60 + "Codenvi: ".Length, GitService.TaskSummary(new string('a', 200)).Length);
        var body = GitService.TaskBody("opus", "Reply", Guid.Empty, Guid.Empty);
        Assert.Contains("Codenvi-Job: 00000000-0000-0000-0000-000000000000", body);
        Assert.Contains("Codenvi-Session:", body);
        Assert.StartsWith("Model: opus", body);
    }
}

public class LocalStoreTests
{
    [Fact]
    public void Replay_ids_are_recorded_once()
    {
        var path = Path.Combine(Path.GetTempPath(), $"codenvi-test-{Guid.NewGuid():N}.db");
        var db = new LocalDb(path);
        var id = Guid.NewGuid();
        Assert.False(db.WasSeen(id));
        Assert.True(db.MarkSeen(id, "job"));
        Assert.False(db.MarkSeen(id, "job"));
        Assert.True(db.WasSeen(id));
    }

    [Fact]
    public void Projects_and_sessions_roundtrip()
    {
        var db = new LocalDb(Path.Combine(Path.GetTempPath(), $"codenvi-test-{Guid.NewGuid():N}.db"));
        var p = new LocalProject { Id = Guid.NewGuid(), Name = "Site", FolderPath = @"C:\src\site" };
        db.UpsertProject(p);
        var s = Services.ProjectService.NewLocalSession(p.Id, Guid.NewGuid(), "Main");
        db.UpsertSession(s);
        Assert.Equal("Site", db.Project(p.Id)!.Name);
        var back = db.Session(s.Id)!;
        Assert.Equal(s.ClaudeSessionId, back.ClaudeSessionId);
        Assert.False(back.Started);
    }

    [Fact]
    public void Secrets_roundtrip_with_dpapi()
    {
        var path = Path.Combine(Path.GetTempPath(), $"codenvi-secrets-{Guid.NewGuid():N}.bin");
        var store = new SecretStore(path);
        var key = DeviceKey.Generate();
        store.Update(s => { s.ServerHost = "h.example"; s.Keys[1] = key; s.CurrentKeyVersion = 1; s.DeviceId = Guid.NewGuid(); });
        var again = new SecretStore(path);
        Assert.Equal("h.example", again.Current.ServerHost);
        Assert.Equal(key, again.CurrentKey);
        Assert.NotEqual(key, File.ReadAllBytes(path)[..32]);
    }
}

public class ServerAddressTests
{
    [Theory]
    [InlineData("codenvi.example.com", "codenvi.example.com")]
    [InlineData("  Codenvi.Example.com  ", "codenvi.example.com")]
    [InlineData("https://codenvi.example.com", "codenvi.example.com")]
    [InlineData("https://codenvi.example.com/", "codenvi.example.com")]
    [InlineData("codenvi.example.com:8443", "codenvi.example.com:8443")]
    [InlineData("localhost:5443", "localhost:5443")]
    public void Normalizes_host_names(string input, string expected) => Assert.Equal(expected, ServerAddress.Normalize(input));

    [Theory]
    [InlineData("")]
    [InlineData("   ")]
    [InlineData("codenvi")]
    [InlineData("http://codenvi.example.com")]
    [InlineData("codenvi.example.com/path")]
    [InlineData("user@codenvi.example.com")]
    [InlineData("two words.com")]
    public void Rejects_things_that_are_not_a_host_name(string input) => Assert.Null(ServerAddress.Normalize(input));
}

public class QrCodeImageTests
{
    [Fact]
    public void Renders_a_png_for_a_long_phone_connection_string()
    {
        var s = "codenvi://a-long-subdomain.example-server-host.com:8443/pair?c=ABCD2345&dev=" + Guid.NewGuid() +
                "&kv=12&k=" + new string('A', 52) + "&dn=" + Uri.EscapeDataString("My Work Laptop Number 2") + "&pin=" + new string('B', 43);
        var png = QrCodeImage.Png(s);
        Assert.True(png.Length > 100);
        Assert.Equal(new byte[] { 0x89, 0x50, 0x4E, 0x47 }, png.Take(4).ToArray()); // PNG signature
    }
}
