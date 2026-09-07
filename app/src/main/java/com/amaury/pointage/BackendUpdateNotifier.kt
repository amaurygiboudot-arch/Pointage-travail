package com.amaury.pointage

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.messaging.FirebaseMessaging

/**
 * Avertit l'utilisateur lorsqu'un nouveau déploiement Firebase HoraTrack a réussi.
 * Le push FCM est immédiat; le callable backendRevision sert de rattrapage au lancement.
 */
object BackendUpdateNotifier {
    const val TOPIC = "horatrack_backend_updates"
    private const val CHANNEL_ID = "horatrack_backend_updates"
    private const val NOTIFICATION_ID = 9517
    private const val PREFS = "backend_update_alerts"
    private const val KEY_KNOWN_REVISION = "known_revision"
    private const val KEY_PENDING_REVISION = "pending_revision"
    private const val KEY_PENDING_TITLE = "pending_title"
    private const val KEY_PENDING_BODY = "pending_body"
    private const val KEY_NOTIFIED_REVISION = "notified_revision"
    private const val KEY_POPUP_REVISION = "popup_revision"
    private const val KEY_LAST_CHECK = "last_check_ms"
    private const val KEY_PERMISSION_REQUESTED = "notification_permission_requested"
    private const val CHECK_INTERVAL_MS = 15L * 60L * 1000L
    private const val NOTIFICATION_PERMISSION_REQUEST = 1421

    fun initialize(context: Context) {
        if (!ensureFirebase(context)) return
        createChannel(context)
        runCatching { FirebaseMessaging.getInstance().subscribeToTopic(TOPIC) }
    }

    fun check(activity: Activity) {
        initialize(activity.applicationContext)
        showPendingIfNeeded(activity)

        val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - prefs.getLong(KEY_LAST_CHECK, 0L) < CHECK_INTERVAL_MS) return
        prefs.edit().putLong(KEY_LAST_CHECK, now).apply()
        if (!ensureFirebase(activity)) return

        FirebaseFunctions.getInstance("us-central1")
            .getHttpsCallable("backendRevision")
            .call()
            .addOnSuccessListener { result ->
                val root = result.data as? Map<*, *> ?: return@addOnSuccessListener
                if (root["available"] != true) return@addOnSuccessListener
                val revision = root["revision"]?.toString()?.trim().orEmpty()
                if (revision.isBlank()) return@addOnSuccessListener
                val title = root["title"]?.toString()?.trim().orEmpty()
                val body = root["message"]?.toString()?.trim().orEmpty()
                observeRevision(activity, revision, title, body, forceAlert = false)
            }
    }

    fun handlePush(context: Context, data: Map<String, String>) {
        val revision = data["revision"].orEmpty().trim()
        if (revision.isBlank()) return
        initialize(context.applicationContext)
        val title = data["title"].orEmpty().trim()
        val body = data["body"].orEmpty().trim()
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_KNOWN_REVISION, revision)
            .putString(KEY_PENDING_REVISION, revision)
            .putString(KEY_PENDING_TITLE, title)
            .putString(KEY_PENDING_BODY, body)
            .apply()
        showNotificationIfNeeded(context, revision, title, body)
    }

    private fun observeRevision(
        activity: Activity,
        revision: String,
        title: String,
        body: String,
        forceAlert: Boolean
    ) {
        val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val known = prefs.getString(KEY_KNOWN_REVISION, "").orEmpty()
        val pending = prefs.getString(KEY_PENDING_REVISION, "").orEmpty()

        // Première installation de cette fonction : on prend une baseline sans fausse alerte.
        if (!forceAlert && known.isBlank() && pending.isBlank()) {
            prefs.edit().putString(KEY_KNOWN_REVISION, revision).apply()
            return
        }
        if (!forceAlert && revision == known && pending != revision) return

        prefs.edit()
            .putString(KEY_KNOWN_REVISION, revision)
            .putString(KEY_PENDING_REVISION, revision)
            .putString(KEY_PENDING_TITLE, title)
            .putString(KEY_PENDING_BODY, body)
            .apply()
        showPendingIfNeeded(activity)
    }

    private fun showPendingIfNeeded(activity: Activity) {
        if (activity.isFinishing || activity.isDestroyed) return
        val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val revision = prefs.getString(KEY_PENDING_REVISION, "").orEmpty()
        if (revision.isBlank()) return
        val title = prefs.getString(KEY_PENDING_TITLE, "").orEmpty()
        val body = prefs.getString(KEY_PENDING_BODY, "").orEmpty()

        showNotificationIfNeeded(activity, revision, title, body)
        requestNotificationPermissionIfNeeded(activity)

        if (prefs.getString(KEY_POPUP_REVISION, "").orEmpty() == revision) return
        prefs.edit().putString(KEY_POPUP_REVISION, revision).apply()
        val shortRevision = revision.take(8)
        val message = buildString {
            append(body.ifBlank { "Les services Firebase d’HoraTrack ont été mis à jour avec succès." })
            append("\n\nRévision : ")
            append(shortRevision)
            append("\n\nAucune action n’est nécessaire.")
        }
        AlertDialog.Builder(activity)
            .setTitle(title.ifBlank { "Mise à jour Firebase HoraTrack" })
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun requestNotificationPermissionIfNeeded(activity: Activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_PERMISSION_REQUESTED, false)) return
        prefs.edit().putBoolean(KEY_PERMISSION_REQUESTED, true).apply()
        activity.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIFICATION_PERMISSION_REQUEST)
    }

    private fun showNotificationIfNeeded(context: Context, revision: String, title: String, body: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getString(KEY_NOTIFIED_REVISION, "").orEmpty() == revision) return
        createChannel(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return

        val launchIntent = Intent(context, LaunchActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val text = body.ifBlank { "Les services Firebase d’HoraTrack ont été mis à jour avec succès." }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.hp_logo_vector)
            .setContentTitle(title.ifBlank { "Mise à jour Firebase HoraTrack" })
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIFICATION_ID, notification)
        prefs.edit().putString(KEY_NOTIFIED_REVISION, revision).apply()
    }

    private fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Mises à jour Firebase HoraTrack",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Avertit après une mise à jour réussie des services Firebase HoraTrack"
            }
        )
    }

    private fun ensureFirebase(context: Context): Boolean {
        if (FirebaseApp.getApps(context).isNotEmpty()) return true
        val apiKey = BuildConfig.FIREBASE_API_KEY.trim()
        val appId = BuildConfig.FIREBASE_APP_ID.trim()
        val projectId = BuildConfig.FIREBASE_PROJECT_ID.trim()
        val senderId = BuildConfig.FIREBASE_SENDER_ID.trim()
        if (apiKey.isBlank() || appId.isBlank() || projectId.isBlank() || senderId.isBlank()) return false
        return runCatching {
            FirebaseApp.initializeApp(
                context,
                FirebaseOptions.Builder()
                    .setApiKey(apiKey)
                    .setApplicationId(appId)
                    .setProjectId(projectId)
                    .setGcmSenderId(senderId)
                    .build()
            )
        }.getOrNull() != null
    }
}
