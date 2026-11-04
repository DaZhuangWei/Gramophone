package moe.ouom.neriplayer.util.media

import org.junit.Assert.assertEquals
import org.junit.Test

class NeteaseImageUrlResizeTest {
    @Test
    fun `netease cover urls ask the CDN for the displayed size`() {
        val url = "https://p1.music.126.net/HXfXIIBCk5w96MiY1Wdsqw==/109951171836610847.jpg"

        assertEquals(
            "$url?param=128y128",
            neteaseResizedImageRequestData(url, 128)
        )
    }

    @Test
    fun `existing query parameters are preserved`() {
        val url = "https://p2.music.126.net/example.jpg?v=1"

        assertEquals(
            "$url&param=384y384",
            neteaseResizedImageRequestData(url, 384)
        )
    }

    @Test
    fun `urls that already request a size are left alone`() {
        val url = "https://p1.music.126.net/example.jpg?param=200y200"

        assertEquals(url, neteaseResizedImageRequestData(url, 512))
    }

    @Test
    fun `non-cover hosts and local references are left alone`() {
        val audio = "https://m801.music.126.net/audio.mp3"
        val local = "content://media/external/audio/media/1"

        assertEquals(audio, neteaseResizedImageRequestData(audio, 128))
        assertEquals(local, neteaseResizedImageRequestData(local, 128))
        assertEquals(null, neteaseResizedImageRequestData(null, 128))
    }

    @Test
    fun `invalid sizes do not rewrite the url`() {
        val url = "https://p1.music.126.net/example.jpg"

        assertEquals(url, neteaseResizedImageRequestData(url, null))
        assertEquals(url, neteaseResizedImageRequestData(url, 0))
        assertEquals(url, neteaseResizedImageRequestData(url, -1))
    }
}
