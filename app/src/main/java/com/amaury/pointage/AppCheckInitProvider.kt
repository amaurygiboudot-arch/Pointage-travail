package com.amaury.pointage

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Build
import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.FirebaseAppCheck

/**
 * Installe App Check avant que les écrans et services de l'application
 * commencent à utiliser Firebase. Le contrôle strict côté Firebase reste
 * désactivé tant que les métriques App Check n'ont pas été vérifiées sur tous
 * les canaux de distribution supportés.
 *
 * Important : un échec App Check ne bloque jamais le stockage/pointage local.
 */
class AppCheckInitProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        val appContext = context?.applicationContext ?: return false
        val prefs = appContext.getSharedPreferences("app_check_status", 0)
        val installer = installerPackage(appContext.packageManager, appContext.packageName)
        val providerName = AppCheckBuildProvider.name

        prefs.edit()
            .putString("provider", providerName)
            .putString("installer", installer ?: "sideload_or_unknown")
            .putBoolean("local_pointage_fail_open", true)
            .apply()

        return runCatching {
            FirebaseApp.initializeApp(appContext)
            val appCheck = FirebaseAppCheck.getInstance()
            // La classe concrète est fournie séparément par src/debug ou src/release.
            // Ainsi le classpath release ne référence jamais firebase-appcheck-debug.
            AppCheckBuildProvider.install(appCheck)
            appCheck.setTokenAutoRefreshEnabled(true)

            prefs.edit()
                .putString("state", "initializing")
                .remove("error")
                .putLong("checked_at", System.currentTimeMillis())
                .apply()

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

    private fun installerPackage(
        packageManager: android.content.pm.PackageManager,
        packageName: String
    ): String? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            packageManager.getInstallSourceInfo(packageName).installingPackageName
        } else {
            @Suppress("DEPRECATION")
            packageManager.getInstallerPackageName(packageName)
        }
    }.getOrNull()

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
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
