package app.codenvi

import app.codenvi.speech.Segmenter
import app.codenvi.speech.Transcript
import app.codenvi.speech.WhisperManager
import app.codenvi.speech.WhisperModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WhisperTest {
    @Test fun cleanRemovesNonSpeechTagsAndTidiesSpaces() {
        assertEquals("Hello there. How are you?", Transcript.clean("  Hello   there. [BLANK_AUDIO] How are you? (silence) "))
        assertEquals("", Transcript.clean("[BLANK_AUDIO]"))
        assertEquals("keep (this one) and [1] too", Transcript.clean("keep (this one) and [1] too"))
    }

    @Test fun modelsHaveDistinctFilesAndUrls() {
        assertEquals(WhisperModel.BASE, WhisperModel.fromId("nonsense"))
        assertEquals(WhisperModel.SMALL, WhisperModel.fromId("small.en"))
        assertTrue(WhisperModel.values().map { it.file }.toSet().size == WhisperModel.values().size)
        assertTrue(WhisperModel.BASE.url.endsWith("ggml-base.en-q5_1.bin"))
        assertEquals(57, WhisperModel.BASE.megabytes)
    }

    @Test fun speechOnlyJoinsSegmentsAndPadsShortAudio() {
        // two 0.1 s segments of constant samples (value 16384 = 0.5), as little-endian bytes
        val n = 1600
        val pcm = ByteArray(n * 2) { i -> if (i % 2 == 0) 0 else 0x40 }
        val out = WhisperManager.speechOnly(pcm, listOf(0 until n * 2))
        assertEquals(0.5f, out[0], 0.0001f)
        assertEquals(0.5f, out[n - 1], 0.0001f)
        assertEquals(0f, out[n], 0f) // the gap after the segment
        assertTrue(out.size >= 16_000 * 12 / 10) // at least 1.2 s for Whisper
    }

    @Test fun segmenterStillFindsSpeech() {
        val rate = 16_000
        val tone = ByteArray(rate * 2) { i -> if (i % 4 < 2) 0x00 else 0x30 } // loud square-ish wave
        assertTrue(Segmenter.split(tone).isNotEmpty())
    }
}
