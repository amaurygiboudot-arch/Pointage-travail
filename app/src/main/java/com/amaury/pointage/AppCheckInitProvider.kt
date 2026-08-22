package com.amaury.pointage

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory

/**
 * Installe App Check avant que les écrans et services de l'application
 * commencent à utiliser Firebase. Le contrôle strict côté Firebase reste
 * désactivé tant que les métriques App Check n'ont pas été vérifiées.
 */
class AppCheckInitProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        val appContext = context?.applicationContext ?: return false
        val prefs = appContext.getSharedPreferences("app_check_status", 0)

        return runCatching {
            FirebaseApp.initializeApp(appContext)
            val appCheck = FirebaseAppCheck.getInstance()
            appCheck.installAppCheckProviderFactory(
                PlayIntegrityAppCheckProviderFactory.getInstance()
            )
            appCheck.setTokenAutoRefreshEnabled(true)

            prefs.edit()
                .putString("state", "initializing")
                .remove("error")
                .putLong("checked_at", System.currentTimeMillis())
                .apply()

            // Force une vraie attestation au démarrage. Aucun jeton n'est stocké.
            appCheck.getAppCheckToken(true)
                .addOnSuccessListener {
                    prefs.edit()
                        .putString("state", "valid")
                        .remove("error")
                        .putLong("checked_at", System.currentTimeMillis())
                        .apply()
                }
                .addOnFailureListener { error ->
                    prefs.edit()
                        .putString("state", "error")
                        .putString(
                            "error",
                            "${error.javaClass.simpleName}: ${error.message.orEmpty()}".take(500)
                        )
                        .putLong("checked_at", System.currentTimeMillis())
                        .apply()
                }
            true
        }.getOrElse { error ->
            prefs.edit()
                .putString("state", "error")
                .putString(
                    "error",
                    "${error.javaClass.simpleName}: ${error.message.orEmpty()}".take(500)
                )
                .putLong("checked_at", System.currentTimeMillis())
                .apply()
            true
        }
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0
}
