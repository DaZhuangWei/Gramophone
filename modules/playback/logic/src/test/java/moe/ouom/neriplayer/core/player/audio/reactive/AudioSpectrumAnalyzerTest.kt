package moe.ouom.neriplayer.core.player.audio.reactive

import androidx.media3.common.C
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioSpectrumAnalyzerTest {

    @Test
    fun `sine wave peaks near its own frequency band`() {
        assertSinePeak(100.0)
        assertSinePeak(1_000.0)
        assertSinePeak(10_000.0)
    }

    @Test
    fun `44_1 khz sample rate maps to the expected band`() {
        val analyzer = AudioSpectrumAnalyzer()
        analyzer.flush(44_100, 1, C.ENCODING_PCM_16BIT)
        analyzer.feed(sinePcm16(1_000.0, 44_100))
        var now = System.nanoTime()
        repeat(4) {
            now += 40_000_000L
            analyzer.analyze(now)
        }
        assertPeakNear(analyzer, 1_000.0)
    }

    @Test
    fun `higher frequency produces a higher centroid`() {
        val low = analyzedFrame(120.0)
        val high = analyzedFrame(9_000.0)

        assertTrue(high.centroidHz > low.centroidHz * 4f)
    }

    @Test
    fun `silence decays the spectrum and clears level`() {
        val analyzer = AudioSpectrumAnalyzer()
        analyzer.flush(48_000, 1, C.ENCODING_PCM_16BIT)
        analyzer.feed(sinePcm16(1_000.0, 48_000))
        var now = System.nanoTime()
        repeat(3) {
            now += 40_000_000L
            analyzer.analyze(now)
        }
        assertTrue(analyzer.frame.value.level > 0.01f)

        now += 1_000_000_000L
        repeat(8) {
            now += 200_000_000L
            analyzer.analyze(now)
        }
        val frame = analyzer.frame.value
        assertEquals(0f, frame.level, 0f)
        assertTrue((frame.bands.maxOrNull() ?: 0f) < 0.05f)
    }

    @Test
    fun `flush clears previous spectrum and reports new sample rate`() {
        val analyzer = AudioSpectrumAnalyzer()
        analyzer.flush(48_000, 1, C.ENCODING_PCM_16BIT)
        analyzer.feed(sinePcm16(1_000.0, 48_000))
        analyzer.analyze(System.nanoTime() + 40_000_000L)
        assertTrue(analyzer.frame.value.bands.any { it > 0f })

        analyzer.flush(44_100, 2, C.ENCODING_PCM_16BIT)
        val frame = analyzer.frame.value
        assertEquals(44_100, frame.sampleRate)
        assertTrue(frame.bands.all { it == 0f })
        assertTrue(frame.peaks.all { it == 0f })
        assertEquals(0f, frame.level, 0f)
    }

    @Test
    fun `24 bit pcm is decoded into the expected band`() {
        val analyzer = AudioSpectrumAnalyzer()
        analyzer.flush(48_000, 1, C.ENCODING_PCM_24BIT)
        analyzer.feed(sinePcm24(3_000.0, 48_000))
        var now = System.nanoTime()
        repeat(4) {
            now += 40_000_000L
            analyzer.analyze(now)
        }
        assertPeakNear(analyzer, 3_000.0)
    }

    private fun assertSinePeak(frequency: Double) {
        val analyzer = AudioSpectrumAnalyzer()
        analyzer.flush(48_000, 1, C.ENCODING_PCM_16BIT)
        analyzer.feed(sinePcm16(frequency, 48_000))
        var now = System.nanoTime()
        repeat(4) {
            now += 40_000_000L
            analyzer.analyze(now)
        }
        assertPeakNear(analyzer, frequency)
    }

    private fun analyzedFrame(frequency: Double): AudioSpectrumFrame {
        val analyzer = AudioSpectrumAnalyzer()
        analyzer.flush(48_000, 1, C.ENCODING_PCM_16BIT)
        analyzer.feed(sinePcm16(frequency, 48_000))
        var now = System.nanoTime()
        repeat(6) {
            now += 40_000_000L
            analyzer.analyze(now)
        }
        return analyzer.frame.value
    }

    private fun assertPeakNear(analyzer: AudioSpectrumAnalyzer, frequency: Double) {
        val frame = analyzer.frame.value
        val peakBand = frame.bands.indices.maxByOrNull { frame.bands[it] } ?: -1
        val expectedBand = (0 until AUDIO_SPECTRUM_BAND_COUNT)
            .minByOrNull { abs(analyzer.bandCenterHz(it) - frequency) } ?: -1
        assertTrue(
            "frequency=$frequency expectedBand=$expectedBand peakBand=$peakBand " +
                "peakCenter=${analyzer.bandCenterHz(peakBand)}",
            abs(peakBand - expectedBand) <= 2
        )
    }

    private fun sinePcm16(frequency: Double, sampleRate: Int): ByteBuffer {
        val count = 2_048
        val buffer = ByteBuffer.allocateDirect(count * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (index in 0 until count) {
            val sample = (sin(2.0 * PI * frequency * index / sampleRate) * 16_000.0)
                .roundToInt()
                .toShort()
            buffer.putShort(sample)
        }
        buffer.flip()
        return buffer
    }

    private fun sinePcm24(frequency: Double, sampleRate: Int): ByteBuffer {
        val count = 2_048
        val buffer = ByteBuffer.allocateDirect(count * 3).order(ByteOrder.LITTLE_ENDIAN)
        for (index in 0 until count) {
            val sample = (sin(2.0 * PI * frequency * index / sampleRate) * 2_000_000.0)
                .roundToInt()
            buffer.put((sample and 0xFF).toByte())
            buffer.put((sample shr 8 and 0xFF).toByte())
            buffer.put((sample shr 16 and 0xFF).toByte())
        }
        buffer.flip()
        return buffer
    }
}
