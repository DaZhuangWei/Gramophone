package moe.ouom.neriplayer.core.player.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OfflinePlaybackFallbackTest {
    @Test
    fun `picks the first offline playable track after the current one`() {
        val playable = setOf(2, 4)
        assertEquals(
            2,
            nextOfflinePlayableIndex(queueSize = 5, currentIndex = 0) { it in playable }
        )
        assertEquals(
            4,
            nextOfflinePlayableIndex(queueSize = 5, currentIndex = 2) { it in playable }
        )
    }

    @Test
    fun `wraps around to find an earlier cached track`() {
        val playable = setOf(0, 1)
        assertEquals(
            0,
            nextOfflinePlayableIndex(queueSize = 5, currentIndex = 4) { it in playable }
        )
    }

    @Test
    fun `never selects the current track again`() {
        val playable = setOf(1)
        assertNull(nextOfflinePlayableIndex(queueSize = 3, currentIndex = 1) { it in playable })
    }

    @Test
    fun `returns null when no other track is offline playable`() {
        val playable = setOf(1)
        assertNull(nextOfflinePlayableIndex(queueSize = 1, currentIndex = 0) { it in playable })
        assertNull(nextOfflinePlayableIndex(queueSize = 4, currentIndex = 1) { it in playable })
    }
}
