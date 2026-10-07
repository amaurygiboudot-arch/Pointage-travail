package com.amaury.pointage

import android.app.AlertDialog
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/** Displays system truth; never requests or silently changes a permission. */
object SystemPermissionsV2 {
    @Suppress("DEPRECATION")
    fun show(context: Context) {
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 16, 24, 16)
        }
        fun label(value: String) { box.addView(TextView(context).apply { text = value; setPadding(0, 12, 0, 12) }) }
        fun open(intent: Intent) {
            try { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            catch (_: android.content.ActivityNotFoundException) {
                Toast.makeText(context, "Réglage indisponible sur cet appareil", Toast.LENGTH_LONG).show()
            }
        }
        fun button(title: String, intent: Intent) {
            box.addView(Button(context).apply { text = title; isAllCaps = false; setOnClickListener { open(intent) } })
        }
        val pm = context.packageManager
        val permissions = runCatching { pm.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions?.toList().orEmpty() }.getOrDefault(emptyList())
        val dangerous = permissions.mapNotNull { name -> runCatching { pm.getPermissionInfo(name, 0) }.getOrNull() }
            .filter { it.protectionLevel and PermissionInfo.PROTECTION_MASK_BASE == PermissionInfo.PROTECTION_DANGEROUS }
        label("Autorisations déclarées par cette application. Leur état est géré par Android. Rouvre ce panneau après une modification pour actualiser les états.")
        dangerous.forEach { permission ->
            val granted = ContextCompat.checkSelfPermission(context, permission.name) == PackageManager.PERMISSION_GRANTED
            label("${permission.loadLabel(pm)} : ${if (granted) "autorisée" else "non autorisée"}")
        }
        if (dangerous.isEmpty()) label("Aucune autorisation sensible déclarée.")
        button("Gérer les autorisations Android", Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
        val enabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
        label("Notifications : ${if (enabled) "autorisées" else "désactivées"}")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            button("Gérer les notifications", Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notificationChannels.sortedBy { it.name.toString() }.forEach { channel ->
                val state = if (channel.importance == NotificationManager.IMPORTANCE_NONE) "canal désactivé" else "canal configuré"
                button("${channel.name} — $state", Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName).putExtra(Settings.EXTRA_CHANNEL_ID, channel.id))
            }
        }
        val dialog = AlertDialog.Builder(context).setTitle("Autorisations et confidentialité")
            .setView(ScrollView(context).apply { addView(box) }).setPositiveButton("Fermer", null).show()
        PersonalizationRuntimeV2.track(dialog)
    }
}
