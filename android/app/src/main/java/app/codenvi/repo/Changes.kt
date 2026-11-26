package app.codenvi.repo

/** What happened to a file since the last commit (the dot on the Code changes page: green, blue, red). */
enum class ChangeKind { NEW, UPDATED, DELETED }

data class FileChange(val path: String, val kind: ChangeKind)

/** The PC's answer to project.changes: the files plus how many more were left out (it lists at most a few thousand). */
data class ChangesResult(val files: List<FileChange>, val more: Int)

/** The files of one folder: [dir] is the full folder path ("" = the project root), shown once above its files. */
data class ChangeGroup(val dir: String, val files: List<ChangedFile>)

data class ChangedFile(val name: String, val kind: ChangeKind)

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

    /**
     * Groups the files by their full folder path, so a folder with one changed file is shown as one line "a/b/c" followed
     * by the file, instead of a chain of nested folders. Groups are alphabetical by path (root first), files by name.
     */
    fun groups(files: List<FileChange>): List<ChangeGroup> {
        val byDir = sortedMapOf<String, MutableList<ChangedFile>>(String.CASE_INSENSITIVE_ORDER)
        for (f in files) {
            val parts = f.path.split('/').filter { it.isNotEmpty() }
            if (parts.isEmpty()) continue
            byDir.getOrPut(parts.dropLast(1).joinToString("/")) { ArrayList() } += ChangedFile(parts.last(), f.kind)
        }
        return byDir.map { (dir, list) -> ChangeGroup(dir, list.sortedBy { it.name.lowercase() }) }
    }
}
