package moe.ouom.neriplayer.data.model.settings.appearance

data class ThemePreferenceSnapshot(
    val dynamicColor: Boolean = false,
    val forceDark: Boolean = true,
    val followSystemDark: Boolean = false,
    val uiStyle: UiStyle = UiStyle.Clean
) {
    fun resolveUseDark(systemDark: Boolean): Boolean {
        return when {
            forceDark -> true
            followSystemDark -> systemDark
            else -> false
        }
    }
}
