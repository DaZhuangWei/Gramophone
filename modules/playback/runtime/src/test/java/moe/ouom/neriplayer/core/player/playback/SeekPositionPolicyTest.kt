package moe.ouom.neriplayer.core.player.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class SeekPositionPolicyTest {
    @Test
    fun `normal seek keeps the requested position`() {
        assertEquals(12_000L, resolveSafeSeekPosition(12_000L, 180_000L))
    }

    @Test
    fun `negative seek is clamped to zero`() {
        assertEquals(0L, resolveSafeSeekPosition(-500L, 180_000L))
    }

    @Test
    fun `seek at or past duration stays before the end`() {
        assertEquals(179_999L, resolveSafeSeekPosition(180_000L, 180_000L))
        assertEquals(179_999L, resolveSafeSeekPosition(999_999L, 180_000L))
    }

    @Test
    fun `unknown duration keeps the requested position`() {
        assertEquals(12_000L, resolveSafeSeekPosition(12_000L, 0L))
        assertEquals(12_000L, resolveSafeSeekPosition(12_000L, -1L))
    }
}
