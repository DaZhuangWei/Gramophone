package moe.ouom.neriplayer.core.player.download.transfer

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.LockSupport

/**
 * 只限制「边听边下」自动下载的带宽，手动下载不受影响。
 *
 * 传输线程按歌曲 stableKey 查询限额；令牌桶允许一秒左右的突发，
 * 随后按设定速率阻塞读取，避免自动下载抢占在线播放缓冲。
 */
object AutoDownloadBandwidthLimiter {
    const val MAX_SPEED_LIMIT_KBPS = 20 * 1024
    const val SPEED_LIMIT_STEP_KBPS = 64

    private val ratesBytesPerSecond = ConcurrentHashMap<String, Long>()
    private val buckets = ConcurrentHashMap<String, Bucket>()

    fun normalizeSpeedLimitKbps(value: Int): Int =
        if (value <= 0) {
            0
        } else {
            val stepped = ((value.toLong() + SPEED_LIMIT_STEP_KBPS / 2) / SPEED_LIMIT_STEP_KBPS) *
                SPEED_LIMIT_STEP_KBPS
            stepped.coerceIn(
                SPEED_LIMIT_STEP_KBPS.toLong(),
                MAX_SPEED_LIMIT_KBPS.toLong()
            ).toInt()
        }

    fun speedLimitBytesPerSecond(speedLimitKbps: Int): Long =
        normalizeSpeedLimitKbps(speedLimitKbps).toLong() * 1024L

    fun registerAutoDownload(songKey: String, speedLimitKbps: Int) {
        val rate = speedLimitBytesPerSecond(speedLimitKbps)
        if (rate <= 0L) {
            unregister(songKey)
            return
        }
        ratesBytesPerSecond[songKey] = rate
        buckets[songKey] = Bucket(rate)
    }

    fun unregister(songKey: String) {
        ratesBytesPerSecond.remove(songKey)
        buckets.remove(songKey)
    }

    fun throttle(songKey: String, bytes: Long) {
        if (bytes <= 0L) return
        val rate = ratesBytesPerSecond[songKey] ?: return
        if (rate <= 0L) return
        val waitNs = (buckets[songKey] ?: Bucket(rate).also { buckets[songKey] = it })
            .consume(bytes = bytes, rateBytesPerSecond = rate)
        if (waitNs > 0L) {
            LockSupport.parkNanos(waitNs)
        }
    }

    private class Bucket(rateBytesPerSecond: Long) {
        private var capacity: Double = rateBytesPerSecond.toDouble().coerceAtLeast(64.0 * 1024)
        private var tokens: Double = capacity
        private var lastNs: Long = System.nanoTime()

        @Synchronized
        fun consume(bytes: Long, rateBytesPerSecond: Long): Long {
            val now = System.nanoTime()
            capacity = rateBytesPerSecond.toDouble().coerceAtLeast(64.0 * 1024)
            val elapsedSec = ((now - lastNs).coerceAtLeast(0L) / 1_000_000_000.0)
            tokens = (tokens + elapsedSec * rateBytesPerSecond)
                .coerceAtMost(capacity)
            lastNs = now

            if (tokens >= bytes.toDouble()) {
                tokens -= bytes.toDouble()
                return 0L
            }

            val deficit = bytes.toDouble() - tokens
            tokens = 0.0
            return (deficit / rateBytesPerSecond * 1_000_000_000.0)
                .toLong()
                .coerceAtLeast(0L)
        }
    }
}
