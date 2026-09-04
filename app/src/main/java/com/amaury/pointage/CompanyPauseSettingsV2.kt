package com.amaury.pointage

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.text.InputType
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

/** Réglages de pauses propres à une entreprise V2, identifiée par son ID stable. */
object CompanyPauseSettingsV2 {
    private const val MIGRATION_KEY = "base_pauses_v2_migrated"

    data class PauseSlot(val startMinute: Int, val endMinute: Int) {
        val durationMinutes: Int
            get() {
                if (startMinute !in 0..1439 || endMinute !in 0..1439 || startMinute == endMinute) return 0
                return (if (endMinute > startMinute) endMinute - startMinute else 24 * 60 - startMinute + endMinute)
                    .coerceIn(0, 240)
            }
    }

    private fun prefs(context: Context, companyId: String) = SalaryCompanyStore.prefs(context, companyId)
    private fun suffix(pauseIndex: Int) = if (pauseIndex == 2) "2" else ""
    private fun startKey(pauseIndex: Int) = "base_pause${suffix(pauseIndex)}_start"
    private fun endKey(pauseIndex: Int) = "base_pause${suffix(pauseIndex)}_end"
    private fun alarmKey(pauseIndex: Int, field: String) = "base_pause${suffix(pauseIndex)}_alarm_$field"

    fun startMinute(context: Context, companyId: String, pauseIndex: Int = 1): Int {
        ensureMigrated(context, companyId)
        return prefs(context, companyId).getInt(startKey(pauseIndex), -1)
    }

    fun endMinute(context: Context, companyId: String, pauseIndex: Int = 1): Int {
        ensureMigrated(context, companyId)
        return prefs(context, companyId).getInt(endKey(pauseIndex), -1)
    }

    fun pause(context: Context, companyId: String, pauseIndex: Int): PauseSlot? =
        PauseSlot(startMinute(context, companyId, pauseIndex), endMinute(context, companyId, pauseIndex))
            .takeIf { it.durationMinutes > 0 }

    fun baseMinutes(context: Context, companyId: String): Int =
        (1..2).sumOf { pause(context, companyId, it)?.durationMinutes ?: 0 }.coerceIn(0, 480)

    fun alarmEnabled(context: Context, companyId: String, pauseIndex: Int): Boolean {
        ensureMigrated(context, companyId)
        return prefs(context, companyId).getBoolean(alarmKey(pauseIndex, "enabled"), false)
    }

    fun alarmSound(context: Context, companyId: String, pauseIndex: Int): String {
        ensureMigrated(context, companyId)
        return prefs(context, companyId).getString(alarmKey(pauseIndex, "sound"), "alarm") ?: "alarm"
    }

    fun saveAlarm(context: Context, companyId: String, pauseIndex: Int, enabled: Boolean, sound: String) {
        prefs(context, companyId).edit()
            .putBoolean(MIGRATION_KEY, true)
            .putBoolean(alarmKey(pauseIndex, "enabled"), enabled)
            .putString(alarmKey(pauseIndex, "sound"), sound)
            .apply()
    }

    fun savePause(context: Context, companyId: String, pauseIndex: Int, startMinute: Int, endMinute: Int) {
        prefs(context, companyId).edit()
            .putBoolean(MIGRATION_KEY, true)
            .putInt(startKey(pauseIndex), startMinute.coerceIn(0, 1439))
            .putInt(endKey(pauseIndex), endMinute.coerceIn(0, 1439))
            .apply()
    }

    fun clearPause(context: Context, companyId: String, pauseIndex: Int) {
        prefs(context, companyId).edit()
            .putBoolean(MIGRATION_KEY, true)
            .remove(startKey(pauseIndex))
            .remove(endKey(pauseIndex))
            .remove(alarmKey(pauseIndex, "enabled"))
            .remove(alarmKey(pauseIndex, "sound"))
            .apply()
    }

    fun clear(context: Context, companyId: String) {
        clearPause(context, companyId, 1)
        clearPause(context, companyId, 2)
        CompanyPauseAlarmManager.scheduleAll(context)
    }

    fun label(context: Context, companyId: String): String {
        val pauses = (1..2).mapNotNull { index -> pause(context, companyId, index)?.let { index to it } }
        if (pauses.isEmpty()) return "Aucune pause de base"
        val lines = pauses.joinToString("\n") { (index, p) ->
            val alarm = if (alarmEnabled(context, companyId, index)) " • 🔔 alarme" else ""
            "Pause $index : ${format(p.startMinute)} – ${format(p.endMinute)} • ${p.durationMinutes} min$alarm"
        }
        return "$lines\nTotal automatiquement déduit : ${baseMinutes(context, companyId)} min"
    }

    /**
     * Migration non destructive : les anciennes pauses des slots 1/2 sont copiées une fois dans
     * l'entreprise V2 correspondante. Les anciennes clés restent intactes jusqu'à la purge V1.
     */
    private fun ensureMigrated(context: Context, companyId: String) {
        val target = prefs(context, companyId)
        if (target.getBoolean(MIGRATION_KEY, false)) return
        val aliases = SalaryCompanyStore.acceptedEmployerIds(context, companyId)
        val legacySlot = when {
            "company_1" in aliases -> 1
            "company_2" in aliases -> 2
            else -> null
        }
        val editor = target.edit()
        if (legacySlot != null) {
            for (index in 1..2) {
                val pause = CompanyBasePauseSettings.pause(context, legacySlot, index)
                if (pause != null && !target.contains(startKey(index)) && !target.contains(endKey(index))) {
                    editor.putInt(startKey(index), pause.startMinute)
                    editor.putInt(endKey(index), pause.endMinute)
                    editor.putBoolean(alarmKey(index, "enabled"), CompanyBasePauseSettings.alarmEnabled(context, legacySlot, index))
                    editor.putString(alarmKey(index, "sound"), CompanyBasePauseSettings.alarmSound(context, legacySlot, index))
                }
            }
        }
        editor.putBoolean(MIGRATION_KEY, true).commit()
    }

    private fun format(minutes: Int): String =
        String.format(Locale.FRANCE, "%02d:%02d", minutes / 60, minutes % 60)
}

/** Interface V2 des pauses pour une seule entreprise. */
class CompanyPauseSettingsV2View(
    context: Context,
    private val companyId: String
) : LinearLayout(context) {

    private data class PauseFields(
        val start: EditText,
        val end: EditText,
        val alarm: CheckBox,
        val sound: Button,
        var soundValue: String
    )

    init {
        orientation = VERTICAL
        setPadding(0, dp(14), 0, dp(6))
        refresh()
    }

    fun refresh() {
        removeAllViews()
        addView(TextView(context).apply {
            text = "PAUSES DE BASE & ALARMES"
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
        })
        addView(TextView(context).apply {
            text = CompanyPauseSettingsV2.label(context, companyId)
            textSize = 13f
            setPadding(0, dp(5), 0, dp(6))
        })
        addView(Button(context).apply {
            text = "RÉGLER LES PAUSES ET ALARMES"
            isAllCaps = false
            setBackgroundResource(R.drawable.hp_panel)
            setOnClickListener { showDialog() }
        }, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)))
    }

    private fun showDialog() {
        val company = SalaryCompanyStore.list(context).firstOrNull { it.id == companyId }
        val companyName = company?.name?.ifBlank { "Entreprise" } ?: "Entreprise"
        PauseAlarmSoundCatalog.stopPreview()
        val box = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(20), dp(14), dp(20), dp(8))
        }
        box.addView(TextView(context).apply {
            text = "Pauses et alarmes • $companyName"
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
        })

        fun timeInput(hintText: String, saved: Int) = EditText(context).apply {
            hint = hintText
            inputType = InputType.TYPE_CLASS_DATETIME or InputType.TYPE_DATETIME_VARIATION_TIME
            isSingleLine = true
            if (saved >= 0) setText(String.format(Locale.FRANCE, "%02d:%02d", saved / 60, saved % 60))
        }

        fun addPauseFields(index: Int): PauseFields {
            box.addView(TextView(context).apply {
                text = "Pause $index"
                textSize = 14f
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, dp(if (index == 1) 10 else 16), 0, 0)
            })
            val start = timeInput(
                "Début — ex. ${if (index == 1) "10:00" else "12:00"}",
                CompanyPauseSettingsV2.startMinute(context, companyId, index)
            )
            val end = timeInput(
                "Fin — ex. ${if (index == 1) "10:15" else "12:30"}",
                CompanyPauseSettingsV2.endMinute(context, companyId, index)
            )
            val alarm = CheckBox(context).apply {
                text = "🔔 Sonner + notifier au début"
                isChecked = CompanyPauseSettingsV2.alarmEnabled(context, companyId, index)
            }
            val savedSound = CompanyPauseSettingsV2.alarmSound(context, companyId, index)
            val selected = PauseAlarmSoundCatalog.resolve(context, savedSound)
            val sound = Button(context).apply {
                text = "Son : ${selected.label}"
                isAllCaps = false
                setBackgroundResource(R.drawable.hp_panel)
            }
            val fields = PauseFields(start, end, alarm, sound, selected.id)
            sound.setOnClickListener { showSoundPicker(fields) }
            box.addView(start)
            box.addView(end)
            box.addView(alarm)
            box.addView(sound, LayoutParams(LayoutParams.MATCH_PARENT, dp(52)))
            return fields
        }

        val p1 = addPauseFields(1)
        val p2 = addPauseFields(2)
        val dialog = AlertDialog.Builder(context)
            .setView(box)
            .setPositiveButton("Enregistrer", null)
            .setNeutralButton("Tout supprimer", null)
            .setNegativeButton("Annuler", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val entries = listOf(1 to p1, 2 to p2)
                var invalid = false
                entries.forEach { (index, fields) ->
                    val rawStart = fields.start.text.toString()
                    val rawEnd = fields.end.text.toString()
                    val bothBlank = rawStart.isBlank() && rawEnd.isBlank()
                    val start = parse(rawStart)
                    val end = parse(rawEnd)
                    when {
                        bothBlank -> CompanyPauseSettingsV2.clearPause(context, companyId, index)
                        start == null || end == null || start == end -> invalid = true
                        else -> {
                            CompanyPauseSettingsV2.savePause(context, companyId, index, start, end)
                            CompanyPauseSettingsV2.saveAlarm(context, companyId, index, fields.alarm.isChecked, fields.soundValue)
                        }
                    }
                }
                if (invalid) {
                    Toast.makeText(context, "Chaque pause renseignée doit avoir une heure de début et de fin valides", Toast.LENGTH_LONG).show()
                } else {
                    PauseAlarmSoundCatalog.stopPreview()
                    CompanyPauseAlarmManager.scheduleAll(context)
                    if (p1.alarm.isChecked || p2.alarm.isChecked) requestAlarmPermissionsIfNeeded()
                    refresh()
                    dialog.dismiss()
                    Toast.makeText(context, "Pauses, alarmes et sons enregistrés", Toast.LENGTH_SHORT).show()
                }
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                PauseAlarmSoundCatalog.stopPreview()
                CompanyPauseSettingsV2.clear(context, companyId)
                refresh()
                dialog.dismiss()
            }
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
                PauseAlarmSoundCatalog.stopPreview()
                dialog.dismiss()
            }
        }
        dialog.setOnCancelListener { PauseAlarmSoundCatalog.stopPreview() }
        dialog.show()
    }

    private fun showSoundPicker(fields: PauseFields) {
        PauseAlarmSoundCatalog.stopPreview()
        val sounds = PauseAlarmSoundCatalog.sounds(context)
        if (sounds.isEmpty()) {
            Toast.makeText(context, "Aucun son système disponible sur ce téléphone", Toast.LENGTH_LONG).show()
            return
        }
        val box = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(8))
        }
        box.addView(TextView(context).apply {
            text = "Du plus discret au moins discret"
            textSize = 13f
            setPadding(0, 0, 0, dp(8))
        })
        val spinner = Spinner(context)
        spinner.adapter = ArrayAdapter(
            context,
            android.R.layout.simple_spinner_item,
            sounds.map { it.label }
        ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        spinner.setSelection(sounds.indexOfFirst { it.id == fields.soundValue }.takeIf { it >= 0 } ?: 0)
        box.addView(spinner, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        box.addView(Button(context).apply {
            text = "▶ PRÉ-ÉCOUTER"
            isAllCaps = false
            setBackgroundResource(R.drawable.hp_panel)
            setOnClickListener {
                sounds.getOrNull(spinner.selectedItemPosition)?.let { PauseAlarmSoundCatalog.preview(context, it) }
            }
        }, LayoutParams(LayoutParams.MATCH_PARENT, dp(50)).apply { topMargin = dp(10) })

        val dialog = AlertDialog.Builder(context)
            .setTitle("Choisir le son de l'alarme")
            .setView(box)
            .setPositiveButton("VALIDER", null)
            .setNegativeButton("ANNULER", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val selected = sounds.getOrNull(spinner.selectedItemPosition) ?: return@setOnClickListener
                PauseAlarmSoundCatalog.stopPreview()
                fields.soundValue = selected.id
                fields.sound.text = "Son : ${selected.label}"
                dialog.dismiss()
            }
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
                PauseAlarmSoundCatalog.stopPreview()
                dialog.dismiss()
            }
        }
        dialog.setOnCancelListener { PauseAlarmSoundCatalog.stopPreview() }
        dialog.show()
    }

    private fun requestAlarmPermissionsIfNeeded() {
        val activity = context as? Activity ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            activity.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1301)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            if (!alarm.canScheduleExactAlarms()) {
                postDelayed({
                    Toast.makeText(context, "Autorise ‘Alarmes et rappels’ pour que les pauses sonnent exactement à l’heure.", Toast.LENGTH_LONG).show()
                    runCatching {
                        activity.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                            data = Uri.parse("package:${context.packageName}")
                        })
                    }
                }, 700L)
            }
        }
    }

    private fun parse(value: String): Int? {
        val match = Regex("^\\s*(\\d{1,2})[:hH](\\d{2})\\s*$").matchEntire(value) ?: return null
        val hour = match.groupValues[1].toIntOrNull() ?: return null
        val minute = match.groupValues[2].toIntOrNull() ?: return null
        if (hour !in 0..23 || minute !in 0..59) return null
        return hour * 60 + minute
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
