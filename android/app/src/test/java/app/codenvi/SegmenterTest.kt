package app.codenvi

import app.codenvi.speech.Segmenter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class SegmenterTest {
    private val rate = 16_000

    /** [seconds] of a 440 Hz tone (loud) or silence, as 16-bit little-endian PCM. */
    private fun pcm(vararg parts: Pair<Boolean, Double>): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        for ((loud, seconds) in parts) {
            for (i in 0 until (seconds * rate).toInt()) {
                val v = if (loud) (sin(2 * PI * 440 * i / rate) * 12_000).toInt() else 0
                out.write(v and 0xFF); out.write((v shr 8) and 0xFF)
            }
        }
        return out.toByteArray()
    }

    private fun seconds(r: IntRange) = (r.last - r.first + 1) / 2.0 / rate

    @Test fun silenceGivesNoSegments() = assertTrue(Segmenter.split(pcm(false to 3.0)).isEmpty())

    @Test fun shortBlipIsIgnored() = assertTrue(Segmenter.split(pcm(false to 1.0, true to 0.1, false to 1.0)).isEmpty())

    @Test fun shortPauseKeepsOneSegment() {
        val segs = Segmenter.split(pcm(true to 2.0, false to 0.8, true to 2.0))
        assertEquals(1, segs.size)
    }

    @Test fun longPauseSplitsIntoTwo() {
        val segs = Segmenter.split(pcm(false to 0.5, true to 2.0, false to 3.0, true to 2.0, false to 0.5))
        assertEquals(2, segs.size)
        assertTrue(segs[0].last < segs[1].first)
        assertTrue(segs.all { it.first % 2 == 0 && (it.last + 1) % 2 == 0 })
    }

    @Test fun endlessSpeechIsCutBelowTheRecognizerLimit() {
        val segs = Segmenter.split(pcm(true to 100.0))
        assertTrue(segs.size >= 3)
        assertTrue(segs.all { seconds(it) <= 45.5 })
    }
}
