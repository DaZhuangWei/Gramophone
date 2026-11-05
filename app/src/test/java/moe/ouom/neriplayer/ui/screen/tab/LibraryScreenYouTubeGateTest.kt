package moe.ouom.neriplayer.ui.screen.tab

import moe.ouom.neriplayer.ui.screen.tab.library.LibraryTab
import moe.ouom.neriplayer.ui.screen.tab.library.libraryTabDisplayOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryScreenYouTubeGateTest {

    @Test
    fun `library tabs exclude YouTube when disabled`() {
        val tabs = libraryTabDisplayOrder(
            isInternational = true,
            youtubeEnabled = false,
            liteEdition = false
        )

        assertFalse(tabs.contains(LibraryTab.YTMUSIC))
        assertTrue(tabs.contains(LibraryTab.NETEASE))
        assertTrue(tabs.contains(LibraryTab.BILI))
    }

    @Test
    fun `lite edition keeps only local favorite and netease tabs`() {
        val expected = listOf(LibraryTab.LOCAL, LibraryTab.FAVORITE, LibraryTab.NETEASE)

        assertEquals(
            expected,
            libraryTabDisplayOrder(
                isInternational = true,
                youtubeEnabled = true,
                liteEdition = true
            )
        )
        assertEquals(
            expected,
            libraryTabDisplayOrder(
                isInternational = false,
                youtubeEnabled = false,
                liteEdition = true
            )
        )
    }
}
