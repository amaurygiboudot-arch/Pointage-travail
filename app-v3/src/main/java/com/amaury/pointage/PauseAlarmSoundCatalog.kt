package com.amaury.pointage

import android.content.Context
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build

/**
 * Sons disponibles pour les alarmes de pause, classés volontairement du plus discret
 * au plus insistant. Le classement repose sur la famille Android du son :
 * notification -> sonnerie -> alarme. Les titres exacts dépendent du téléphone.
 */
object PauseAlarmSoundCatalog {
    data class Sound(
        val id: String,
        val label: String,
        val uri: Uri,
        val level: Int
    )

    private var preview: Ringtone? = null

    fun sounds(context: Context): List<Sound> {
        val result = mutableListOf<Sound>()
        collect(context, RingtoneManager.TYPE_NOTIFICATION, 1, "Discret", result)
        collect(context, RingtoneManager.TYPE_RINGTONE, 2, "Moyen", result)
        collect(context, RingtoneManager.TYPE_ALARM, 3, "Fort", result)

        // Dédoublonne les URI qui apparaissent dans plusieurs familles sur certains téléphones.
        return result.distinctBy { it.uri.toString() }
    }

    fun resolve(context: Context, savedId: String?): Sound {
        val list = sounds(context)
        if (!savedId.isNullOrBlank()) {
            list.firstOrNull { it.id == savedId || it.uri.toString() == savedId }?.let { return it }
        }

        // Compatibilité avec les anciennes valeurs enregistrées.
        val legacyType = when (savedId) {
            "notification" -> RingtoneManager.TYPE_NOTIFICATION
            "ringtone" -> RingtoneManager.TYPE_RINGTONE
            else -> RingtoneManager.TYPE_ALARM
        }
        val legacyUri = RingtoneManager.getDefaultUri(legacyType)
        list.firstOrNull { it.uri == legacyUri }?.let { return it }
        return list.firstOrNull { it.level == 2 }
            ?: list.firstOrNull()
            ?: Sound("default_alarm", "Fort • Alarme système", RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM), 3)
    }

    fun preview(context: Context, sound: Sound) {
        stopPreview()
        val ringtone = runCatching { RingtoneManager.getRingtone(context.applicationContext, sound.uri) }.getOrNull() ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            ringtone.audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        }
        preview = ringtone
        runCatching { ringtone.play() }
    }

    fun stopPreview() {
        preview?.let { runCatching { it.stop() } }
        preview = null
    }

    private fun collect(context: Context, type: Int, level: Int, levelName: String, out: MutableList<Sound>) {
        val manager = RingtoneManager(context).apply { setType(type) }
        val cursor = runCatching { manager.cursor }.getOrNull() ?: return
        cursor.use {
            while (it.moveToNext()) {
                val position = it.position
                val uri = runCatching { manager.getRingtoneUri(position) }.getOrNull() ?: continue
                val title = runCatching { RingtoneManager.getRingtone(context, uri)?.getTitle(context) }
                    .getOrNull().orEmpty().trim().ifBlank { "Son ${position + 1}" }
                out += Sound(uri.toString(), "$levelName • $title", uri, level)
            }
        }
    }
}
