package com.amaury.pointage

import android.content.Context
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * Point d'entrée FCM unique. La variante Google Play conserve les alertes backend,
 * mais ignore toujours les mises à jour APK internes via le BuildConfig existant.
 */
class HoraTrackFirebaseMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        super.onNewToken(token)
        BackendUpdateNotifier.initialize(this)
        FirebaseUpdatePush.initialize(this)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        when (message.data["kind"].orEmpty()) {
            "backend_update" -> BackendUpdateNotifier.handlePush(this, message.data)
            "update", "" -> handleApkUpdate(message)
        }
    }

    private fun handleApkUpdate(message: RemoteMessage) {
        if (!UpdateChecker.INTERNAL_APK_UPDATES_ENABLED) return
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
