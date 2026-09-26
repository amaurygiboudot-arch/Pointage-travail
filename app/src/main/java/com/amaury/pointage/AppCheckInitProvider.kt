package com.amaury.pointage

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.AppCheckProviderFactory
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory

/**
 * Installe App Check avant que les écrans et services de l'application
 * commencent à utiliser Firebase.
 *
 * Les builds release/play utilisent Play Integrity. Seule la build Android
 * réellement debuggable utilise le fournisseur App Check debug, dont la
 * dépendance n'est pas embarquée dans les variantes de production.
 */
class AppCheckInitProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        val appContext = context?.applicationContext ?: return false
        val prefs = appContext.getSharedPreferences("app_check_status", 0)

        return runCatching {
            FirebaseApp.initializeApp(appContext)
            val appCheck = FirebaseAppCheck.getInstance()
            appCheck.installAppCheckProviderFactory(providerFactory())
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

    private fun providerFactory(): AppCheckProviderFactory {
        if (!BuildConfig.DEBUG) {
            return PlayIntegrityAppCheckProviderFactory.getInstance()
        }

        // La classe n'existe que sur le classpath debug grâce à debugImplementation.
        // La réflexion évite toute référence de compilation/packaging dans release/play.
        val providerClass = Class.forName(
            "com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory"
        )
        val instance = providerClass.getMethod("getInstance").invoke(null)
        return instance as? AppCheckProviderFactory
            ?: error("Fournisseur Firebase App Check debug invalide")
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
