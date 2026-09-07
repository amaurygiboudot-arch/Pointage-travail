package com.amaury.pointage

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import java.lang.ref.WeakReference
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Avertit l'utilisateur lorsqu'un nouveau backend Firebase HoraTrack a été déployé.
 *
 * Deux chemins complémentaires sont utilisés :
 *  - FCM pour l'alerte rapide ;
 *  - lecture serveur Firestore au prochain passage dans l'application si le push a été raté.
 *
 * Une même révision ne produit qu'un popup et qu'une notification Android.
 */
object FirebaseBackendUpdateNotice : Application.ActivityLifecycleCallbacks {
    const val TOPIC = "horatrack_backend_updates"
    const val KIND = "backend_update"

    private const val CHANNEL_ID = "horatrack_backend_updates"
    private const val NOTIFICATION_ID = 9701
    private const val REQUEST_NOTIFICATIONS = 1407
    private const val PREFS = "firebase_backend_updates"
    private const val KEY_KNOWN_REVISION = "known_revision"
    private const val KEY_PENDING_REVISION = "pending_revision"
    private const val KEY_PENDING_TITLE = "pending_title"
    private const val KEY_PENDING_BODY = "pending_body"
    private const val KEY_PENDING_DEPLOYED_AT = "pending_deployed_at"
    private const val KEY_POPUP_REVISION = "popup_revision"
    private const val KEY_NOTIFICATION_REVISION = "notification_revision"
    private const val KEY_PERMISSION_REQUESTED = "notification_permission_requested"
    private const val KEY_LAST_SERVER_CHECK = "last_server_check"
    private const val SERVER_CHECK_INTERVAL_MS = 15L * 60L * 1000L

    @Volatile private var initialized = false
    @Volatile private var currentActivity: WeakReference<Activity>? = null

    fun initialize(context: Context) {
        if (FirebaseApp.getApps(context).isEmpty()) return
        createChannel(context)
        runCatching { FirebaseMessaging.getInstance().subscribeToTopic(TOPIC) }

        if (!initialized) {
            synchronized(this) {
                if (!initialized) {
                    (context.applicationContext as? Application)?.registerActivityLifecycleCallbacks(this)
                    initialized = true
                }
            }
        }
    }

    fun onPush(context: Context, data: Map<String, String>) {
        val revision = data["revision"].orEmpty().trim()
        if (revision.isBlank()) return
        val title = data["title"].orEmpty().ifBlank { "Mise à jour Firebase terminée" }
        val body = data["body"].orEmpty().ifBlank { "Le backend Firebase HoraTrack vient d'être mis à jour avec succès." }
        val deployedAtMs = data["deployedAtMs"]?.toLongOrNull() ?: System.currentTimeMillis()
        recordNewRevision(context.applicationContext, revision, title, body, deployedAtMs)
    }

    override fun onActivityResumed(activity: Activity) {
        currentActivity = WeakReference(activity)
        if (activity !is MainActivity) return
        ensureNotificationPermission(activity)
        ensurePendingNotification(activity)
        showPendingPopup(activity)
        checkLatestBackendRelease(activity)
    }

    override fun onActivityPaused(activity: Activity) {
        if (currentActivity?.get() === activity) currentActivity = null
    }

    private fun checkLatestBackendRelease(activity: Activity) {
        if (FirebaseApp.getApps(activity).isEmpty()) return
        val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val lastCheck = prefs.getLong(KEY_LAST_SERVER_CHECK, 0L)
        if (now - lastCheck < SERVER_CHECK_INTERVAL_MS) return
        prefs.edit().putLong(KEY_LAST_SERVER_CHECK, now).apply()

        FirebaseFirestore.getInstance()
            .collection("system")
            .document("backend_release")
            .get(Source.SERVER)
            .addOnSuccessListener { snapshot ->
                val revision = snapshot.getString("revision").orEmpty().trim()
                if (revision.isBlank()) return@addOnSuccessListener
                val title = snapshot.getString("title").orEmpty().ifBlank { "Mise à jour Firebase terminée" }
                val body = snapshot.getString("body").orEmpty().ifBlank { "Le backend Firebase HoraTrack a été mis à jour avec succès." }
                val deployedAtMs = snapshot.getLong("deployedAtMs") ?: 0L
                val current = prefs.getString(KEY_KNOWN_REVISION, "").orEmpty()
                val pending = prefs.getString(KEY_PENDING_REVISION, "").orEmpty()

                // Première installation : mémorise la révision courante sans faire passer
                // une ancienne mise à jour pour une nouveauté.
                if (current.isBlank() && pending.isBlank()) {
                    prefs.edit().putString(KEY_KNOWN_REVISION, revision).apply()
                    return@addOnSuccessListener
                }
                if (revision != current) {
                    recordNewRevision(activity.applicationContext, revision, title, body, deployedAtMs)
                }
            }
    }

    private fun recordNewRevision(
        context: Context,
        revision: String,
        title: String,
        body: String,
        deployedAtMs: Long
    ) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val known = prefs.getString(KEY_KNOWN_REVISION, "").orEmpty()
        val pending = prefs.getString(KEY_PENDING_REVISION, "").orEmpty()
        if (revision == known && revision != pending) return

        prefs.edit()
            .putString(KEY_KNOWN_REVISION, revision)
            .putString(KEY_PENDING_REVISION, revision)
            .putString(KEY_PENDING_TITLE, title.take(120))
            .putString(KEY_PENDING_BODY, body.take(500))
            .putLong(KEY_PENDING_DEPLOYED_AT, deployedAtMs)
            .apply()

        showNotification(context, revision, title, body)
        currentActivity?.get()?.takeIf { it is MainActivity && !it.isFinishing && !it.isDestroyed }?.let { activity ->
            activity.runOnUiThread { showPendingPopup(activity) }
        }
    }

    private fun ensurePendingNotification(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val revision = prefs.getString(KEY_PENDING_REVISION, "").orEmpty()
        if (revision.isBlank()) return
        if (prefs.getString(KEY_NOTIFICATION_REVISION, "") == revision) return
        showNotification(
            context,
            revision,
            prefs.getString(KEY_PENDING_TITLE, null),
            prefs.getString(KEY_PENDING_BODY, null)
        )
    }

    private fun showNotification(context: Context, revision: String, title: String?, body: String?) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getString(KEY_NOTIFICATION_REVISION, "") == revision) {
            clearPendingIfFullyShown(context, revision)
            return
        }
        createChannel(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return

        val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: Intent(context, LaunchActivity::class.java)
        launchIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        val pendingIntent = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val text = body?.takeIf { it.isNotBlank() }
            ?: "Le backend Firebase HoraTrack a été mis à jour avec succès."
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.hp_logo_vector)
            .setContentTitle(title?.takeIf { it.isNotBlank() } ?: "Mise à jour Firebase terminée")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$text\nRévision ${shortRevision(revision)}"))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIFICATION_ID, notification)
        prefs.edit().putString(KEY_NOTIFICATION_REVISION, revision).apply()
        clearPendingIfFullyShown(context, revision)
    }

    private fun showPendingPopup(activity: Activity) {
        val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val revision = prefs.getString(KEY_PENDING_REVISION, "").orEmpty()
        if (revision.isBlank() || prefs.getString(KEY_POPUP_REVISION, "") == revision) return
        if (activity.isFinishing || activity.isDestroyed) return

        val title = prefs.getString(KEY_PENDING_TITLE, null)?.takeIf { it.isNotBlank() }
            ?: "Mise à jour Firebase terminée"
        val body = prefs.getString(KEY_PENDING_BODY, null)?.takeIf { it.isNotBlank() }
            ?: "Le backend Firebase HoraTrack a été mis à jour avec succès."
        val deployedAtMs = prefs.getLong(KEY_PENDING_DEPLOYED_AT, 0L)
        val whenText = if (deployedAtMs > 0L) {
            SimpleDateFormat("dd/MM/yyyy à HH:mm", Locale.FRANCE).format(Date(deployedAtMs))
        } else null

        prefs.edit().putString(KEY_POPUP_REVISION, revision).apply()
        AlertDialog.Builder(activity)
            .setTitle(title)
            .setMessage(buildString {
                append(body)
                append("\n\nRévision Firebase : ")
                append(shortRevision(revision))
                if (whenText != null) {
                    append("\nDéployée le ")
                    append(whenText)
                }
                append("\n\nAucune action n'est nécessaire.")
            })
            .setPositiveButton("OK", null)
            .setOnDismissListener { clearPendingIfFullyShown(activity, revision) }
            .show()
    }

    private fun clearPendingIfFullyShown(context: Context, revision: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getString(KEY_POPUP_REVISION, "") != revision ||
            prefs.getString(KEY_NOTIFICATION_REVISION, "") != revision
        ) return
        if (prefs.getString(KEY_PENDING_REVISION, "") != revision) return
        prefs.edit()
            .remove(KEY_PENDING_REVISION)
            .remove(KEY_PENDING_TITLE)
            .remove(KEY_PENDING_BODY)
            .remove(KEY_PENDING_DEPLOYED_AT)
            .apply()
    }

    private fun ensureNotificationPermission(activity: Activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_PERMISSION_REQUESTED, false)) return
        prefs.edit().putBoolean(KEY_PERMISSION_REQUESTED, true).apply()
        activity.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
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
                description = "Avertit lorsqu'un nouveau backend Firebase HoraTrack est déployé"
            }
        )
    }

    private fun shortRevision(revision: String): String = revision.take(10)

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}

/** Service FCM conservé uniquement dans la variante Google Play pour les alertes backend. */
class BackendFirebaseMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        super.onNewToken(token)
        FirebaseUpdatePush.initialize(this)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        if (message.data["kind"].orEmpty() == FirebaseBackendUpdateNotice.KIND) {
            FirebaseBackendUpdateNotice.onPush(this, message.data)
        }
    }
}
