@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package moe.ouom.neriplayer.core.player.lifecycle

import coil.Coil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.common.logging.NPLogger
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.player.cache.clearRetainingImageCache
import moe.ouom.neriplayer.data.identity.stableKey
import moe.ouom.neriplayer.data.model.storage.ExtraCacheClearResult
import moe.ouom.neriplayer.data.model.storage.PlayerCacheClearOutcome
import moe.ouom.neriplayer.data.model.storage.StorageCacheClearOptions
import moe.ouom.neriplayer.data.settings.storage.PlaybackCacheStorage

private val storageCacheClearMutex = Mutex()

internal suspend fun PlayerManager.clearStorageCachesImpl(options: StorageCacheClearOptions): PlayerCacheClearOutcome =
    storageCacheClearMutex.withLock {
        withContext(Dispatchers.IO) {
            val empty = ExtraCacheClearResult(true, 0, 0, 0)
            if (!options.needsPlayerCacheClear) return@withContext PlayerCacheClearOutcome(empty)
            var completed = empty
            var retentionUnavailable = options.retainRecentSongs > 0
            try {
                val recent = loadRetainedStorageCacheSongs(options.retainRecentSongs)
                    ?: return@withContext PlayerCacheClearOutcome(empty.copy(success = false), retentionUnavailable = true)
                retentionUnavailable = false
                val plan = snapshotStorageCacheRetention(recent, options.retainRecentSongs)
                val audio = if (options.audioCache) cache?.let {
                    clearAudioStorageCache(it, plan.retainedSongs, plan.protectedKeys, plan.preserveUnmapped) { remove ->
                        synchronized(storageCachePlaybackLock) {
                            if (playbackRequestToken != plan.requestToken || currentSongFlow.value?.stableKey() != plan.currentSongKey) {
                                false
                            } else {
                                remove()
                                true
                            }
                        }
                    }
                } ?: empty else empty
                val staleAudio = if (options.audioCache) {
                    PlaybackCacheStorage.cleanupStaleCacheDirs(application)
                } else {
                    PlaybackCacheStorage.CleanupResult(true, 0L, 0)
                }
                val audioWithStale = audio.copy(
                    success = audio.success && staleAudio.success,
                    freedBytes = audio.freedBytes + staleAudio.freedBytes,
                    deletedFiles = audio.deletedFiles + staleAudio.deletedFiles
                )
                completed = audioWithStale
                val image = if (options.imageCache) {
                    clearRetainingImageCache(Coil.imageLoader(application), plan.retainedCoverUrls, plan.preserveUnmapped)
                } else empty
                PlayerCacheClearOutcome(
                    ExtraCacheClearResult(
                        audioWithStale.success && image.success,
                        audioWithStale.freedBytes + image.freedBytes,
                        0,
                        audioWithStale.deletedFiles + image.deletedFiles
                    )
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                NPLogger.w("NERI-Player", "Storage cache clear failed", error)
                PlayerCacheClearOutcome(completed.copy(success = false), retentionUnavailable)
            }
        }
    }
