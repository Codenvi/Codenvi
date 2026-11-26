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

    @Test fun groupsByFullFolderPath() {
        val g = Changes.groups(listOf(
            FileChange("android/app/src/codenvi/speech/Speech.kt", ChangeKind.UPDATED),
            FileChange("android/app/src/codenvi/CodenviApp.kt", ChangeKind.NEW),
            FileChange("z.txt", ChangeKind.DELETED), FileChange("android/app/src/codenvi/B.kt", ChangeKind.NEW),
        ))
        assertEquals(listOf("", "android/app/src/codenvi", "android/app/src/codenvi/speech"), g.map { it.dir })
        assertEquals(listOf("z.txt"), g[0].files.map { it.name })
        assertEquals(listOf("B.kt", "CodenviApp.kt"), g[1].files.map { it.name })
        assertEquals(ChangeKind.UPDATED, g[2].files.single().kind)
    }
}
