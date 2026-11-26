package app.codenvi

import app.codenvi.repo.ChangeKind
import app.codenvi.repo.Changes
import app.codenvi.repo.FileChange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChangesTest {
    @Test fun parsesLinesAndSkipsGarbage() {
        val r = Changes.parse("N\ta/b.txt\nU\tc d.kt\nD\tgone.md\nbogus\nX\tz\n+\t7\n")
        assertEquals(
            listOf(FileChange("a/b.txt", ChangeKind.NEW), FileChange("c d.kt", ChangeKind.UPDATED), FileChange("gone.md", ChangeKind.DELETED)),
            r.files,
        )
        assertEquals(7, r.more)
    }

    @Test fun emptyOrNullGivesNothing() {
        assertTrue(Changes.parse(null).files.isEmpty())
        assertTrue(Changes.parse("").files.isEmpty())
    }

    @Test fun treeNestsFoldersFirstThenFiles() {
        val tree = Changes.tree(listOf(
            FileChange("z.txt", ChangeKind.NEW), FileChange("src/b.kt", ChangeKind.UPDATED),
            FileChange("src/a/x.kt", ChangeKind.DELETED), FileChange("Readme.md", ChangeKind.UPDATED),
        ))
        assertEquals(listOf("src", "Readme.md", "z.txt"), tree.map { it.name })
        val src = tree[0]
        assertTrue(src.isFolder)
        assertEquals(listOf("a", "b.kt"), src.children.map { it.name })
        assertEquals("src/a", src.children[0].path)
        assertEquals(ChangeKind.DELETED, src.children[0].children[0].kind)
    }

    @Test fun flattenHonoursCollapsedFolders() {
        val tree = Changes.tree(listOf(FileChange("src/a.kt", ChangeKind.NEW), FileChange("b.kt", ChangeKind.NEW)))
        assertEquals(listOf("src" to 0, "a.kt" to 1, "b.kt" to 0), Changes.flatten(tree, emptySet()).map { it.first.name to it.second })
        assertEquals(listOf("src" to 0, "b.kt" to 0), Changes.flatten(tree, setOf("src")).map { it.first.name to it.second })
    }
}
