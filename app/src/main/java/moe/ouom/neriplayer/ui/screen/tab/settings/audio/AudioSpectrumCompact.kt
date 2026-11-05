package moe.ouom.neriplayer.ui.screen.tab.settings.audio

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import moe.ouom.neriplayer.core.player.audio.reactive.AUDIO_SPECTRUM_BAND_FREQUENCIES_HZ
import moe.ouom.neriplayer.core.player.audio.reactive.AudioSpectrumFrame
import moe.ouom.neriplayer.data.model.playback.effects.AudioEffectsSound
import kotlin.math.abs
import kotlin.math.log2
import kotlin.math.roundToInt
import moe.ouom.neriplayer.R as AppR

private val ToneSpectrumCutColor = Color(0xFF6EAABE)
private val ToneSpectrumHighColor = Color(0xFF5FC9BB)

/**
 * 音色页的紧凑拾音器：低频/高频效果区 + 实时频谱 + 能量重心。
 * 频谱 State 只在 draw 阶段读取，避免 60fps 触发整页重组。
 */
@Composable
internal fun AudioEffectsToneSpectrum(
    sound: AudioEffectsSound,
    spectrum: State<AudioSpectrumFrame>,
    modifier: Modifier = Modifier
) {
    val primary = MaterialTheme.colorScheme.primary
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val surfaceColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.20f)
    val active = LocalAudioEffectsSectionEnabled.current
    val textMeasurer = rememberTextMeasurer()
    val spectrumDisplay = rememberSpectrumDisplay(spectrum)
    val lowLabel = stringResource(AppR.string.audio_effects_spectrum_low)
    val highLabel = stringResource(AppR.string.audio_effects_spectrum_high)
    val waitingLabel = stringResource(AppR.string.audio_effects_spectrum_waiting)

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "$lowLabel ${formatSignedDb(sound.bassDb)}",
                color = if (sound.bassDb >= 0f) primary else ToneSpectrumCutColor,
                style = MaterialTheme.typography.labelMedium
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = "$highLabel ${formatSignedDb(sound.trebleDb)}",
                color = if (sound.trebleDb >= 0f) ToneSpectrumHighColor else ToneSpectrumCutColor,
                style = MaterialTheme.typography.labelMedium
            )
        }

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(86.dp)
                .padding(top = 6.dp)
        ) {
            spectrumDisplay.frameTick.longValue
            drawRoundRect(
                color = surfaceColor,
                cornerRadius = CornerRadius(14.dp.toPx())
            )

            val left = 10.dp.toPx()
            val right = size.width - 10.dp.toPx()
            val top = 9.dp.toPx()
            val bottom = size.height - 18.dp.toPx()
            val plotWidth = (right - left).coerceAtLeast(1f)
            val plotHeight = (bottom - top).coerceAtLeast(1f)
            val alphaScale = if (active) 1f else 0.42f
            fun xFor(hz: Float): Float {
                val ratio = (log2(hz / 20f) / log2(1_000f)).coerceIn(0f, 1f)
                return left + ratio * plotWidth
            }

            drawToneGrid(gridColor, left, right, top, bottom)

            val bassX = xFor(sound.bassFrequencyHz)
            val trebleX = xFor(sound.trebleFrequencyHz)
            drawToneRange(
                x1 = left,
                x2 = bassX,
                top = top,
                height = plotHeight,
                gainDb = sound.bassDb,
                color = primary,
                alphaScale = alphaScale
            )
            drawToneRange(
                x1 = trebleX,
                x2 = right,
                top = top,
                height = plotHeight,
                gainDb = sound.trebleDb,
                color = ToneSpectrumHighColor,
                alphaScale = alphaScale
            )

            val barBrush = Brush.horizontalGradient(
                colors = listOf(
                    primary.copy(alpha = 0.78f * alphaScale),
                    primary.copy(alpha = 0.48f * alphaScale),
                    ToneSpectrumHighColor.copy(alpha = 0.66f * alphaScale)
                ),
                startX = left,
                endX = right
            )
            val toneBarCount = 40
            val barWidth = plotWidth / toneBarCount * 0.58f
            val lastIndex = AUDIO_SPECTRUM_BAND_FREQUENCIES_HZ.lastIndex
            for (bar in 0 until toneBarCount) {
                val index = (bar * lastIndex / (toneBarCount - 1f)).roundToInt()
                val frequency = AUDIO_SPECTRUM_BAND_FREQUENCIES_HZ[index]
                val barHeight = spectrumDisplay.bands.getOrNull(index)?.coerceIn(0f, 1f)?.times(plotHeight) ?: 0f
                if (barHeight <= 1f) continue
                val x = xFor(frequency) - barWidth / 2f
                drawRoundRect(
                    brush = barBrush,
                    topLeft = Offset(x, bottom - barHeight),
                    size = Size(barWidth, barHeight),
                    cornerRadius = CornerRadius(barWidth / 2f)
                )
                val peak = spectrumDisplay.peaks.getOrNull(index)?.coerceIn(0f, 1f) ?: 0f
                if (peak > 0.01f) {
                    val y = bottom - peak * plotHeight
                    drawLine(
                        color = primary.copy(alpha = 0.5f * alphaScale),
                        start = Offset(x, y),
                        end = Offset(x + barWidth, y),
                        strokeWidth = 1.dp.toPx()
                    )
                }
            }

            val rangeY = bottom + 1.dp.toPx()
            if (abs(sound.bassDb) >= 0.5f) {
                drawRangeBar(
                    x1 = left,
                    x2 = bassX,
                    y = rangeY,
                    color = if (sound.bassDb >= 0f) primary else ToneSpectrumCutColor,
                    strength = abs(sound.bassDb) / 12f,
                    alphaScale = alphaScale
                )
            }
            if (abs(sound.trebleDb) >= 0.5f) {
                drawRangeBar(
                    x1 = trebleX,
                    x2 = right,
                    y = rangeY,
                    color = if (sound.trebleDb >= 0f) ToneSpectrumHighColor else ToneSpectrumCutColor,
                    strength = abs(sound.trebleDb) / 12f,
                    alphaScale = alphaScale
                )
            }

            if (spectrumDisplay.level >= 0.002f) {
                val centroidX = xFor(spectrumDisplay.centroidHz)
                drawCircle(
                    color = primary.copy(alpha = 0.9f * alphaScale),
                    radius = 2.5.dp.toPx(),
                    center = Offset(centroidX, top + 5.dp.toPx())
                )
            }

            val tickStyle = TextStyle(fontSize = 8.sp, color = labelColor.copy(alpha = 0.72f))
            listOf(20f to "20", 100f to "100", 1_000f to "1k", 10_000f to "10k", 20_000f to "20k")
                .forEach { (frequency, label) ->
                    val layout = textMeasurer.measure(label, style = tickStyle)
                    drawText(
                        textLayoutResult = layout,
                        topLeft = Offset(xFor(frequency) - layout.size.width / 2f, size.height - 10.dp.toPx())
                    )
                }

            if (spectrumDisplay.level < 0.002f) {
                val layout = textMeasurer.measure(waitingLabel, style = TextStyle(fontSize = 9.sp, color = labelColor.copy(alpha = 0.55f)))
                drawText(
                    textLayoutResult = layout,
                    topLeft = Offset(
                        left + (plotWidth - layout.size.width) / 2f,
                        top + (plotHeight - layout.size.height) / 2f
                    )
                )
            }
        }
    }
}

private fun DrawScope.drawToneGrid(
    gridColor: Color,
    left: Float,
    right: Float,
    top: Float,
    bottom: Float
) {
    listOf(100, 1_000, 10_000).forEach { frequency ->
        val ratio = (log2(frequency / 20.0) / log2(1_000.0)).toFloat().coerceIn(0f, 1f)
        val x = left + ratio * (right - left)
        drawLine(
            color = gridColor.copy(alpha = 0.35f),
            start = Offset(x, top),
            end = Offset(x, bottom),
            strokeWidth = 1.dp.toPx()
        )
    }
    // 底部基线
    drawLine(
        color = gridColor.copy(alpha = 0.45f),
        start = Offset(left, bottom),
        end = Offset(right, bottom),
        strokeWidth = 1.dp.toPx()
    )
}

private fun DrawScope.drawToneRange(
    x1: Float,
    x2: Float,
    top: Float,
    height: Float,
    gainDb: Float,
    color: Color,
    alphaScale: Float
) {
    if (abs(gainDb) < 0.5f || x2 <= x1) return
    val strength = (abs(gainDb) / 12f).coerceIn(0f, 1f)
    val rangeColor = if (gainDb >= 0f) color else ToneSpectrumCutColor
    val brush = Brush.verticalGradient(
        colors = listOf(
            rangeColor.copy(alpha = (0.02f + 0.04f * strength) * alphaScale),
            rangeColor.copy(alpha = (0.07f + 0.13f * strength) * alphaScale)
        ),
        startY = top,
        endY = top + height
    )
    drawRect(
        brush = brush,
        topLeft = Offset(x1, top),
        size = Size(x2 - x1, height)
    )
}

private fun DrawScope.drawRangeBar(
    x1: Float,
    x2: Float,
    y: Float,
    color: Color,
    strength: Float,
    alphaScale: Float
) {
    if (x2 <= x1) return
    val alpha = (0.30f + 0.35f * strength.coerceIn(0f, 1f)) * alphaScale
    drawRoundRect(
        color = color.copy(alpha = alpha),
        topLeft = Offset(x1, y),
        size = Size(x2 - x1, 5.dp.toPx()),
        cornerRadius = CornerRadius(2.5.dp.toPx())
    )
    drawLine(
        color = color.copy(alpha = (alpha + 0.18f).coerceAtMost(0.9f)),
        start = Offset(x1, y - 2.dp.toPx()),
        end = Offset(x1, y + 7.dp.toPx()),
        strokeWidth = 1.5.dp.toPx()
    )
    drawLine(
        color = color.copy(alpha = (alpha + 0.18f).coerceAtMost(0.9f)),
        start = Offset(x2, y - 2.dp.toPx()),
        end = Offset(x2, y + 7.dp.toPx()),
        strokeWidth = 1.5.dp.toPx()
    )
}
