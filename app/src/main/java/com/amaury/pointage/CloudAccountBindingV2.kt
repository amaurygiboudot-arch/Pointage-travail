package com.amaury.pointage

import android.content.Context

/**
 * Association locale entre les données AGKGMG de cet appareil et le compte cloud autorisé.
 *
 * Cette association est volontairement propre à l'appareil et n'est jamais sauvegardée :
 * une déconnexion ne doit pas permettre à un autre compte d'envoyer silencieusement les
 * données locales existantes vers son propre espace Firebase.
 */
object CloudAccountBindingV2 {
    private const val PREFS = "cloud_account_binding_v2"
    private const val KEY_UID = "bound_uid"

    fun boundUid(context: Context): String? =
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_UID, null)
            ?.trim()
            ?.takeIf { it.isNotBlank() }

    fun isBoundTo(context: Context, uid: String): Boolean =
        uid.isNotBlank() && boundUid(context) == uid

    fun bind(context: Context, uid: String): Boolean {
        val normalized = uid.trim()
        if (normalized.isBlank()) return false
        return context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_UID, normalized)
            .commit()
    }
}
