package app.codenvi.repo

enum class DiffKind { HUNK, ADDED, REMOVED, CONTEXT, NOTE }

/** One displayed line of a diff. [number] is the line number in the new file (the old file for removed lines); null for headers and notes. */
data class DiffLine(val kind: DiffKind, val text: String, val number: Int? = null)

data class DiffResult(val lines: List<DiffLine>, val added: Int, val removed: Int)

object Diff {
    private val headerPrefixes = listOf("diff --git", "index ", "--- ", "+++ ", "new file mode", "deleted file mode", "old mode", "new mode",
        "similarity index", "rename from", "rename to", "copy from", "copy to", "dissimilarity index")
    private val hunk = Regex("""^@@ -(\d+)(?:,\d+)? \+(\d+)(?:,\d+)? @@.*""")

    /**
     * Parses a unified diff (what `git diff` prints). The file header lines before the first hunk are dropped. Lines before a hunk
     * that are not headers, and "\ No newline" markers, are notes (for example "New binary file (12 bytes)." or "diff truncated").
     */
    fun parse(text: String): DiffResult {
        val out = ArrayList<DiffLine>()
        var oldNo = 0
        var newNo = 0
        var inHunk = false
        var added = 0
        var removed = 0
        for (raw in text.split('\n')) {
            val line = raw.removeSuffix("\r")
            val h = hunk.matchEntire(line)
            when {
                h != null -> {
                    oldNo = h.groupValues[1].toInt()
                    newNo = h.groupValues[2].toInt()
                    inHunk = true
                    out += DiffLine(DiffKind.HUNK, line)
                }
                !inHunk && headerPrefixes.any { line.startsWith(it) } -> {}
                !inHunk -> if (line.isNotBlank()) out += DiffLine(DiffKind.NOTE, line)
                line.startsWith("\\") -> out += DiffLine(DiffKind.NOTE, line.removePrefix("\\").trim())
                line.startsWith("+") -> { out += DiffLine(DiffKind.ADDED, line.substring(1), newNo++); added++ }
                line.startsWith("-") -> { out += DiffLine(DiffKind.REMOVED, line.substring(1), oldNo++); removed++ }
                line.startsWith(" ") -> { out += DiffLine(DiffKind.CONTEXT, line.substring(1), newNo); newNo++; oldNo++ }
                line.isEmpty() -> {} // the empty piece after the last newline
                else -> out += DiffLine(DiffKind.NOTE, line) // e.g. "... diff truncated"
            }
        }
        return DiffResult(out, added, removed)
    }
}
