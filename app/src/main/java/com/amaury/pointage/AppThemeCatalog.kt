package com.amaury.pointage

import android.content.Context
import android.graphics.Color

/**
 * Catalogue central des thèmes HP Travail.
 * Le thème choisi définit une palette complète clair/sombre.
 * Un fond personnalisé reste une option distincte : choisir un thème réinitialise
 * le fond personnalisé pour éviter que deux systèmes de couleurs se contredisent.
 */
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
            lightText = Color.parseColor("#111111"),
            darkHint = Color.parseColor("#C9C1B4"),
            lightHint = Color.parseColor("#55514B")
        ),
        HpTheme(
            id = "steel_blue",
            label = "Acier Bleu",
            darkBackground = Color.parseColor("#071018"),
            darkPanel = Color.parseColor("#12202C"),
            lightBackground = Color.parseColor("#EAF1F6"),
            lightPanel = Color.parseColor("#F8FCFF"),
            accent = Color.parseColor("#397FAE"),
            accentLight = Color.parseColor("#8FD2FF"),
            darkText = Color.parseColor("#EFF8FF"),
            lightText = Color.parseColor("#10202C"),
            darkHint = Color.parseColor("#AFC6D6"),
            lightHint = Color.parseColor("#506674")
        )
    )

    fun current(context: Context): HpTheme {
        val id = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_THEME, DEFAULT_THEME) ?: DEFAULT_THEME
        return themes.firstOrNull { it.id == id } ?: themes.first()
    }

    fun set(context: Context, theme: HpTheme) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_THEME, theme.id)
            // Le thème est maintenant la source de vérité. Une ancienne couleur
            // personnalisée ne doit pas masquer sa palette au prochain affichage.
            .remove("app_bg")
            .putBoolean("custom_bg", false)
            .putBoolean("custom_image_bg", false)
            .commit()
    }
}
