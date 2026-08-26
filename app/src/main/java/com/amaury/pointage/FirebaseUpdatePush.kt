package com.amaury.pointage

import android.Manifest
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
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

object FirebaseUpdatePush {
    const val TOPIC = "hp_travail_updates"
    private const val CHANNEL_ID = "hp_travail_updates"

    fun initialize(context: Context) {
        if (FirebaseApp.getApps(context).isEmpty()) {
            val apiKey = BuildConfig.FIREBASE_API_KEY.trim()
            val appId = BuildConfig.FIREBASE_APP_ID.trim()
            val projectId = BuildConfig.FIREBASE_PROJECT_ID.trim()
            val senderId = BuildConfig.FIREBASE_SENDER_ID.trim()
            if (apiKey.isBlank() || appId.isBlank() || projectId.isBlank() || senderId.isBlank()) return

            runCatching {
                val options = FirebaseOptions.Builder()
                    .setApiKey(apiKey)
                    .setApplicationId(appId)
                    .setProjectId(projectId)
                    .setGcmSenderId(senderId)
                    .build()
                FirebaseApp.initializeApp(context, options)
            }.getOrNull() ?: return
        }

        createChannel(context)
        runCatching { FirebaseMessaging.getInstance().subscribeToTopic(TOPIC) }
    }

    private fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Mises à jour HoraTrack", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Avertit lorsqu'une nouvelle version de HoraTrack est disponible"
            }
        )
    }

    fun showUpdateNotification(context: Context, title: String?, body: String?) {
        createChannel(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return

        val launchIntent = Intent(context, LaunchActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("open_update_prompt", true)
        }
        val pending = PendingIntent.getActivity(
            context,
            9401,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.horatrack_notification)
            .setContentTitle(title?.takeIf { it.isNotBlank() } ?: "Mise à jour HoraTrack")
            .setContentText(body?.takeIf { it.isNotBlank() } ?: "Une nouvelle version est disponible. Appuie ici pour la mettre à jour.")
            .setStyle(NotificationCompat.BigTextStyle().bigText(body ?: "Une nouvelle version de HoraTrack est disponible. Ouvre l'application pour l'installer."))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()

        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(9401, notification)
    }
}

class HpFirebaseMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        super.onNewToken(token)
        FirebaseUpdatePush.initialize(this)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        val kind = message.data["kind"].orEmpty()
        if (kind.isNotBlank() && kind != "update") return

        getSharedPreferences("update_push", Context.MODE_PRIVATE).edit()
            .putString("version", message.data["version"].orEmpty())
            .putLong("received_at", System.currentTimeMillis())
            .apply()

        FirebaseUpdatePush.showUpdateNotification(
            this,
            message.data["title"] ?: message.notification?.title,
            message.data["body"] ?: message.notification?.body
        )
    }
}
