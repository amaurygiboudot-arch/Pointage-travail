package com.amaury.pointage.v2

import android.content.Context

/**
 * Règles de la phase de test V2.
 *
 * Le mode test doit permettre d'utiliser l'application normalement avec les
 * données déjà présentes. Il ne doit jamais transformer une installation
 * existante en bac à sable jetable ni effacer silencieusement les réglages.
 */
object V2TestDataPolicy {
    private const val PREFS = "horatrack_v2_test_policy"
    private const val KEY_CONFIRMED = "preserve_existing_data"

    /** Les données utilisateur existantes sont toujours conservées en V2 test. */
    const val PRESERVE_EXISTING_DATA = true

    /** Une fonction normale reste disponible en mode test ; seuls les diagnostics s'ajoutent. */
    fun normalFeaturesEnabled(): Boolean = HoraTrackV2.ENABLED

    /** Marque l'installation comme protégée sans modifier aucun autre SharedPreferences. */
    fun ensurePreservation(context: Context) {
        if (!HoraTrackV2.ENABLED || !HoraTrackV2.TEST_MODE) return
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_CONFIRMED, true)
            .apply()
    }

    fun preservationConfirmed(context: Context): Boolean =
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_CONFIRMED, false)
}
