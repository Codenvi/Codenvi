package app.codenvi

import app.codenvi.crypto.ControlCommands
import app.codenvi.crypto.ControlPlain
import app.codenvi.crypto.ProjectMeta
import app.codenvi.net.parseJson
import app.codenvi.net.toJsonBytes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScriptMetaTest {
    @Test
    fun old_project_info_without_the_hint_reads_as_no_script() {
        val meta = """{"name":"Site","devBranch":"codenvi-dev"}""".toByteArray().parseJson<ProjectMeta>()
        assertFalse(meta.hasScript)
    }

    @Test
    fun hint_written_by_the_pc_is_read() {
        val meta = """{"name":"Site","devBranch":"b","hasScript":true}""".toByteArray().parseJson<ProjectMeta>()
        assertTrue(meta.hasScript)
        assertEquals(meta, meta.toJsonBytes().parseJson<ProjectMeta>())
    }

    @Test
    fun renaming_keeps_the_hint() {
        val meta = ProjectMeta("Site", "b", hasScript = true)
        assertTrue(meta.copy(name = "New").hasScript)
    }

    @Test
    fun running_hint_defaults_to_false_and_is_read() {
        assertFalse("""{"name":"S","devBranch":"b","hasScript":true}""".toByteArray().parseJson<ProjectMeta>().scriptRunning)
        assertTrue("""{"name":"S","devBranch":"b","hasScript":true,"scriptRunning":true}""".toByteArray().parseJson<ProjectMeta>().scriptRunning)
        assertEquals("project.script.stop", ControlCommands.STOP_SCRIPT)
    }

    @Test
    fun run_script_command_carries_no_script_text() {
        val cmd = ControlPlain(ControlCommands.RUN_SCRIPT, "p1")
        val back = cmd.toJsonBytes().parseJson<ControlPlain>()
        assertEquals("project.script.run", back.command)
        assertNull(back.text)
    }
}
