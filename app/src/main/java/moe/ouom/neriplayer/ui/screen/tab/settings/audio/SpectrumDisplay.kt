package moe.ouom.neriplayer.ui.screen.tab.settings.audio

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import moe.ouom.neriplayer.core.player.audio.reactive.AUDIO_SPECTRUM_BAND_COUNT
import moe.ouom.neriplayer.core.player.audio.reactive.AudioSpectrumFrame
import kotlin.math.abs
import kotlin.math.exp

/**
 * 显示层频谱：FFT 仍按 60Hz 更新，但显示值在每个屏幕帧向最新目标平滑靠近。
 * 60Hz 屏幕上没有额外收益；120Hz 屏幕上会把两帧之间的运动补出来。
 */
internal class SpectrumDisplay(bandCount: Int = AUDIO_SPECTRUM_BAND_COUNT) {
    val bands = FloatArray(bandCount)
    val peaks = FloatArray(bandCount)
    val frameTick = mutableLongStateOf(0L)

    var centroidHz = 1_000f
        private set
    var level = 0f
        private set

    fun update(frame: AudioSpectrumFrame, dtSeconds: Float): Boolean {
        val dt = dtSeconds.coerceIn(0.001f, 0.05f)
        val bandCoefficient = 1f - exp(-dt / 0.012f)
        val peakCoefficient = 1f - exp(-dt / 0.020f)
        val centroidCoefficient = 1f - exp(-dt / 0.030f)
        val levelCoefficient = 1f - exp(-dt / 0.020f)
        var dirty = false

        val count = minOf(bands.size, frame.bands.size, frame.peaks.size)
        for (index in 0 until count) {
            val targetBand = frame.bands[index].coerceIn(0f, 1f)
            val nextBand = bands[index] + (targetBand - bands[index]) * bandCoefficient
            if (abs(nextBand - bands[index]) > 1e-4f) dirty = true
            bands[index] = nextBand

            val targetPeak = frame.peaks[index].coerceIn(0f, 1f)
            val nextPeak = peaks[index] + (targetPeak - peaks[index]) * peakCoefficient
            if (abs(nextPeak - peaks[index]) > 1e-4f) dirty = true
            peaks[index] = nextPeak
        }

        val nextCentroid = centroidHz + (frame.centroidHz - centroidHz) * centroidCoefficient
        if (abs(nextCentroid - centroidHz) > 0.05f) dirty = true
        centroidHz = nextCentroid

        val nextLevel = level + (frame.level - level) * levelCoefficient
        if (abs(nextLevel - level) > 1e-4f) dirty = true
        level = nextLevel

        if (!dirty) {
            var quiet = frame.level < 0.0005f
            for (index in 0 until count) {
                if (bands[index] < 0.0002f) bands[index] = 0f else quiet = false
                if (peaks[index] < 0.0002f) peaks[index] = 0f else quiet = false
            }
            if (quiet) level = 0f
        }
        return dirty
    }
}

@Composable
internal fun rememberSpectrumDisplay(
    spectrum: State<AudioSpectrumFrame>,
    bandCount: Int = AUDIO_SPECTRUM_BAND_COUNT
): SpectrumDisplay {
    val display = remember(bandCount) { SpectrumDisplay(bandCount) }
    LaunchedEffect(display, spectrum) {
        var lastNanos = 0L
        snapshotFlow { spectrum.value }.collectLatest { frame ->
            // 只在“有新目标”时请求显示帧；空闲时不再占着 120Hz 时钟
            while (isActive) {
                var dirty = false
                withFrameNanos { now ->
                    val dt = if (lastNanos == 0L) {
                        0.016f
                    } else {
                        ((now - lastNanos) / 1_000_000_000.0).toFloat().coerceIn(0.001f, 0.05f)
                    }
                    lastNanos = now
                    dirty = display.update(frame, dt)
                    if (dirty) display.frameTick.longValue = now
                }
                if (!dirty) break
            }
        }
    }
    return display
}
