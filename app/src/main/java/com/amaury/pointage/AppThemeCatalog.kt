package com.amaury.pointage

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color

/** Catalogue central des thèmes HP Travail. */
data class HpTheme(
    val id: String,
    val label: String,
    val darkBackground: Int,
    val darkPanel: Int,
    val lightBackground: Int,
    val lightPanel: Int,
    val accent: Int,
    val accentLight: Int,
    val darkText: Int,
    val lightText: Int,
    val darkHint: Int,
    val lightHint: Int
)

object AppThemeCatalog {
    const val PREFS = "appearance_settings"
    const val KEY_THEME = "visual_theme"
    const val DEFAULT_THEME = "signature_gold"
    private const val KEY_CELESTIAL_NIGHT = "celestial_night"
    private const val KEY_SOLAR = "solar_lighting_enabled"

    val themes = listOf(
        HpTheme(
            id = "signature_gold",
            label = "Signature Or",
            darkBackground = Color.parseColor("#050505"),
            darkPanel = Color.parseColor("#181818"),
            lightBackground = Color.parseColor("#F3F0E8"),
            lightPanel = Color.parseColor("#FFFDF9"),
            accent = Color.parseColor("#D6A84B"),
            accentLight = Color.parseColor("#F3D58A"),
            darkText = Color.parseColor("#F7F3EC"),
            lightText = Color.parseColor("#17130D"),
            darkHint = Color.parseColor("#C9C1B4"),
            lightHint = Color.parseColor("#625B50")
        ),
        HpTheme(
            id = "steel_blue",
            label = "Acier Bleu",
            darkBackground = Color.parseColor("#071018"),
            darkPanel = Color.parseColor("#12202C"),
            lightBackground = Color.parseColor("#DDEAF2"),
            lightPanel = Color.parseColor("#F3F9FC"),
            accent = Color.parseColor("#397FAE"),
            accentLight = Color.parseColor("#8FD2FF"),
            darkText = Color.parseColor("#EFF8FF"),
            lightText = Color.parseColor("#10202C"),
            darkHint = Color.parseColor("#AFC6D6"),
            lightHint = Color.parseColor("#506674")
        ),
        HpTheme(
            id = "brushed_aluminum",
            label = "Alu Brossé",
            darkBackground = Color.parseColor("#202326"),
            darkPanel = Color.parseColor("#30353A"),
            lightBackground = Color.parseColor("#D8DBDE"),
            lightPanel = Color.parseColor("#F0F2F3"),
            accent = Color.parseColor("#7C858C"),
            accentLight = Color.parseColor("#E3E7EA"),
            darkText = Color.parseColor("#F5F6F7"),
            lightText = Color.parseColor("#202428"),
            darkHint = Color.parseColor("#BFC5C9"),
            lightHint = Color.parseColor("#5C646A")
        ),
        HpTheme(
            id = "natural_carbon",
            label = "Carbone",
            darkBackground = Color.parseColor("#070808"),
            darkPanel = Color.parseColor("#161819"),
            lightBackground = Color.parseColor("#C7C9C9"),
            lightPanel = Color.parseColor("#E4E6E6"),
            accent = Color.parseColor("#596166"),
            accentLight = Color.parseColor("#CDD2D4"),
            darkText = Color.parseColor("#F1F2F2"),
            lightText = Color.parseColor("#171919"),
            darkHint = Color.parseColor("#A9AFB2"),
            lightHint = Color.parseColor("#565C5F")
        ),
        HpTheme(
            id = "diamond_crystal",
            label = "Diamant",
            darkBackground = Color.parseColor("#07111D"),
            darkPanel = Color.parseColor("#102033"),
            lightBackground = Color.parseColor("#EAF6FC"),
            lightPanel = Color.parseColor("#F8FCFF"),
            accent = Color.parseColor("#8DC9E8"),
            accentLight = Color.parseColor("#E8F8FF"),
            darkText = Color.parseColor("#F4FBFF"),
            lightText = Color.parseColor("#102431"),
            darkHint = Color.parseColor("#B7D7E8"),
            lightHint = Color.parseColor("#567487")
        )
    )

    fun current(context: Context): HpTheme {
        val id = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_THEME, DEFAULT_THEME) ?: DEFAULT_THEME
        return themes.firstOrNull { it.id == id } ?: themes.first()
    }

    /**
     * En mode automatique, la palette suit le vrai jour/nuit lorsque l'éclairage
     * soleil/lune est activé. Sinon elle suit le thème système Android.
     */
    fun useDarkPalette(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return when (prefs.getString("mode", "auto") ?: "auto") {
            "light" -> false
            "dark" -> true
            else -> {
                if (prefs.getBoolean(KEY_SOLAR, false) && prefs.contains(KEY_CELESTIAL_NIGHT)) {
                    prefs.getBoolean(KEY_CELESTIAL_NIGHT, false)
                } else {
                    (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
                }
            }
        }
    }

    fun setCelestialNight(context: Context, night: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_CELESTIAL_NIGHT, night).apply()
    }

    fun set(context: Context, theme: HpTheme) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_THEME, theme.id)
            .remove("app_bg")
            .putBoolean("custom_bg", false)
            .putBoolean("custom_image_bg", false)
            .commit()
    }
}
