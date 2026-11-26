package app.codenvi

import app.codenvi.repo.Diff
import app.codenvi.repo.DiffKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiffTest {
    private val sample = """
        diff --git a/src/a.kt b/src/a.kt
        index 1111111..2222222 100644
        --- a/src/a.kt
        +++ b/src/a.kt
        @@ -10,4 +10,5 @@ class A {
         keep
        -old
        +new one
        +new two
         tail
        \ No newline at end of file
    """.trimIndent() + "\n"

    @Test fun dropsFileHeadersAndNumbersLines() {
        val r = Diff.parse(sample)
        assertEquals(listOf(DiffKind.HUNK, DiffKind.CONTEXT, DiffKind.REMOVED, DiffKind.ADDED, DiffKind.ADDED, DiffKind.CONTEXT, DiffKind.NOTE),
            r.lines.map { it.kind })
        assertEquals(listOf(null, 10, 11, 11, 12, 13, null), r.lines.map { it.number })
        assertEquals(2, r.added)
        assertEquals(1, r.removed)
        assertEquals("No newline at end of file", r.lines.last().text)
    }

    @Test fun newFileHunkStartsAtOne() {
        val r = Diff.parse("@@ -0,0 +1,2 @@\n+a\n+b\n")
        assertEquals(listOf(1, 2), r.lines.drop(1).map { it.number })
        assertEquals(2, r.added)
    }

    @Test fun plainMessagesBecomeNotes() {
        val r = Diff.parse("New binary file (12 bytes).")
        assertEquals(listOf(DiffKind.NOTE), r.lines.map { it.kind })
        assertTrue(Diff.parse("").lines.isEmpty())
    }

    @Test fun carriageReturnsAreStripped() {
        val r = Diff.parse("@@ -1 +1 @@\r\n-a\r\n+b\r\n")
        assertEquals(listOf("a", "b"), r.lines.drop(1).map { it.text })
    }
}
