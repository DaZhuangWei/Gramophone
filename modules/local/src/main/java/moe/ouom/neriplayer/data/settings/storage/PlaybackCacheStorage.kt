package moe.ouom.neriplayer.data.settings.storage

import android.content.Context
import java.io.File

/**
 * 播放缓存（听过的歌曲）存放位置。
 *
 * Media3 的 SimpleCache 必须使用可随机读写的真实目录，因此这里只提供
 * 应用可写的私有/应用专属目录；用户可以在内部存储与各外置存储之间选择。
 */
object PlaybackCacheStorage {
    const val DIRECTORY_NAME = "media_cache"

    private const val PREFS_NAME = "playback_cache_storage"
    private const val LOCATION_KEY = "location"

    enum class Location(val storedValue: String) {
        INTERNAL("internal"),
        EXTERNAL("external"),
        EXTERNAL_SD("external_sd");

        companion object {
            fun fromStoredValue(value: String?): Location =
                entries.firstOrNull { it.storedValue == value } ?: INTERNAL
        }
    }

    fun read(context: Context): Location =
        Location.fromStoredValue(
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(LOCATION_KEY, null)
        )

    fun write(context: Context, location: Location) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(LOCATION_KEY, location.storedValue)
            .apply()
    }

    fun isAvailable(context: Context, location: Location): Boolean =
        when (location) {
            Location.INTERNAL -> true
            Location.EXTERNAL -> context.getExternalFilesDir(null) != null
            Location.EXTERNAL_SD -> context.getExternalFilesDirs(null)?.getOrNull(1) != null
        }

    fun availableLocations(context: Context): List<Location> =
        Location.entries.filter { isAvailable(context, it) }

    /**
     * 当前选择不可用时自动回退到内部存储，避免 SD 卡拔出后完全失去缓存。
     */
    fun resolveCacheDir(context: Context): File =
        resolveCacheDir(context, read(context))

    fun resolveCacheDir(context: Context, location: Location): File {
        val root = when (location) {
            Location.INTERNAL -> context.filesDir
            Location.EXTERNAL -> context.getExternalFilesDir(null)
            Location.EXTERNAL_SD -> context.getExternalFilesDirs(null)?.getOrNull(1)
        } ?: context.filesDir
        return File(root, DIRECTORY_NAME).apply { runCatching { mkdirs() } }
    }

    fun locationCacheDir(context: Context, location: Location): File {
        val root = when (location) {
            Location.INTERNAL -> context.filesDir
            Location.EXTERNAL -> context.getExternalFilesDir(null)
            Location.EXTERNAL_SD -> context.getExternalFilesDirs(null)?.getOrNull(1)
        } ?: return File(context.filesDir, DIRECTORY_NAME)
        return File(root, DIRECTORY_NAME)
    }

    /**
     * 统计与清理需要覆盖所有历史位置，包括升级前位于 cacheDir 的旧缓存。
     */
    fun candidateCacheDirs(context: Context): List<File> = buildList {
        add(File(context.filesDir, DIRECTORY_NAME))
        context.getExternalFilesDirs(null)?.forEach { root ->
            if (root != null) add(File(root, DIRECTORY_NAME))
        }
        add(File(context.cacheDir, DIRECTORY_NAME))
    }.distinctBy { it.absolutePath }

    data class CleanupResult(
        val success: Boolean,
        val freedBytes: Long,
        val deletedFiles: Int
    )

    /**
     * 清除切换位置后遗留在旧目录的缓存；当前正在使用的目录会跳过。
     */
    fun cleanupStaleCacheDirs(context: Context): CleanupResult {
        val activePath = resolveCacheDir(context).absolutePath
        var success = true
        var freedBytes = 0L
        var deletedFiles = 0
        candidateCacheDirs(context)
            .filter { it.absolutePath != activePath && it.exists() }
            .forEach { directory ->
                runCatching {
                    val files = directory.walkTopDown().filter { it.isFile }.toList()
                    val directoryBytes = files.sumOf { it.length().coerceAtLeast(0L) }
                    if (directory.deleteRecursively()) {
                        freedBytes += directoryBytes
                        deletedFiles += files.size
                    } else {
                        success = false
                    }
                }.onFailure {
                    success = false
                }
            }
        return CleanupResult(success, freedBytes, deletedFiles)
    }

    /**
     * 旧版本把播放缓存放在 cacheDir，升级后迁移到持久的 filesDir；
     * 同一分区内直接 rename，失败时旧缓存仍可被统计和清理。
     */
    fun migrateLegacyCacheIfNeeded(context: Context) {
        val target = File(context.filesDir, DIRECTORY_NAME)
        if (target.exists()) return
        val legacy = File(context.cacheDir, DIRECTORY_NAME)
        if (!legacy.isDirectory) return
        runCatching {
            // 只做同分区 rename（秒级）；跨分区大目录复制会拖慢启动，失败就保留旧缓存等待清理
            target.parentFile?.mkdirs()
            legacy.renameTo(target)
        }
    }
}
