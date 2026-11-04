package moe.ouom.neriplayer.ui.settings

import moe.ouom.neriplayer.common.LiteEdition
import moe.ouom.neriplayer.core.player.service.notification.FlymeStatusBarLyricSupport

/** `status_bar_lyrics_enabled`，定义见 AutoSettingsSchema.lyrics.statusBarLyrics。 */
internal const val STATUS_BAR_LYRICS_SETTING_KEY = "status_bar_lyrics_enabled"

/**
 * 设备能力决定哪些设置项不提供入口 —— 与用户偏好无关。
 *
 * 探测逻辑见 [FlymeStatusBarLyricSupport]：只有魅族框架支持状态栏歌词时，
 * 这个开关才有意义，其余机型上直接不展示。
 */
internal fun resolveUnavailableSettingKeys(statusBarLyricsSupported: Boolean): Set<String> = buildSet {
    if (!statusBarLyricsSupported) {
        add(STATUS_BAR_LYRICS_SETTING_KEY)
    }
}

/** 已下线入口的设置项：保留存储值兼容旧配置，但界面与搜索都不再展示。 */
internal val retiredSettingKeys: Set<String> = setOf("always_use_new_tab_style")

/** Gramophone Lite 只保留网易云与本地音乐，其它平台设置不再展示。 */
private val liteHiddenSettingKeys: Set<String> = if (LiteEdition.ENABLED) {
    setOf(
        "youtube_enabled",
        "internationalization_enabled",
        "bili_skip_segment_prompt_enabled",
        "youtube_audio_quality",
        "bili_audio_quality",
        "mobile_data_youtube_audio_quality",
        "mobile_data_bili_audio_quality",
        "home_card_bilibili",
        "netease_auto_source_switch",
        "remember_long_form_playback_progress",
        "bili_sponsor_block_enabled",
        "bili_cc_subtitle_lyrics_enabled",
        "bili_cc_subtitle_language",
        "bili_cc_subtitle_ai_only_enabled",
        "bili_cc_subtitle_lyric_default_offset_ms",
        "qq_music_lyric_default_offset_ms",
        "kugou_lyric_default_offset_ms",
        "lrclib_lyric_default_offset_ms",
        "amll_ttml_lyric_default_offset_ms",
        "download_youtube_audio_quality",
        "download_bili_audio_quality"
    )
} else {
    emptySet()
}

/** 当前设备上不提供入口的设置项；设备能力不会在运行期改变，解析一次即可。 */
internal val unavailableSettingKeys: Set<String> =
    resolveUnavailableSettingKeys(FlymeStatusBarLyricSupport.isSupported) +
        retiredSettingKeys +
        liteHiddenSettingKeys
