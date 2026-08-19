package com.amaury.pointage

import android.content.Context

/**
 * Point d'accès unique pour la palette jour/nuit.
 *
 * Conserve la compatibilité avec les widgets et fonds qui utilisent encore
 * ThemeDayNight, tout en déléguant la décision au catalogue de thèmes actuel.
 */
object ThemeDayNight {
    fun isDark(context: Context): Boolean = AppThemeCatalog.useDarkPalette(context)
}
