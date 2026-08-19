package com.amaury.pointage

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color

data class HpTheme(
    val id: String, val label: String,
    val darkBackground: Int, val darkPanel: Int,
    val lightBackground: Int, val lightPanel: Int,
    val accent: Int, val accentLight: Int,
    val darkText: Int, val lightText: Int,
    val darkHint: Int, val lightHint: Int
)

object AppThemeCatalog {
    const val PREFS = "appearance_settings"
    const val KEY_THEME = "visual_theme"
    const val DEFAULT_THEME = "signature_gold"
    private const val KEY_CELESTIAL_NIGHT = "celestial_night"
    private const val KEY_SOLAR = "solar_lighting_enabled"

    val themes = listOf(
        HpTheme("signature_gold", "Signature Or", Color.parseColor("#050505"), Color.parseColor("#181818"), Color.parseColor("#F3F0E8"), Color.parseColor("#FFFDF9"), Color.parseColor("#D6A84B"), Color.parseColor("#F3D58A"), Color.parseColor("#F7F3EC"), Color.parseColor("#17130D"), Color.parseColor("#C9C1B4"), Color.parseColor("#625B50")),
        HpTheme("steel_blue", "Acier Bleu", Color.parseColor("#071018"), Color.parseColor("#12202C"), Color.parseColor("#DDEAF2"), Color.parseColor("#F3F9FC"), Color.parseColor("#397FAE"), Color.parseColor("#8FD2FF"), Color.parseColor("#EFF8FF"), Color.parseColor("#10202C"), Color.parseColor("#AFC6D6"), Color.parseColor("#506674")),
        HpTheme("brushed_aluminum", "Alu Brossé", Color.parseColor("#202326"), Color.parseColor("#30353A"), Color.parseColor("#D8DBDE"), Color.parseColor("#F0F2F3"), Color.parseColor("#7C858C"), Color.parseColor("#E3E7EA"), Color.parseColor("#F5F6F7"), Color.parseColor("#202428"), Color.parseColor("#BFC5C9"), Color.parseColor("#5C646A")),
        HpTheme("natural_carbon", "Carbone", Color.parseColor("#070808"), Color.parseColor("#161819"), Color.parseColor("#C7C9C9"), Color.parseColor("#E4E6E6"), Color.parseColor("#596166"), Color.parseColor("#CDD2D4"), Color.parseColor("#F1F2F2"), Color.parseColor("#171919"), Color.parseColor("#A9AFB2"), Color.parseColor("#565C5F")),
        // Le diamant garde volontairement un écrin noir/bleu nuit, même en palette claire :
        // le contraste sombre est indispensable pour rendre visibles transparence, réfraction et éclats.
        HpTheme("diamond_crystal", "Diamant", Color.parseColor("#030810"), Color.parseColor("#09131F"), Color.parseColor("#07111D"), Color.parseColor("#0D1A28"), Color.parseColor("#8DC9E8"), Color.parseColor("#F4FBFF"), Color.parseColor("#F7FCFF"), Color.parseColor("#F7FCFF"), Color.parseColor("#AFC8D8"), Color.parseColor("#AFC8D8"))
    )

    fun current(context: Context): HpTheme {
        val id = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_THEME, DEFAULT_THEME) ?: DEFAULT_THEME
        return themes.firstOrNull { it.id == id } ?: themes.first()
    }

    fun useDarkPalette(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return when (prefs.getString("mode", "auto") ?: "auto") {
            "light" -> false
            "dark" -> true
            else -> if (prefs.getBoolean(KEY_SOLAR, false) && prefs.contains(KEY_CELESTIAL_NIGHT)) prefs.getBoolean(KEY_CELESTIAL_NIGHT, false)
            else (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        }
    }

    fun setCelestialNight(context: Context, night: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_CELESTIAL_NIGHT, night).apply()
    }

    fun set(context: Context, theme: HpTheme) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_THEME, theme.id).remove("app_bg").putBoolean("custom_bg", false).putBoolean("custom_image_bg", false).commit()
    }
}
