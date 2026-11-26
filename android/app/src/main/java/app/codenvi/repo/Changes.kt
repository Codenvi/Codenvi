package app.codenvi.repo

/** What happened to a file since the last commit (the dot on the Code changes page: green, blue, red). */
enum class ChangeKind { NEW, UPDATED, DELETED }

data class FileChange(val path: String, val kind: ChangeKind)

/** The PC's answer to project.changes: the files plus how many more were left out (it lists at most a few thousand). */
data class ChangesResult(val files: List<FileChange>, val more: Int)

/**
 * A node of the Code changes tree: a folder (kind == null) with children, or a changed file. A chain of folders that each hold
 * only one folder is shown as one row ("android\app\src\main"), like the VS Code explorer. [path] is the full path
 * (of the deepest folder of a chain), with "/" separators, and identifies the row.
 */
data class ChangeNode(val name: String, val path: String, val kind: ChangeKind?, val children: List<ChangeNode> = emptyList()) {
    val isFolder get() = kind == null
}

object Changes {
    /** Parses the PC's text: one "N|U|D<TAB>path" line per file, and an optional "+<TAB>count" line. Unknown lines are skipped. */
    fun parse(text: String?): ChangesResult {
        val files = ArrayList<FileChange>()
        var more = 0
        text.orEmpty().lineSequence().forEach { line ->
            val tab = line.indexOf('\t')
            if (tab != 1) return@forEach
            val rest = line.substring(2)
            when (line[0]) {
                'N' -> files += FileChange(rest, ChangeKind.NEW)
                'U' -> files += FileChange(rest, ChangeKind.UPDATED)
                'D' -> files += FileChange(rest, ChangeKind.DELETED)
                '+' -> more = rest.trim().toIntOrNull() ?: 0
            }
        }
        return ChangesResult(files, more)
    }

    /** The folder tree: folders first, then files, each alphabetical (case-insensitive); single-folder chains are merged. */
    fun tree(files: List<FileChange>): List<ChangeNode> {
        class Dir { val dirs = java.util.TreeMap<String, Dir>(String.CASE_INSENSITIVE_ORDER); val files = ArrayList<Pair<String, ChangeKind>>() }
        val root = Dir()
        for (f in files) {
            val parts = f.path.split('/').filter { it.isNotEmpty() }
            if (parts.isEmpty()) continue
            var d = root
            for (p in parts.dropLast(1)) d = d.dirs.getOrPut(p) { Dir() }
            d.files += parts.last() to f.kind
        }
        fun build(d: Dir, prefix: String): List<ChangeNode> {
            val folders = d.dirs.map { (name, sub) ->
                var label = name
                var path = prefix + name
                var cur = sub
                // Merge while the folder holds exactly one folder and no files.
                while (cur.files.isEmpty() && cur.dirs.size == 1) {
                    val (childName, child) = cur.dirs.entries.first()
                    label = "$label\\$childName"
                    path = "$path/$childName"
                    cur = child
                }
                ChangeNode(label, path, null, build(cur, "$path/"))
            }
            return folders + d.files.sortedBy { it.first.lowercase() }.map { (name, kind) -> ChangeNode(name, prefix + name, kind) }
        }
        return build(root, "")
    }

    /** The tree flattened for display: (node, depth) in order, skipping the children of folders whose path is in [collapsed]. */
    fun flatten(nodes: List<ChangeNode>, collapsed: Set<String>, depth: Int = 0): List<Pair<ChangeNode, Int>> {
        val out = ArrayList<Pair<ChangeNode, Int>>()
        for (n in nodes) {
            out += n to depth
            if (n.isFolder && n.path !in collapsed) out += flatten(n.children, collapsed, depth + 1)
        }
        return out
    }
}
