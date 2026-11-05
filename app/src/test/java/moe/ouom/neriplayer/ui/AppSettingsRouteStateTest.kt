package moe.ouom.neriplayer.ui

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import moe.ouom.neriplayer.data.settings.SettingsRepository
import moe.ouom.neriplayer.data.model.settings.appearance.AdvancedBlurQuality
import moe.ouom.neriplayer.data.model.settings.appearance.ThemePreferenceSnapshot
import moe.ouom.neriplayer.data.model.settings.lyrics.LyricFontScales
import moe.ouom.neriplayer.data.model.settings.playback.PlaybackPreferenceSnapshot
import moe.ouom.neriplayer.ui.settings.owner.AppUsbExclusiveSettingsActions
import moe.ouom.neriplayer.ui.settings.route.AppAppearanceSettingsActions
import moe.ouom.neriplayer.ui.settings.route.AppHomeSettingsActions
import moe.ouom.neriplayer.ui.settings.route.appSettingsRouteStateFlow
import moe.ouom.neriplayer.ui.settings.route.initialAppSettingsRouteState
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

// DataStore 在 Android SDK < 26 时会退回 File.renameTo，Windows 上无法覆盖已存在的文件；
// Robolectric 提供 SDK 35，才能走到 java.nio 的替换语义
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppSettingsRouteStateTest {
    @Test
    fun initialRouteStateKeepsEveryBootstrapPreferenceSourceIndependent() {
        val theme = ThemePreferenceSnapshot()
        val playback = PlaybackPreferenceSnapshot()
        val fontScales = LyricFontScales(1.25f, 1.15f, 1.3f, 1.2f)
        val initial = initialAppSettingsRouteState(theme, playback, AdvancedBlurQuality.Default, fontScales)
        val changedTheme = initialAppSettingsRouteState(
            theme.copy(dynamicColor = false), playback, AdvancedBlurQuality.Default, fontScales
        )
        val changedPlayback = initialAppSettingsRouteState(
            theme, playback.copy(cloudMusicLyricDefaultOffsetMs = 240L),
            AdvancedBlurQuality.Default, fontScales
        )
        val changedBlurQuality = initialAppSettingsRouteState(
            theme, playback, AdvancedBlurQuality.High, fontScales
        )
        val changedFonts = initialAppSettingsRouteState(
            theme, playback, AdvancedBlurQuality.Default, fontScales.copy(coverTranslation = 0.9f)
        )

        assertEquals(initial.copy(appearance = initial.appearance.copy(
            theme = initial.appearance.theme.copy(dynamicColorEnabled = false)
        )), changedTheme)
        assertEquals(initial.copy(lyrics = initial.lyrics.copy(
            lyricOffsets = initial.lyrics.lyricOffsets.copy(cloudMusicLyricDefaultOffsetMs = 240L)
        )), changedPlayback)
        assertEquals(initial.copy(appearance = initial.appearance.copy(
            visualBlur = initial.appearance.visualBlur.copy(advancedBlurQuality = AdvancedBlurQuality.High)
        )), changedBlurQuality)
        assertEquals(initial.copy(lyrics = initial.lyrics.copy(
            lyricPresentation = initial.lyrics.lyricPresentation.copy(
                lyricFontScales = fontScales.copy(coverTranslation = 0.9f)
            )
        )), changedFonts)
    }

    @Test
    fun internationalizationAndUsbPreferencesReachTheSettingsDomainState() = runBlocking {
        val filesDir = File.createTempFile("neriplayer-settings-route", "").apply {
            delete()
            mkdirs()
            deleteOnExit()
        }
        val application = ApplicationProvider.getApplicationContext<Application>()
        val context = object : ContextWrapper(application) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = filesDir
        }
        val repository = SettingsRepository(context)

        val initial = withTimeout(10_000) { appSettingsRouteStateFlow(repository).first() }
        val nextInternationalization = !initial.other.internationalizationEnabled
        val nextBitPerfect = !initial.playback.usbExclusivePreferences.bitPerfect
        AppAppearanceSettingsActions(repository, this) {}
            .onInternationalizationEnabledChange(nextInternationalization)
        AppUsbExclusiveSettingsActions(repository, this).onBitPerfectChange(nextBitPerfect)
        AppHomeSettingsActions(repository, this).onShowHomeBilibiliCardChange(false)

        val updated = withTimeout(10_000) {
            appSettingsRouteStateFlow(repository).first { state ->
                state.other.internationalizationEnabled == nextInternationalization &&
                    state.playback.usbExclusivePreferences.bitPerfect == nextBitPerfect &&
                    !state.other.homeCards.showHomeBilibiliCard
            }
        }
        assertEquals(nextInternationalization, updated.other.internationalizationEnabled)
        assertEquals(nextBitPerfect, updated.playback.usbExclusivePreferences.bitPerfect)
        assertEquals(false, updated.other.homeCards.showHomeBilibiliCard)
    }
}
