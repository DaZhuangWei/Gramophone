package moe.ouom.neriplayer.core.player.network

import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.common.logging.NPLogger
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.player.host.PlayerDependencies
import moe.ouom.neriplayer.core.player.playback.playAtIndex
import moe.ouom.neriplayer.core.player.url.hasCompleteExoPlayerCache
import moe.ouom.neriplayer.data.identity.stableKey
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.playback.PlaybackCommandSource
import moe.ouom.neriplayer.data.model.playback.PlayerEvent

private const val TAG = "NERI-OfflineFallback"

/**
 * 从当前曲目之后开始，环形寻找下一首可离线播放的歌曲。
 * 返回 null 表示整个队列都没有本地/缓存/下载副本。
 */
internal fun nextOfflinePlayableIndex(
    queueSize: Int,
    currentIndex: Int,
    isPlayable: (Int) -> Boolean
): Int? {
    if (queueSize <= 1) return null
    val safeCurrent = currentIndex.coerceIn(0, queueSize - 1)
    for (offset in 1 until queueSize) {
        val index = (safeCurrent + offset) % queueSize
        if (isPlayable(index)) return index
    }
    return null
}

/**
 * 判断歌曲是否能脱离网络播放：
 * - 本地歌曲
 * - 已下载歌曲（含刚完成、目录尚未刷新的）
 * - Media3 播放缓存已完整覆盖整首
 */
internal fun PlayerManager.isOfflinePlayableSong(song: SongItem): Boolean {
    if (isLocalSong(song)) return true
    val downloads = PlayerDependencies.downloads
    if (downloads.hasDownloadedSongCached(song)) return true
    if (downloads.hasRecentlyCompletedAudio(song)) return true
    val cacheKey = runCatching { computeCacheKey(song) }.getOrNull() ?: return false
    return hasCompleteExoPlayerCache(cacheKey)
}

/**
 * 断网失败时优先跳到队列中下一首可离线播放的歌，而不是停在不可播放的曲目上。
 * 返回 true 表示已经接管跳转；false 时调用方继续走原来的“等待网络”逻辑。
 */
internal fun PlayerManager.tryPlayNextOfflinePlayable(reason: String): Boolean {
    if (isListenTogetherActive()) return false
    val targetIndex = nextOfflinePlayableIndex(
        queueSize = currentPlaylist.size,
        currentIndex = currentIndex
    ) { index ->
        isOfflinePlayableSong(currentPlaylist[index])
    } ?: return false

    val fromIndex = currentIndex
    val targetSong = currentPlaylist[targetIndex]
    NPLogger.w(
        TAG,
        "network offline fallback: reason=$reason, fromIndex=$fromIndex, " +
            "targetIndex=$targetIndex, song=${targetSong.name}, key=${targetSong.stableKey()}"
    )
    currentIndex = targetIndex
    markAutoTrackAdvance()
    playAtIndex(
        index = targetIndex,
        commandSource = PlaybackCommandSource.LOCAL_SAFETY,
        allowRememberedLongFormPosition = false
    )
    emitPlaybackCommand(
        type = "NEXT",
        source = PlaybackCommandSource.LOCAL_SAFETY,
        queue = currentPlaylist.toList(),
        currentIndex = targetIndex,
        positionMs = 0L,
        force = true
    )
    postPlayerEvent(
        PlayerEvent.ShowNotice(
            getLocalizedString(CoreCommonR.string.player_playback_switched_to_offline)
        )
    )
    return true
}
