package moe.ouom.neriplayer.core.player.audio.reactive

import androidx.media3.common.C
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

const val AUDIO_SPECTRUM_BAND_COUNT = 56

/** 显示用频段中心，固定 25 Hz - 20 kHz 对数分布；与采样率无关 */
val AUDIO_SPECTRUM_BAND_FREQUENCIES_HZ: FloatArray = FloatArray(AUDIO_SPECTRUM_BAND_COUNT) { index ->
    val ratio = AUDIO_SPECTRUM_MAX_FREQUENCY_HZ / AUDIO_SPECTRUM_MIN_FREQUENCY_HZ
    (AUDIO_SPECTRUM_MIN_FREQUENCY_HZ * ratio.pow((index + 0.5) / AUDIO_SPECTRUM_BAND_COUNT)).toFloat()
}

private const val AUDIO_SPECTRUM_MIN_FREQUENCY_HZ = 25.0
private const val AUDIO_SPECTRUM_MAX_FREQUENCY_HZ = 20_000.0
private const val FFT_SIZE = 2048
private const val RING_SIZE = 16_384
private const val RING_MASK = 16_383L
private const val TARGET_SAMPLE_RATE = 48_000
private const val DB_FLOOR = -82.0
private const val DB_CEILING = -6.0
private const val ATTACK_SECONDS = 0.045
private const val RELEASE_SECONDS = 0.22
private const val PEAK_HOLD_SECONDS = 0.45f
private const val PEAK_FALL_PER_SECOND = 0.38f
private const val SILENCE_AFTER_NS = 500_000_000L
private const val IDLE_EMIT_INTERVAL_NS = 100_000_000L

/**
 * 频谱结果：bands/peaks 为 0..1 的显示值，centroidHz 是线性功率加权重心。
 * 所有字段都在分析线程生成，UI 只读。
 */
data class AudioSpectrumFrame(
    val bands: FloatArray,
    val peaks: FloatArray,
    val centroidHz: Float,
    val level: Float,
    val sampleRate: Int,
    val version: Long
)

/**
 * 从 PCM 采样计算实时频谱。音频线程只调用 [feed]，FFT 由 [analyze] 在独立线程执行。
 * 不做 Android/Compose 依赖，便于单元测试。
 */
class AudioSpectrumAnalyzer {
    private val _frame = MutableStateFlow(emptyFrame(44_100))
    val frame: StateFlow<AudioSpectrumFrame> = _frame.asStateFlow()

    private val ring = FloatArray(RING_SIZE)
    @Volatile
    private var ringWrite = 0L
    @Volatile
    private var lastFeedNs = 0L
    @Volatile
    private var inputSampleRate = 44_100
    @Volatile
    private var decimatedSampleRate = 44_100
    @Volatile
    private var decimationFactor = 1
    @Volatile
    private var formatChannels = 2
    @Volatile
    private var formatEncoding = C.ENCODING_PCM_16BIT

    private var accumulator = 0f
    private var accumulatorCount = 0

    private val window = FloatArray(FFT_SIZE) { index ->
        (0.5 - 0.5 * cos(2.0 * PI * index / (FFT_SIZE - 1))).toFloat()
    }
    private val coherentGain = (window.sum() / FFT_SIZE).coerceAtLeast(1e-6f)
    private val fftRe = FloatArray(FFT_SIZE)
    private val fftIm = FloatArray(FFT_SIZE)
    private val bitReverse = IntArray(FFT_SIZE)
    private val cosTable = FloatArray(FFT_SIZE / 2) { index ->
        cos(2.0 * PI * index / FFT_SIZE).toFloat()
    }
    private val sinTable = FloatArray(FFT_SIZE / 2) { index ->
        sin(2.0 * PI * index / FFT_SIZE).toFloat()
    }
    private val bandStart = IntArray(AUDIO_SPECTRUM_BAND_COUNT)
    private val bandEnd = IntArray(AUDIO_SPECTRUM_BAND_COUNT)
    private val bandCenters = FloatArray(AUDIO_SPECTRUM_BAND_COUNT)
    private val smoothed = FloatArray(AUDIO_SPECTRUM_BAND_COUNT)
    private val peaks = FloatArray(AUDIO_SPECTRUM_BAND_COUNT)
    private val peakHold = FloatArray(AUDIO_SPECTRUM_BAND_COUNT)

    private var lastAnalyzeNs = 0L
    private var lastEmitNs = 0L
    private var centroidHz = 1_000f
    private var level = 0f
    private var version = 0L

    init {
        initBitReverse()
        rebuildBands(decimatedSampleRate)
    }

    /** Tee/音频格式变化时调用；会清空旧频谱，避免切歌或换采样率时残留 */
    @Synchronized
    fun flush(sampleRate: Int, channelCount: Int, encoding: Int) {
        val safeRate = sampleRate.coerceAtLeast(8_000)
        inputSampleRate = safeRate
        decimationFactor = max(1, (safeRate.toDouble() / TARGET_SAMPLE_RATE).roundToInt())
        decimatedSampleRate = safeRate / decimationFactor
        formatChannels = channelCount.coerceIn(1, 2)
        formatEncoding = encoding
        rebuildBands(decimatedSampleRate)

        ring.fill(0f)
        ringWrite = 0L
        lastFeedNs = 0L
        accumulator = 0f
        accumulatorCount = 0
        smoothed.fill(0f)
        peaks.fill(0f)
        peakHold.fill(0f)
        centroidHz = 1_000f
        level = 0f
        lastAnalyzeNs = 0L
        version++
        _frame.value = emptyFrame(decimatedSampleRate, version)
    }

    fun reset() {
        flush(inputSampleRate, formatChannels, formatEncoding)
    }

    /** 音频线程调用；只做解码、下混、抽取和环形缓冲写入，不做 FFT */
    fun feed(buffer: ByteBuffer) {
        if (!buffer.hasRemaining()) return
        val channels = formatChannels
        val factor = decimationFactor
        val encoding = formatEncoding
        when (encoding) {
            C.ENCODING_PCM_8BIT -> feed8Bit(buffer, channels, factor)
            C.ENCODING_PCM_16BIT -> feed16Bit(buffer, channels, factor, ByteOrder.LITTLE_ENDIAN)
            C.ENCODING_PCM_16BIT_BIG_ENDIAN -> feed16Bit(buffer, channels, factor, ByteOrder.BIG_ENDIAN)
            C.ENCODING_PCM_24BIT -> feed24Bit(buffer, channels, factor, bigEndian = false)
            C.ENCODING_PCM_24BIT_BIG_ENDIAN -> feed24Bit(buffer, channels, factor, bigEndian = true)
            C.ENCODING_PCM_32BIT -> feed32Bit(buffer, channels, factor, ByteOrder.LITTLE_ENDIAN)
            C.ENCODING_PCM_32BIT_BIG_ENDIAN -> feed32Bit(buffer, channels, factor, ByteOrder.BIG_ENDIAN)
            C.ENCODING_PCM_FLOAT -> feedFloat(buffer, channels, factor)
            else -> feed16Bit(buffer, channels, factor, ByteOrder.LITTLE_ENDIAN)
        }
        lastFeedNs = System.nanoTime()
    }

    /** 独立线程按 ~60fps 调用；完成加窗、FFT、频段合并、平滑和重心 */
    @Synchronized
    fun analyze(nowNs: Long) {
        val dt = if (lastAnalyzeNs == 0L) {
            0.033f
        } else {
            ((nowNs - lastAnalyzeNs) / 1_000_000_000.0).toFloat().coerceIn(0.001f, 0.2f)
        }
        lastAnalyzeNs = nowNs

        val silent = lastFeedNs == 0L || nowNs - lastFeedNs > SILENCE_AFTER_NS
        if (silent) {
            decaySilently(dt)
            emit(nowNs)
            return
        }

        val write = ringWrite
        val windowStart = write - FFT_SIZE
        var rms = 0.0
        for (index in 0 until FFT_SIZE) {
            val sample = if (windowStart >= 0L) {
                ring[((windowStart + index) and RING_MASK).toInt()]
            } else {
                0f
            }
            fftRe[index] = sample * window[index]
            fftIm[index] = 0f
            rms += sample * sample
        }
        level = sqrt(rms / FFT_SIZE).toFloat().coerceIn(0f, 1f)
        fft(fftRe, fftIm)

        val attack = 1.0 - exp(-dt / ATTACK_SECONDS)
        val release = 1.0 - exp(-dt / RELEASE_SECONDS)
        for (band in 0 until AUDIO_SPECTRUM_BAND_COUNT) {
            var maxPower = 0f
            for (bin in bandStart[band]..bandEnd[band]) {
                val power = fftRe[bin] * fftRe[bin] + fftIm[bin] * fftIm[bin]
                if (power > maxPower) maxPower = power
            }
            val amplitude = 2.0 * sqrt(maxPower.toDouble()) / (FFT_SIZE * coherentGain.toDouble())
            val db = 20.0 * log10(amplitude + 1e-9)
            val normalized = ((db - DB_FLOOR) / (DB_CEILING - DB_FLOOR)).coerceIn(0.0, 1.0)
            val target = normalized.pow(0.82).toFloat()
            val coefficient = if (target > smoothed[band]) attack else release
            smoothed[band] = (smoothed[band] + (target - smoothed[band]) * coefficient).toFloat()

            if (target > peaks[band]) {
                peaks[band] = target
                peakHold[band] = 0f
            } else if (peakHold[band] < PEAK_HOLD_SECONDS) {
                peakHold[band] += dt
            } else {
                peaks[band] = (peaks[band] - dt * PEAK_FALL_PER_SECOND).coerceAtLeast(0f)
            }
        }

        val binHz = decimatedSampleRate.toDouble() / FFT_SIZE
        var totalPower = 0.0
        var weightedPower = 0.0
        for (bin in 1 until FFT_SIZE / 2) {
            val power = (fftRe[bin] * fftRe[bin] + fftIm[bin] * fftIm[bin]).toDouble()
            totalPower += power
            weightedPower += bin * binHz * power
        }
        val targetCentroid = if (totalPower > 1e-12) {
            (weightedPower / totalPower).coerceIn(40.0, 16_000.0)
        } else {
            centroidHz.toDouble()
        }
        centroidHz += ((targetCentroid - centroidHz) * (1.0 - exp(-dt / 0.18))).toFloat()
        emit(nowNs)
    }

    internal fun bandCenterHz(index: Int): Float = bandCenters[index]

    private fun decaySilently(dt: Float) {
        val decay = exp(-dt / RELEASE_SECONDS).toFloat()
        for (band in 0 until AUDIO_SPECTRUM_BAND_COUNT) {
            smoothed[band] = (smoothed[band] * decay).coerceAtLeast(0f)
            if (smoothed[band] < 0.0005f) smoothed[band] = 0f
            if (peaks[band] > 0f) {
                if (peakHold[band] < PEAK_HOLD_SECONDS) {
                    peakHold[band] += dt
                } else {
                    peaks[band] = (peaks[band] - dt * PEAK_FALL_PER_SECOND).coerceAtLeast(0f)
                }
            }
        }
        level = 0f
    }

    private fun emit(nowNs: Long) {
        val quiet = level < 0.0005f && smoothed.all { it < 0.001f }
        if (quiet && nowNs - lastEmitNs < IDLE_EMIT_INTERVAL_NS) return
        lastEmitNs = nowNs
        version++
        _frame.value = AudioSpectrumFrame(
            bands = smoothed.copyOf(),
            peaks = peaks.copyOf(),
            centroidHz = centroidHz,
            level = level,
            sampleRate = decimatedSampleRate,
            version = version
        )
    }

    private fun rebuildBands(sampleRate: Int) {
        val binHz = sampleRate.toDouble() / FFT_SIZE
        val ratio = AUDIO_SPECTRUM_MAX_FREQUENCY_HZ / AUDIO_SPECTRUM_MIN_FREQUENCY_HZ
        val maxBin = FFT_SIZE / 2 - 1
        for (band in 0 until AUDIO_SPECTRUM_BAND_COUNT) {
            val low = AUDIO_SPECTRUM_MIN_FREQUENCY_HZ *
                ratio.pow(band.toDouble() / AUDIO_SPECTRUM_BAND_COUNT)
            val high = AUDIO_SPECTRUM_MIN_FREQUENCY_HZ *
                ratio.pow((band + 1).toDouble() / AUDIO_SPECTRUM_BAND_COUNT)
            val start = max(1, floor(low / binHz).toInt())
            val end = max(start, (high / binHz).roundToInt() - 1)
            bandStart[band] = start.coerceIn(1, maxBin)
            bandEnd[band] = end.coerceIn(bandStart[band], maxBin)
            bandCenters[band] = sqrt(low * high).toFloat()
        }
    }

    private fun feed8Bit(buffer: ByteBuffer, channels: Int, factor: Int) {
        val dup = buffer.duplicate()
        val frameBytes = channels
        while (dup.remaining() >= frameBytes) {
            val left = (((dup.get().toInt() and 0xFF) - 128) / 128f)
            val mono = if (channels == 1) {
                left
            } else {
                val right = (((dup.get().toInt() and 0xFF) - 128) / 128f)
                if (channels > 2) dup.position(dup.position() + channels - 2)
                (left + right) * 0.5f
            }
            pushSample(mono, factor)
        }
    }

    private fun feed16Bit(buffer: ByteBuffer, channels: Int, factor: Int, order: ByteOrder) {
        val dup = buffer.duplicate().order(order)
        val frameBytes = 2 * channels
        while (dup.remaining() >= frameBytes) {
            val left = dup.short / 32768f
            val mono = if (channels == 1) {
                left
            } else {
                val right = dup.short / 32768f
                if (channels > 2) dup.position(dup.position() + (channels - 2) * 2)
                (left + right) * 0.5f
            }
            pushSample(mono, factor)
        }
    }

    private fun feed24Bit(buffer: ByteBuffer, channels: Int, factor: Int, bigEndian: Boolean) {
        val dup = buffer.duplicate()
        val frameBytes = 3 * channels
        while (dup.remaining() >= frameBytes) {
            val left = read24Bit(dup, bigEndian)
            val mono = if (channels == 1) {
                left
            } else {
                val right = read24Bit(dup, bigEndian)
                if (channels > 2) dup.position(dup.position() + (channels - 2) * 3)
                (left + right) * 0.5f
            }
            pushSample(mono, factor)
        }
    }

    private fun feed32Bit(buffer: ByteBuffer, channels: Int, factor: Int, order: ByteOrder) {
        val dup = buffer.duplicate().order(order)
        val frameBytes = 4 * channels
        while (dup.remaining() >= frameBytes) {
            val left = dup.int / 2_147_483_648f
            val mono = if (channels == 1) {
                left
            } else {
                val right = dup.int / 2_147_483_648f
                if (channels > 2) dup.position(dup.position() + (channels - 2) * 4)
                (left + right) * 0.5f
            }
            pushSample(mono, factor)
        }
    }

    private fun feedFloat(buffer: ByteBuffer, channels: Int, factor: Int) {
        val dup = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val frameBytes = 4 * channels
        while (dup.remaining() >= frameBytes) {
            val left = dup.float.sanitizeSample()
            val mono = if (channels == 1) {
                left
            } else {
                val right = dup.float.sanitizeSample()
                if (channels > 2) dup.position(dup.position() + (channels - 2) * 4)
                (left + right) * 0.5f
            }
            pushSample(mono, factor)
        }
    }

    private fun Float.sanitizeSample(): Float =
        if (isFinite()) coerceIn(-1f, 1f) else 0f

    private fun read24Bit(buffer: ByteBuffer, bigEndian: Boolean): Float {
        val b0 = buffer.get().toInt() and 0xFF
        val b1 = buffer.get().toInt() and 0xFF
        val b2 = buffer.get().toInt() and 0xFF
        val raw = if (bigEndian) {
            (b0 shl 16) or (b1 shl 8) or b2
        } else {
            b0 or (b1 shl 8) or (b2 shl 16)
        }
        val signed = if (raw and 0x800000 != 0) raw or 0xFF000000.toInt() else raw
        return signed / 8_388_608f
    }

    private fun pushSample(sample: Float, factor: Int) {
        accumulator += sample
        accumulatorCount++
        if (accumulatorCount < factor) return
        ring[(ringWrite and RING_MASK).toInt()] = accumulator / factor
        ringWrite++
        accumulator = 0f
        accumulatorCount = 0
    }

    private fun initBitReverse() {
        var j = 0
        for (index in 1 until FFT_SIZE) {
            var bit = FFT_SIZE shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            bitReverse[index] = j
        }
    }

    private fun fft(re: FloatArray, im: FloatArray) {
        for (index in 0 until FFT_SIZE) {
            val reversed = bitReverse[index]
            if (reversed > index) {
                val real = re[index]
                re[index] = re[reversed]
                re[reversed] = real
                val imaginary = im[index]
                im[index] = im[reversed]
                im[reversed] = imaginary
            }
        }

        var length = 2
        while (length <= FFT_SIZE) {
            val half = length / 2
            val tableStep = FFT_SIZE / length
            var start = 0
            while (start < FFT_SIZE) {
                var tableIndex = 0
                for (index in start until start + half) {
                    val cosValue = cosTable[tableIndex]
                    val sinValue = sinTable[tableIndex]
                    val oddReal = re[index + half]
                    val oddImaginary = im[index + half]
                    val twiddledReal = oddReal * cosValue + oddImaginary * sinValue
                    val twiddledImaginary = -oddReal * sinValue + oddImaginary * cosValue
                    re[index + half] = re[index] - twiddledReal
                    im[index + half] = im[index] - twiddledImaginary
                    re[index] += twiddledReal
                    im[index] += twiddledImaginary
                    tableIndex += tableStep
                }
                start += length
            }
            length *= 2
        }
    }

    private fun emptyFrame(sampleRate: Int, version: Long = 0L): AudioSpectrumFrame =
        AudioSpectrumFrame(
            bands = FloatArray(AUDIO_SPECTRUM_BAND_COUNT),
            peaks = FloatArray(AUDIO_SPECTRUM_BAND_COUNT),
            centroidHz = 1_000f,
            level = 0f,
            sampleRate = sampleRate,
            version = version
        )
}
