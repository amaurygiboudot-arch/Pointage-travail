package com.amaury.pointage

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.database.Cursor
import android.net.Uri
import android.os.Build
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import java.util.UUID

object DeviceRegistry {
    private const val PREFS = "firebase_device_registry"
    private const val KEY_INSTALL_ID = "install_id"
    private const val KEY_LAST_SYNC = "last_sync_ms"
    private const val KEY_LAST_ERROR = "last_error"
    private const val MIN_SYNC_INTERVAL_MS = 6L * 60L * 60L * 1000L

    fun installId(context: Context): String {
        val prefs = prefs(context)
        val existing = prefs.getString(KEY_INSTALL_ID, null)
        if (!existing.isNullOrBlank()) return existing

        val created = UUID.randomUUID().toString()
        prefs.edit().putString(KEY_INSTALL_ID, created).apply()
        return created
    }

    fun lastError(context: Context): String? =
        prefs(context).getString(KEY_LAST_ERROR, null)?.takeIf { it.isNotBlank() }

    fun registerIfSignedIn(context: Context, force: Boolean = false) {
        val user = FirebaseAuth.getInstance().currentUser ?: return
        val now = System.currentTimeMillis()
        val prefs = prefs(context)
        val lastSync = prefs.getLong(KEY_LAST_SYNC, 0L)
        if (!force && now - lastSync < MIN_SYNC_INTERVAL_MS) return

        val appContext = context.applicationContext
        val id = installId(appContext)
        val packageInfo = runCatching {
            appContext.packageManager.getPackageInfo(appContext.packageName, 0)
        }.getOrNull()
        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo?.longVersionCode ?: 0L
        } else {
            @Suppress("DEPRECATION")
            (packageInfo?.versionCode ?: 0).toLong()
        }

        val manufacturer = Build.MANUFACTURER.orEmpty().trim()
        val model = Build.MODEL.orEmpty().trim()
        val displayModel = listOf(manufacturer, model)
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .trim()
            .ifBlank { "Android" }

        val db = FirebaseFirestore.getInstance()
        val document = db.collection("users")
            .document(user.uid)
            .collection("devices")
            .document(id)

        db.runTransaction { transaction ->
            val snapshot = transaction.get(document)
            val data = hashMapOf<String, Any>(
                "installationId" to id,
                "displayModel" to displayModel,
                "manufacturer" to manufacturer,
                "model" to model,
                "device" to Build.DEVICE.orEmpty(),
                "product" to Build.PRODUCT.orEmpty(),
                "androidVersion" to Build.VERSION.RELEASE.orEmpty(),
                "sdkInt" to Build.VERSION.SDK_INT,
                "appVersionName" to (packageInfo?.versionName ?: "inconnue"),
                "appVersionCode" to versionCode,
                "packageName" to appContext.packageName,
                "platform" to "android",
                "active" to true,
                "lastSeenAt" to FieldValue.serverTimestamp()
            )
            if (!snapshot.exists()) {
                data["firstSeenAt"] = FieldValue.serverTimestamp()
            }
            transaction.set(document, data, com.google.firebase.firestore.SetOptions.merge())
        }.addOnSuccessListener {
            prefs.edit()
                .putLong(KEY_LAST_SYNC, System.currentTimeMillis())
                .remove(KEY_LAST_ERROR)
                .apply()
        }.addOnFailureListener { error ->
            prefs.edit()
                .putString(KEY_LAST_ERROR, error.localizedMessage ?: error.javaClass.simpleName)
                .apply()
        }
    }

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

class DeviceRegistryInitProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        val appContext = context?.applicationContext ?: return true
        val auth = FirebaseAuth.getInstance()

        // Firebase Auth may restore the saved session a little after process startup.
        // Listening for auth-state changes guarantees registration once that restore finishes.
        auth.addAuthStateListener { firebaseAuth ->
            if (firebaseAuth.currentUser != null) {
                DeviceRegistry.registerIfSignedIn(appContext)
            }
        }

        // Also try immediately for sessions that are already available.
        DeviceRegistry.registerIfSignedIn(appContext)
        return true
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
