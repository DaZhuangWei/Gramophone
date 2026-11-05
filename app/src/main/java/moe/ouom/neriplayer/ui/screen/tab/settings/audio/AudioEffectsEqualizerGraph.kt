package moe.ouom.neriplayer.ui.screen.tab.settings.audio

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitVerticalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.verticalDrag
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import moe.ouom.neriplayer.core.player.audio.reactive.AUDIO_SPECTRUM_BAND_FREQUENCIES_HZ
import moe.ouom.neriplayer.core.player.audio.reactive.AudioSpectrumFrame
import moe.ouom.neriplayer.data.model.playback.effects.AUDIO_EFFECTS_EQ_BAND_LIMIT_DB
import moe.ouom.neriplayer.data.model.playback.effects.AudioEffectsGraphicBandFrequenciesHz
import moe.ouom.neriplayer.data.model.playback.effects.AudioEffectsSound
import moe.ouom.neriplayer.data.model.playback.effects.effective
import moe.ouom.neriplayer.data.model.playback.effects.responseCurve
import java.util.Locale
import kotlin.math.abs
import kotlin.math.log2
import kotlin.math.roundToInt
import moe.ouom.neriplayer.R as AppR

private const val GRAPH_LEFT_DP = 30f
private const val GRAPH_BOTTOM_DP = 22f
private const val GRAPH_TOP_DP = 18f
internal const val BAND_STEP_DB = 0.5f
private const val HANDLE_TOUCH_RADIUS_DP = 22f
private const val HANDLE_VERTICAL_REACH = 1.6f

private val BandLabels = listOf("31", "62", "125", "250", "500", "1k", "2k", "4k", "8k", "16k")

/** 几何换算独立出来，界面与测试共用同一套坐标 */
internal class EqualizerGraphGeometry(
    private val width: Float,
    private val height: Float,
    private val left: Float,
    private val top: Float,
    private val bottom: Float
) {
    private val bandCount = AudioEffectsGraphicBandFrequenciesHz.size
    private val cell = (width - left) / bandCount
    val plotHeight = (height - top - bottom).coerceAtLeast(1f)
    val plotTop: Float get() = top
    val plotLeft: Float get() = left
    val plotRight: Float get() = width

    fun bandX(index: Int): Float = left + (index + 0.5f) * cell

    fun frequencyX(frequencyHz: Float): Float {
        val position = log2(frequencyHz / AudioEffectsGraphicBandFrequenciesHz.first()) + 0.5f
        return (left + position * cell).coerceIn(left, width)
    }

    fun dbY(db: Float): Float {
        val limit = AUDIO_EFFECTS_EQ_BAND_LIMIT_DB
        val clamped = db.coerceIn(-limit, limit)
        return top + (limit - clamped) / (2f * limit) * plotHeight
    }

    fun yDb(y: Float): Float {
        val limit = AUDIO_EFFECTS_EQ_BAND_LIMIT_DB
        val raw = limit - (y - top) / plotHeight * 2f * limit
        return snapToStep(raw.coerceIn(-limit, limit), BAND_STEP_DB)
    }

    fun nearestBand(x: Float): Int = ((x - left) / cell).toInt().coerceIn(0, bandCount - 1)

    /** 只有按在某个圆点附近才算抓住它，避免滑动页面时误改频段 */
    fun handleAt(x: Float, y: Float, bands: List<Float>, radius: Float): Int? {
        val band = nearestBand(x)
        val gain = bands.getOrNull(band) ?: return null
        val hit = abs(x - bandX(band)) <= radius && abs(y - dbY(gain)) <= radius * HANDLE_VERTICAL_REACH
        return band.takeIf { hit }
    }

    /** 拖动按手指位移累加增益，按下时圆点不会跳到手指位置 */
    fun dragGain(startGain: Float, upwardPx: Float): Float {
        val limit = AUDIO_EFFECTS_EQ_BAND_LIMIT_DB
        val raw = startGain + upwardPx / plotHeight * 2f * limit
        return snapToStep(raw.coerceIn(-limit, limit), BAND_STEP_DB)
    }

    val plotBottom: Float get() = top + plotHeight
}

@Composable
internal fun AudioEffectsEqualizerGraph(
    sound: AudioEffectsSound,
    selectedBand: Int,
    spectrum: State<AudioSpectrumFrame>,
    drySpectrum: State<AudioSpectrumFrame>,
    compareWetSpectrum: State<AudioSpectrumFrame>,
    compareMode: Boolean,
    onSelectBand: (Int) -> Unit,
    onBandChange: (index: Int, gainDb: Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val primary = MaterialTheme.colorScheme.primary
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val handleFill = MaterialTheme.colorScheme.surface
    val textMeasurer = rememberTextMeasurer()
    val liveLabel = stringResource(AppR.string.audio_effects_spectrum_live)
    val waitingLabel = stringResource(AppR.string.audio_effects_spectrum_waiting)
    val lowLabel = stringResource(AppR.string.audio_effects_spectrum_low)
    val midLabel = stringResource(AppR.string.audio_effects_spectrum_mid)
    val highLabel = stringResource(AppR.string.audio_effects_spectrum_high)
    val compareLiveLabel = stringResource(AppR.string.audio_effects_spectrum_view_compare)
    val compareStageLabel = stringResource(AppR.string.audio_effects_spectrum_compare_stage)
    var draggingBand by remember { mutableIntStateOf(-1) }
    val latestBands by rememberUpdatedState(sound.equalizerBandsDb)
    val latestOnSelect by rememberUpdatedState(onSelectBand)
    val latestOnBandChange by rememberUpdatedState(onBandChange)
    val curve = remember(sound) { sound.copy(equalizerEnabled = true).effective().responseCurve(points = 96) }
    val bands = sound.equalizerBandsDb
    val enabled = sound.equalizerEnabled
    val interactive = LocalAudioEffectsSectionEnabled.current
    val wetDisplay = rememberSpectrumDisplay(if (compareMode) compareWetSpectrum else spectrum)
    val dryDisplay = rememberSpectrumDisplay(drySpectrum)
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(220.dp)
            .padding(horizontal = 12.dp)
            .pointerInput(interactive) {
                if (!interactive) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val geometry = geometry(size.width.toFloat(), size.height.toFloat())
                    val handle = geometry.handleAt(down.position.x, down.position.y, latestBands, HANDLE_TOUCH_RADIUS_DP.dp.toPx())
                    if (handle == null) {
                        val up = waitForUpOrCancellation() ?: return@awaitEachGesture
                        latestOnSelect(geometry.nearestBand(up.position.x))
                        return@awaitEachGesture
                    }
                    latestOnSelect(handle)
                    val startGain = latestBands.getOrElse(handle) { 0f }
                    val slop = awaitVerticalTouchSlopOrCancellation(down.id) { change, _ -> change.consume() }
                        ?: return@awaitEachGesture
                    draggingBand = handle
                    latestOnBandChange(handle, geometry.dragGain(startGain, down.position.y - slop.position.y))
                    verticalDrag(slop.id) { change ->
                        change.consume()
                        latestOnBandChange(handle, geometry.dragGain(startGain, down.position.y - change.position.y))
                    }
                    draggingBand = -1
                }
            }
    ) {
        wetDisplay.frameTick.longValue
        if (compareMode) dryDisplay.frameTick.longValue
        val geometry = geometry(size.width, size.height)
        val compareLevel = if (compareMode) maxOf(wetDisplay.level, dryDisplay.level) else wetDisplay.level
        if (compareMode) {
            drawCompareSpectrum(
                dryBands = dryDisplay.bands,
                wetBands = wetDisplay.bands,
                level = compareLevel,
                geometry = geometry,
                primary = primary,
                labelColor = labelColor
            )
        } else {
            drawSpectrumRanges(sound, geometry, primary)
            drawSpectrumBars(
                bands = wetDisplay.bands,
                peaks = wetDisplay.peaks,
                level = wetDisplay.level,
                geometry = geometry,
                primary = primary,
                enabled = enabled
            )
        }
        drawGrid(geometry, gridColor, labelColor, textMeasurer)
        if (!compareMode) {
            drawSpectrumNeedle(
                level = wetDisplay.level,
                centroidHz = wetDisplay.centroidHz,
                geometry = geometry,
                primary = primary
            )
        }
        if (selectedBand in bands.indices) {
            val x = geometry.bandX(selectedBand)
            drawLine(
                color = primary.copy(alpha = 0.12f),
                start = Offset(x, GRAPH_TOP_DP.dp.toPx()),
                end = Offset(x, geometry.plotBottom),
                strokeWidth = 14.dp.toPx()
            )
        }
        drawResponse(geometry, curve, primary.copy(alpha = if (enabled) 1f else 0.4f))
        bands.forEachIndexed { index, gain ->
            val center = Offset(geometry.bandX(index), geometry.dbY(gain))
            val active = index == draggingBand
            val selected = index == selectedBand
            val radius = if (active) 11.dp.toPx() else 8.dp.toPx()
            drawCircle(color = if (selected) primary else handleFill, radius = radius, center = center)
            drawCircle(color = primary, radius = radius, center = center, style = Stroke(width = 2.5.dp.toPx()))
            if (active || selected) {
                drawLabel(textMeasurer, formatSignedDb(gain), Offset(center.x, center.y - radius - 14.dp.toPx()), primary)
            }
        }
        drawSpectrumStatus(
            level = compareLevel,
            centroidHz = wetDisplay.centroidHz,
            compareMode = compareMode,
            compareLiveLabel = compareLiveLabel,
            compareStageLabel = compareStageLabel,
            geometry = geometry,
            textMeasurer = textMeasurer,
            primary = primary,
            labelColor = labelColor,
            liveLabel = liveLabel,
            waitingLabel = waitingLabel,
            lowLabel = lowLabel,
            midLabel = midLabel,
            highLabel = highLabel
        )
    }
}

private fun androidx.compose.ui.unit.Density.geometry(width: Float, height: Float) = EqualizerGraphGeometry(
    width = width,
    height = height,
    left = GRAPH_LEFT_DP.dp.toPx(),
    top = GRAPH_TOP_DP.dp.toPx(),
    bottom = GRAPH_BOTTOM_DP.dp.toPx()
)

private fun DrawScope.drawGrid(
    geometry: EqualizerGraphGeometry,
    gridColor: androidx.compose.ui.graphics.Color,
    labelColor: androidx.compose.ui.graphics.Color,
    textMeasurer: TextMeasurer
) {
    val dashed = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))
    listOf(12f, 6f, 0f, -6f, -12f).forEach { db ->
        val y = geometry.dbY(db)
        drawLine(
            color = gridColor.copy(alpha = if (db == 0f) 0.9f else 0.45f),
            start = Offset(GRAPH_LEFT_DP.dp.toPx(), y),
            end = Offset(size.width, y),
            strokeWidth = if (db == 0f) 1.5.dp.toPx() else 1.dp.toPx(),
            pathEffect = if (db == 0f) null else dashed
        )
        val label = if (db > 0f) "+${db.toInt()}" else db.toInt().toString()
        drawLabel(textMeasurer, label, Offset(GRAPH_LEFT_DP.dp.toPx() / 2f, y), labelColor)
    }
    BandLabels.forEachIndexed { index, label ->
        drawLabel(textMeasurer, label, Offset(geometry.bandX(index), size.height - 8.dp.toPx()), labelColor)
    }
}

private fun DrawScope.drawResponse(
    geometry: EqualizerGraphGeometry,
    curve: List<Pair<Float, Float>>,
    color: androidx.compose.ui.graphics.Color
) {
    if (curve.isEmpty()) return
    val line = Path()
    val fill = Path()
    curve.forEachIndexed { index, (frequency, gain) ->
        val x = geometry.frequencyX(frequency)
        val y = geometry.dbY(gain)
        if (index == 0) {
            line.moveTo(x, y)
            fill.moveTo(x, geometry.dbY(0f))
            fill.lineTo(x, y)
        } else {
            line.lineTo(x, y)
            fill.lineTo(x, y)
        }
    }
    fill.lineTo(geometry.frequencyX(curve.last().first), geometry.dbY(0f))
    fill.close()
    drawPath(
        path = fill,
        brush = Brush.verticalGradient(
            colors = listOf(color.copy(alpha = 0.28f), color.copy(alpha = 0.04f)),
            startY = 0f,
            endY = geometry.plotBottom
        )
    )
    drawPath(path = line, color = color, style = Stroke(width = 2.5.dp.toPx()))
}

private fun DrawScope.drawLabel(
    textMeasurer: TextMeasurer,
    text: String,
    center: Offset,
    color: androidx.compose.ui.graphics.Color
) {
    val layout = textMeasurer.measure(text, style = TextStyle(fontSize = 10.sp, color = color))
    drawText(
        textLayoutResult = layout,
        topLeft = Offset(center.x - layout.size.width / 2f, center.y - layout.size.height / 2f)
    )
}

private val SpectrumCutColor = Color(0xFF6EAABE)
private val SpectrumHighColor = Color(0xFF5FC9BB)
private val SpectrumBoostDeltaColor = Color(0xFFFFD68C)

private fun DrawScope.drawSpectrumBars(
    bands: FloatArray,
    peaks: FloatArray,
    level: Float,
    geometry: EqualizerGraphGeometry,
    primary: Color,
    enabled: Boolean
) {
    if (level < 0.002f) return
    val alphaScale = if (enabled) 1f else 0.55f
    val brush = Brush.horizontalGradient(
        colors = listOf(
            primary.copy(alpha = 0.42f * alphaScale),
            primary.copy(alpha = 0.22f * alphaScale),
            SpectrumHighColor.copy(alpha = 0.38f * alphaScale)
        ),
        startX = geometry.plotLeft,
        endX = size.width
    )
    val barWidth = 2.dp.toPx()
    val bottom = geometry.plotBottom
    val peakColor = primary.copy(alpha = 0.55f * alphaScale)
    bands.forEachIndexed { index, value ->
        val frequency = AUDIO_SPECTRUM_BAND_FREQUENCIES_HZ.getOrNull(index) ?: return@forEachIndexed
        if (frequency > 16_000f) return@forEachIndexed
        val x = geometry.frequencyX(frequency)
        val barHeight = value.coerceIn(0f, 1f) * geometry.plotHeight
        if (barHeight > 1f) {
            drawRoundRect(
                brush = brush,
                topLeft = Offset(x - barWidth / 2f, bottom - barHeight),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(barWidth / 2f)
            )
        }
        val peak = peaks.getOrNull(index)?.coerceIn(0f, 1f) ?: 0f
        if (peak > 0.01f) {
            val y = bottom - peak * geometry.plotHeight
            drawLine(
                color = peakColor,
                start = Offset(x - barWidth / 2f, y),
                end = Offset(x + barWidth / 2f, y),
                strokeWidth = 1.dp.toPx()
            )
        }
    }
}

private fun DrawScope.drawCompareSpectrum(
    dryBands: FloatArray,
    wetBands: FloatArray,
    level: Float,
    geometry: EqualizerGraphGeometry,
    primary: Color,
    labelColor: Color
) {
    if (level < 0.002f) return
    val plotLeft = geometry.plotLeft
    val plotRight = geometry.plotRight
    val plotBottom = geometry.plotBottom
    val plotHeight = geometry.plotHeight
    val dryFill = labelColor.copy(alpha = 0.075f)
    val dryLine = labelColor.copy(alpha = 0.62f)
    val barCount = 40
    val barWidth = 4.dp.toPx()
    val lastIndex = AUDIO_SPECTRUM_BAND_FREQUENCIES_HZ.lastIndex

    fun dryAt(index: Int): Float {
        val left = dryBands.getOrNull((index - 1).coerceAtLeast(0)) ?: 0f
        val center = dryBands.getOrNull(index) ?: 0f
        val right = dryBands.getOrNull((index + 1).coerceAtMost(dryBands.lastIndex)) ?: 0f
        return ((left + center * 2f + right) / 4f).coerceIn(0f, 1f)
    }

    val dryPath = Path()
    var dryStarted = false
    var firstX = 0f
    var lastX = 0f
    AUDIO_SPECTRUM_BAND_FREQUENCIES_HZ.forEachIndexed { index, frequency ->
        if (frequency > 16_000f) return@forEachIndexed
        val x = geometry.frequencyX(frequency)
        val y = plotBottom - dryAt(index) * plotHeight
        if (!dryStarted) {
            dryPath.moveTo(x, y)
            firstX = x
            dryStarted = true
        } else {
            dryPath.lineTo(x, y)
        }
        lastX = x
    }
    if (dryStarted) {
        dryPath.lineTo(lastX, plotBottom)
        dryPath.lineTo(firstX, plotBottom)
        dryPath.close()
        drawPath(path = dryPath, color = dryFill)
    }

    val wetBrush = Brush.horizontalGradient(
        colors = listOf(
            primary.copy(alpha = 0.85f),
            primary.copy(alpha = 0.58f),
            SpectrumHighColor.copy(alpha = 0.82f)
        ),
        startX = plotLeft,
        endX = plotRight
    )
    val sampledIndices = IntArray(barCount)
    for (bar in 0 until barCount) {
        val index = (bar * lastIndex / (barCount - 1f)).roundToInt()
        sampledIndices[bar] = index
        val frequency = AUDIO_SPECTRUM_BAND_FREQUENCIES_HZ[index]
        if (frequency > 16_000f) continue
        val x = geometry.frequencyX(frequency)
        val height = wetBands.getOrNull(index)?.coerceIn(0f, 1f)?.times(plotHeight) ?: 0f
        if (height < 1f) continue
        drawRoundRect(
            brush = wetBrush,
            topLeft = Offset(x - barWidth / 2f, plotBottom - height),
            size = Size(barWidth, height),
            cornerRadius = CornerRadius(barWidth / 2f)
        )
    }

    for (bar in 0 until barCount) {
        val index = sampledIndices[bar]
        val frequency = AUDIO_SPECTRUM_BAND_FREQUENCIES_HZ[index]
        if (frequency > 16_000f) continue
        val x = geometry.frequencyX(frequency)
        val dryHeight = dryAt(index) * plotHeight
        val wetHeight = (wetBands.getOrNull(index) ?: 0f).coerceIn(0f, 1f) * plotHeight
        val diff = wetHeight - dryHeight
        if (abs(diff) < 1.5f) continue
        drawRect(
            color = if (diff > 0f) {
                SpectrumBoostDeltaColor.copy(alpha = 0.55f)
            } else {
                SpectrumCutColor.copy(alpha = 0.42f)
            },
            topLeft = Offset(x - barWidth / 2f, plotBottom - maxOf(dryHeight, wetHeight)),
            size = Size(barWidth, abs(diff))
        )
    }

    val dryLinePath = Path()
    var lineStarted = false
    AUDIO_SPECTRUM_BAND_FREQUENCIES_HZ.forEachIndexed { index, frequency ->
        if (frequency > 16_000f) return@forEachIndexed
        val x = geometry.frequencyX(frequency)
        val y = plotBottom - dryAt(index) * plotHeight
        if (!lineStarted) {
            dryLinePath.moveTo(x, y)
            lineStarted = true
        } else {
            dryLinePath.lineTo(x, y)
        }
    }
    drawPath(
        path = dryLinePath,
        color = dryLine,
        style = Stroke(
            width = 1.4.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(3f, 4f))
        )
    )
}

private fun DrawScope.drawSpectrumRanges(
    sound: AudioEffectsSound,
    geometry: EqualizerGraphGeometry,
    primary: Color
) {
    drawSpectrumRange(geometry, 20f, sound.bassFrequencyHz, sound.bassDb, primary)
    drawSpectrumRange(geometry, sound.trebleFrequencyHz, 20_000f, sound.trebleDb, SpectrumHighColor)
}

private fun DrawScope.drawSpectrumRange(
    geometry: EqualizerGraphGeometry,
    fromHz: Float,
    toHz: Float,
    gainDb: Float,
    boostColor: Color
) {
    if (abs(gainDb) < 0.5f) return
    val x1 = geometry.frequencyX(fromHz)
    val x2 = geometry.frequencyX(toHz)
    if (x2 <= x1) return
    val strength = (abs(gainDb) / 12f).coerceIn(0f, 1f)
    val color = if (gainDb >= 0f) boostColor else SpectrumCutColor
    val alpha = 0.035f + 0.075f * strength
    drawRect(
        color = color.copy(alpha = alpha),
        topLeft = Offset(x1, geometry.plotTop),
        size = Size(x2 - x1, geometry.plotHeight)
    )
    drawLine(
        color = color.copy(alpha = (alpha * 3f).coerceAtMost(0.5f)),
        start = Offset(x1, geometry.plotTop + 0.5f),
        end = Offset(x2, geometry.plotTop + 0.5f),
        strokeWidth = 1.dp.toPx()
    )
}

private fun DrawScope.drawSpectrumNeedle(
    level: Float,
    centroidHz: Float,
    geometry: EqualizerGraphGeometry,
    primary: Color
) {
    if (level < 0.002f) return
    val x = geometry.frequencyX(centroidHz)
    val top = geometry.plotTop + 8.dp.toPx()
    drawLine(
        color = primary.copy(alpha = 0.22f),
        start = Offset(x, top),
        end = Offset(x, geometry.plotBottom),
        strokeWidth = 1.dp.toPx(),
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(3f, 5f))
    )
    val half = 3.5.dp.toPx()
    val mid = geometry.plotTop + 7.dp.toPx()
    val diamond = Path().apply {
        moveTo(x, geometry.plotTop + 1.dp.toPx())
        lineTo(x + half, mid)
        lineTo(x, geometry.plotTop + 13.dp.toPx())
        lineTo(x - half, mid)
        close()
    }
    drawPath(path = diamond, color = primary.copy(alpha = 0.85f))
}

private fun DrawScope.drawSpectrumStatus(
    level: Float,
    centroidHz: Float,
    compareMode: Boolean,
    compareLiveLabel: String,
    compareStageLabel: String,
    geometry: EqualizerGraphGeometry,
    textMeasurer: TextMeasurer,
    primary: Color,
    labelColor: Color,
    liveLabel: String,
    waitingLabel: String,
    lowLabel: String,
    midLabel: String,
    highLabel: String
) {
    val centerY = 8.dp.toPx()
    val active = level >= 0.002f
    val color = if (active) primary else labelColor.copy(alpha = 0.55f)
    drawCircle(
        color = color,
        radius = 2.dp.toPx(),
        center = Offset(geometry.plotLeft + 2.dp.toPx(), centerY)
    )
    drawSmallText(
        textMeasurer = textMeasurer,
        text = when {
            compareMode -> compareLiveLabel
            active -> liveLabel
            else -> waitingLabel
        },
        x = geometry.plotLeft + 8.dp.toPx(),
        centerY = centerY,
        color = color
    )
    if (compareMode) {
        val layout = textMeasurer.measure(
            compareStageLabel,
            style = TextStyle(fontSize = 9.sp, color = labelColor)
        )
        drawText(
            textLayoutResult = layout,
            topLeft = Offset(
                size.width - 10.dp.toPx() - layout.size.width,
                centerY - layout.size.height / 2f
            )
        )
        return
    }
    if (!active) return
    val band = when {
        centroidHz < 250f -> lowLabel
        centroidHz < 2_000f -> midLabel
        else -> highLabel
    }
    val text = "$band ${formatSpectrumFrequency(centroidHz)}"
    val layout = textMeasurer.measure(text, style = TextStyle(fontSize = 9.sp, color = labelColor))
    drawText(
        textLayoutResult = layout,
        topLeft = Offset(
            size.width - 10.dp.toPx() - layout.size.width,
            centerY - layout.size.height / 2f
        )
    )
}

private fun DrawScope.drawSmallText(
    textMeasurer: TextMeasurer,
    text: String,
    x: Float,
    centerY: Float,
    color: Color
) {
    val layout = textMeasurer.measure(text, style = TextStyle(fontSize = 9.sp, color = color))
    drawText(
        textLayoutResult = layout,
        topLeft = Offset(x, centerY - layout.size.height / 2f)
    )
}

private fun formatSpectrumFrequency(hz: Float): String {
    if (hz < 1_000f) return "${hz.roundToInt()} Hz"
    val khz = hz / 1_000f
    return if (abs(khz - khz.roundToInt()) < 0.05f) {
        "${khz.roundToInt()} kHz"
    } else {
        String.format(Locale.US, "%.1f kHz", khz)
    }
}
