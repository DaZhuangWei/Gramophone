package moe.ouom.neriplayer.core.player.download.transfer

import org.junit.Assert.assertEquals
import org.junit.Test

class AutoDownloadBandwidthLimiterTest {
    @Test
    fun `speed limit is normalized to the configured step`() {
        assertEquals(0, AutoDownloadBandwidthLimiter.normalizeSpeedLimitKbps(-1))
        assertEquals(0, AutoDownloadBandwidthLimiter.normalizeSpeedLimitKbps(0))
        assertEquals(64, AutoDownloadBandwidthLimiter.normalizeSpeedLimitKbps(1))
        assertEquals(128, AutoDownloadBandwidthLimiter.normalizeSpeedLimitKbps(100))
        assertEquals(1024, AutoDownloadBandwidthLimiter.normalizeSpeedLimitKbps(1000))
    }

    @Test
    fun `speed limit never exceeds the maximum`() {
        assertEquals(
            AutoDownloadBandwidthLimiter.MAX_SPEED_LIMIT_KBPS,
            AutoDownloadBandwidthLimiter.normalizeSpeedLimitKbps(Int.MAX_VALUE)
        )
    }

    @Test
    fun `byte rate follows the normalized kbps value`() {
        assertEquals(0L, AutoDownloadBandwidthLimiter.speedLimitBytesPerSecond(0))
        assertEquals(128L * 1024L, AutoDownloadBandwidthLimiter.speedLimitBytesPerSecond(100))
    }

    @Test
    fun `registered automatic downloads are throttled to the configured rate`() {
        val songKey = "test-throttle-song"
        AutoDownloadBandwidthLimiter.registerAutoDownload(songKey, 64)
        try {
            val startNs = System.nanoTime()
            AutoDownloadBandwidthLimiter.throttle(songKey, 128L * 1024L)
            val elapsedMs = (System.nanoTime() - startNs) / 1_000_000L
            assert(elapsedMs >= 900L) {
                "expected the 64 KB/s limiter to delay 128 KB by about one second, got ${elapsedMs}ms"
            }
        } finally {
            AutoDownloadBandwidthLimiter.unregister(songKey)
        }
    }

    @Test
    fun `unregistered songs are never throttled`() {
        AutoDownloadBandwidthLimiter.unregister("missing")
        AutoDownloadBandwidthLimiter.throttle("missing", 1024L)
    }
}
