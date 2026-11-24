using System.Text.Json;

namespace Codenvi.Desktop.Claude;

public abstract record ClaudeEvent;
public sealed record InitEvent(string? SessionId, string? Model) : ClaudeEvent;
public sealed record ToolUseEvent(string Name, string ActivityLine) : ClaudeEvent;
public sealed record TextEvent(string Text) : ClaudeEvent;
public sealed record ResultEvent(bool IsError, string? Subtype, string Text, string? SessionId, decimal? CostUsd,
    long? DurationMs, int? NumTurns, long? InputTokens, long? OutputTokens) : ClaudeEvent;

/// <summary>
/// Tolerant parser for `claude -p --output-format stream-json --verbose` (V4 in docs/design.md §14).
/// Unknown event types and fields are ignored, so format changes don't break jobs.
/// </summary>
public static class StreamJsonParser
{
    public static IEnumerable<ClaudeEvent> Parse(string line, string? projectFolder = null)
    {
        if (string.IsNullOrWhiteSpace(line) || line[0] != '{') yield break;
        JsonDocument doc;
        try { doc = JsonDocument.Parse(line); }
        catch (JsonException) { yield break; }

        using (doc)
        {
            var root = doc.RootElement;
            if (root.ValueKind != JsonValueKind.Object) yield break;
            var type = Str(root, "type");
            switch (type)
            {
                case "system" when Str(root, "subtype") == "init":
                    yield return new InitEvent(Str(root, "session_id"), Str(root, "model"));
                    break;

                case "assistant":
                    if (root.ValueKind == JsonValueKind.Object && root.TryGetProperty("message", out var msg) &&
                        msg.ValueKind == JsonValueKind.Object && msg.TryGetProperty("content", out var content) &&
                        content.ValueKind == JsonValueKind.Array)
                    {
                        foreach (var block in content.EnumerateArray())
                        {
                            if (block.ValueKind != JsonValueKind.Object) continue;
                            var blockType = Str(block, "type");
                            if (blockType == "tool_use")
                            {
                                var name = Str(block, "name") ?? "tool";
                                var input = block.TryGetProperty("input", out var i) ? i : default;
                                yield return new ToolUseEvent(name, Describe(name, input, projectFolder));
                            }
                            else if (blockType == "text" && Str(block, "text") is { Length: > 0 } text)
                            {
                                yield return new TextEvent(text);
                            }
                        }
                    }
                    break;

                case "result":
                {
                    long? inTok = null, outTok = null;
                    if (root.TryGetProperty("usage", out var usage) && usage.ValueKind == JsonValueKind.Object)
                    {
                        inTok = Long(usage, "input_tokens");
                        outTok = Long(usage, "output_tokens");
                    }
                    var isError = root.TryGetProperty("is_error", out var ie) && ie.ValueKind == JsonValueKind.True;
                    yield return new ResultEvent(isError, Str(root, "subtype"), Str(root, "result") ?? "", Str(root, "session_id"),
                        Dec(root, "total_cost_usd") ?? Dec(root, "cost_usd"), Long(root, "duration_ms"), (int?)Long(root, "num_turns"), inTok, outTok);
                    break;
                }
            }
        }
    }

    /// <summary>Turns a tool call into a short activity line such as "Editing src/App.cs" or "Running: npm test".</summary>
    public static string Describe(string tool, JsonElement input, string? projectFolder)
    {
        string? P(string name) => input.ValueKind == JsonValueKind.Object ? Str(input, name) : null;
        string Rel(string? path)
        {
            if (string.IsNullOrEmpty(path)) return "";
            if (projectFolder is not null)
            {
                try
                {
                    var rel = Path.GetRelativePath(projectFolder, path);
                    if (!rel.StartsWith("..", StringComparison.Ordinal) && !Path.IsPathRooted(rel)) path = rel;
                }
                catch (ArgumentException) { }
            }
            return path.Replace('\\', '/');
        }
        static string Cut(string? s, int n = 80)
        {
            s = (s ?? "").ReplaceLineEndings(" ").Trim();
            return s.Length <= n ? s : s[..(n - 1)] + "…";
        }

        return tool switch
        {
            "Read" => $"Reading {Rel(P("file_path"))}",
            "Edit" or "MultiEdit" or "NotebookEdit" => $"Editing {Rel(P("file_path") ?? P("notebook_path"))}",
            "Write" => $"Writing {Rel(P("file_path"))}",
            "Bash" or "PowerShell" => $"Running: {Cut(P("command"))}",
            "Grep" => $"Searching: {Cut(P("pattern"), 60)}",
            "Glob" => $"Finding files: {Cut(P("pattern"), 60)}",
            "WebFetch" => $"Fetching {Cut(P("url"))}",
            "WebSearch" => $"Searching the web: {Cut(P("query"), 60)}",
            "TodoWrite" => "Updating the plan",
            "Task" or "Agent" => $"Starting a subagent: {Cut(P("description"), 60)}",
            _ => $"Using {tool}",
        };
    }

    private static string? Str(JsonElement e, string name) =>
        e.ValueKind == JsonValueKind.Object && e.TryGetProperty(name, out var v) && v.ValueKind == JsonValueKind.String ? v.GetString() : null;

    private static long? Long(JsonElement e, string name) =>
        e.ValueKind == JsonValueKind.Object && e.TryGetProperty(name, out var v) && v.ValueKind == JsonValueKind.Number && v.TryGetInt64(out var l) ? l : null;

    private static decimal? Dec(JsonElement e, string name) =>
        e.ValueKind == JsonValueKind.Object && e.TryGetProperty(name, out var v) && v.ValueKind == JsonValueKind.Number && v.TryGetDecimal(out var d) ? d : null;
}
