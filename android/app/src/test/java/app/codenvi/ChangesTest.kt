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

    @Test fun treeNestsAndMergesSingleFolderChains() {
        val t = Changes.tree(listOf(
            FileChange("android/app/src/main/java/app/codenvi/CodenviApp.kt", ChangeKind.UPDATED),
            FileChange("android/app/src/main/java/app/codenvi/notify/Notifier.kt", ChangeKind.NEW),
            FileChange("android/app/src/main/java/app/codenvi/data/Stores.kt", ChangeKind.UPDATED),
            FileChange("android/app/src/main/res/raw/reply_chime.wav", ChangeKind.NEW),
            FileChange("docs/design.md", ChangeKind.UPDATED), FileChange("CHANGELOG.md", ChangeKind.UPDATED),
        ))
        assertEquals(listOf("android\\app\\src\\main", "docs", "CHANGELOG.md"), t.map { it.name })
        val main = t[0]
        assertEquals("android/app/src/main", main.path)
        assertEquals(listOf("java\\app\\codenvi", "res\\raw"), main.children.map { it.name })
        val codenvi = main.children[0]
        assertEquals(listOf("data", "notify", "CodenviApp.kt"), codenvi.children.map { it.name })
        assertEquals("android/app/src/main/java/app/codenvi/notify/Notifier.kt", codenvi.children[1].children.single().path)
        assertEquals(ChangeKind.NEW, codenvi.children[1].children.single().kind)
    }

    @Test fun flattenHonoursCollapsedFolders() {
        val tree = Changes.tree(listOf(FileChange("src/a/x.kt", ChangeKind.NEW), FileChange("src/b.kt", ChangeKind.NEW), FileChange("c.kt", ChangeKind.NEW)))
        assertEquals(listOf("src" to 0, "a" to 1, "x.kt" to 2, "b.kt" to 1, "c.kt" to 0),
            Changes.flatten(tree, emptySet()).map { it.first.name to it.second })
        assertEquals(listOf("src" to 0, "c.kt" to 0), Changes.flatten(tree, setOf("src")).map { it.first.name to it.second })
    }
}
