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
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

object CompanyBasePauseSettings {
    private const val PREFS = "salary_settings"

    data class PauseSlot(val startMinute: Int, val endMinute: Int) {
        val durationMinutes: Int
            get() {
                if (startMinute !in 0..1439 || endMinute !in 0..1439 || startMinute == endMinute) return 0
                return (if (endMinute > startMinute) endMinute - startMinute else (24 * 60 - startMinute) + endMinute).coerceIn(0, 240)
            }
    }

    private fun prefix(slot: Int) = if (slot == 2) "company2_" else "company_"
    private fun suffix(pauseIndex: Int) = if (pauseIndex == 2) "2" else ""
    private fun alarmKey(slot: Int, pauseIndex: Int, field: String) = prefix(slot) + "base_pause${suffix(pauseIndex)}_alarm_$field"

    fun startMinute(context: Context, slot: Int, pauseIndex: Int = 1): Int = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(prefix(slot) + "base_pause${suffix(pauseIndex)}_start", -1)
    fun endMinute(context: Context, slot: Int, pauseIndex: Int = 1): Int = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(prefix(slot) + "base_pause${suffix(pauseIndex)}_end", -1)
    fun pause(context: Context, slot: Int, pauseIndex: Int): PauseSlot? = PauseSlot(startMinute(context, slot, pauseIndex), endMinute(context, slot, pauseIndex)).takeIf { it.durationMinutes > 0 }
    fun baseMinutes(context: Context, slot: Int): Int = (1..2).sumOf { pause(context, slot, it)?.durationMinutes ?: 0 }.coerceIn(0, 480)

    fun alarmEnabled(context: Context, slot: Int, pauseIndex: Int): Boolean = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(alarmKey(slot, pauseIndex, "enabled"), false)
    fun alarmSound(context: Context, slot: Int, pauseIndex: Int): String = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(alarmKey(slot, pauseIndex, "sound"), "alarm") ?: "alarm"
    fun saveAlarm(context: Context, slot: Int, pauseIndex: Int, enabled: Boolean, sound: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(alarmKey(slot, pauseIndex, "enabled"), enabled).putString(alarmKey(slot, pauseIndex, "sound"), sound).apply()
    }

    fun savePause(context: Context, slot: Int, pauseIndex: Int, startMinute: Int, endMinute: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(prefix(slot) + "base_pause${suffix(pauseIndex)}_start", startMinute.coerceIn(0, 1439)).putInt(prefix(slot) + "base_pause${suffix(pauseIndex)}_end", endMinute.coerceIn(0, 1439)).apply()
    }

    fun clearPause(context: Context, slot: Int, pauseIndex: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(prefix(slot) + "base_pause${suffix(pauseIndex)}_start").remove(prefix(slot) + "base_pause${suffix(pauseIndex)}_end").remove(alarmKey(slot, pauseIndex, "enabled")).remove(alarmKey(slot, pauseIndex, "sound")).apply()
    }
    fun clear(context: Context, slot: Int) { clearPause(context, slot, 1); clearPause(context, slot, 2); CompanyPauseAlarmManager.scheduleAll(context) }

    fun label(context: Context, slot: Int): String {
        val pauses = (1..2).mapNotNull { index -> pause(context, slot, index)?.let { index to it } }
        if (pauses.isEmpty()) return "Aucune pause de base"
        val lines = pauses.joinToString("\n") { (index, p) ->
            val alarm = if (alarmEnabled(context, slot, index)) " • 🔔 alarme" else ""
            "Pause $index : ${format(p.startMinute)} – ${format(p.endMinute)} • ${p.durationMinutes} min$alarm"
        }
        return "$lines\nTotal automatiquement déduit : ${baseMinutes(context, slot)} min"
    }
    private fun format(minutes: Int): String = String.format(Locale.FRANCE, "%02d:%02d", minutes / 60, minutes % 60)
}

class CompanyBasePauseView(context: Context) : LinearLayout(context) {
    private val prefs = context.getSharedPreferences("salary_settings", Context.MODE_PRIVATE)
    init { orientation = VERTICAL; setPadding(0, dp(10), 0, dp(6)); tag = "company_base_pause_view"; refresh() }

    fun refresh() {
        removeAllViews()
        addView(TextView(context).apply { text = "PAUSES DE BASE ENTREPRISE"; textSize = 15f; setTypeface(typeface, Typeface.BOLD) })
        addView(TextView(context).apply { text = "Tu peux définir 2 pauses fixes et une alarme personnalisée au début de chacune. Elles sont automatiquement retirées des heures travaillées ; les autres pauses restent en supplément."; textSize = 12f; setPadding(0, dp(4), 0, dp(6)) })
        addCompany(1); if (companyExists(2)) addCompany(2)
    }

    private fun companyExists(slot: Int): Boolean { val prefix = if (slot == 2) "company2_" else "company_"; return prefs.getString(prefix + "name", "").orEmpty().isNotBlank() || prefs.getString(prefix + "siret", "").orEmpty().isNotBlank() }
    private fun addCompany(slot: Int) {
        val prefix = if (slot == 2) "company2_" else "company_"
        val name = prefs.getString(prefix + "name", "").orEmpty().ifBlank { if (slot == 1) "Entreprise principale" else "Entreprise 2" }
        addView(TextView(context).apply { text = "$name\n${CompanyBasePauseSettings.label(context, slot)}"; textSize = 13f; setPadding(dp(8), dp(6), dp(8), dp(4)) }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(5) })
        addView(Button(context).apply { text = "RÉGLER PAUSES ET ALARMES"; isAllCaps = false; setBackgroundResource(R.drawable.hp_panel); setOnClickListener { showDialog(slot, name) } }, LayoutParams(LayoutParams.MATCH_PARENT, dp(54)))
    }

    private data class PauseFields(val start: EditText, val end: EditText, val alarm: CheckBox, val sound: Button, var soundValue: String)

    private fun showDialog(slot: Int, name: String) {
        val box = LinearLayout(context).apply { orientation = VERTICAL; setPadding(dp(20), dp(14), dp(20), dp(8)) }
        box.addView(TextView(context).apply { text = "Pauses et alarmes • $name"; textSize = 16f; setTypeface(typeface, Typeface.BOLD) })
        fun timeInput(hintText: String, saved: Int) = EditText(context).apply { hint = hintText; inputType = InputType.TYPE_CLASS_DATETIME or InputType.TYPE_DATETIME_VARIATION_TIME; isSingleLine = true; if (saved >= 0) setText(String.format(Locale.FRANCE, "%02d:%02d", saved / 60, saved % 60)) }
        fun soundLabel(value: String) = when (value) { "ringtone" -> "Son : Sonnerie du téléphone"; "notification" -> "Son : Notification"; else -> "Son : Alarme système" }
        fun addPauseFields(index: Int): PauseFields {
            box.addView(TextView(context).apply { text = "Pause $index"; textSize = 14f; setTypeface(typeface, Typeface.BOLD); setPadding(0, dp(if (index == 1) 10 else 16), 0, 0) })
            val start = timeInput("Début — ex. ${if (index == 1) "10:00" else "12:00"}", CompanyBasePauseSettings.startMinute(context, slot, index))
            val end = timeInput("Fin — ex. ${if (index == 1) "10:15" else "12:30"}", CompanyBasePauseSettings.endMinute(context, slot, index))
            val alarm = CheckBox(context).apply { text = "🔔 Sonner + notifier au début"; isChecked = CompanyBasePauseSettings.alarmEnabled(context, slot, index) }
            var soundValue = CompanyBasePauseSettings.alarmSound(context, slot, index)
            val sound = Button(context).apply { text = soundLabel(soundValue); isAllCaps = false; setBackgroundResource(R.drawable.hp_panel) }
            val fields = PauseFields(start, end, alarm, sound, soundValue)
            sound.setOnClickListener {
                fields.soundValue = when (fields.soundValue) { "alarm" -> "ringtone"; "ringtone" -> "notification"; else -> "alarm" }
                sound.text = soundLabel(fields.soundValue)
            }
            box.addView(start); box.addView(end); box.addView(alarm); box.addView(sound, LayoutParams(LayoutParams.MATCH_PARENT, dp(48)))
            return fields
        }
        val p1 = addPauseFields(1); val p2 = addPauseFields(2)
        AlertDialog.Builder(context).setView(box).setPositiveButton("Enregistrer") { _, _ ->
            val entries = listOf(1 to p1, 2 to p2); var invalid = false
            entries.forEach { (index, f) ->
                val rawStart = f.start.text.toString(); val rawEnd = f.end.text.toString(); val bothBlank = rawStart.isBlank() && rawEnd.isBlank(); val s = parse(rawStart); val e = parse(rawEnd)
                when { bothBlank -> CompanyBasePauseSettings.clearPause(context, slot, index); s == null || e == null || s == e -> invalid = true; else -> { CompanyBasePauseSettings.savePause(context, slot, index, s, e); CompanyBasePauseSettings.saveAlarm(context, slot, index, f.alarm.isChecked, f.soundValue) } }
            }
            if (invalid) {
                Toast.makeText(context, "Chaque pause renseignée doit avoir une heure de début et de fin valides", Toast.LENGTH_LONG).show()
            } else {
                CompanyPauseAlarmManager.scheduleAll(context)
                if (p1.alarm.isChecked || p2.alarm.isChecked) requestAlarmPermissionsIfNeeded()
                refresh()
                Toast.makeText(context, "Pauses, alarmes et notifications enregistrées", Toast.LENGTH_SHORT).show()
            }
        }.setNeutralButton("Tout supprimer") { _, _ -> CompanyBasePauseSettings.clear(context, slot); refresh() }.setNegativeButton("Annuler", null).show()
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
                        activity.startActivity(
                            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                                data = Uri.parse("package:${context.packageName}")
                            }
                        )
                    }
                }, 700L)
            }
        }
    }

    private fun parse(value: String): Int? { val m = Regex("^\\s*(\\d{1,2})[:hH](\\d{2})\\s*$").matchEntire(value) ?: return null; val h = m.groupValues[1].toIntOrNull() ?: return null; val min = m.groupValues[2].toIntOrNull() ?: return null; if (h !in 0..23 || min !in 0..59) return null; return h * 60 + min }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}

object CompanyBasePauseInstaller {
    fun install(panel: SalaryPanelView) {
        if (panel.findViewWithTag<View>("company_base_pause_view") != null) { panel.findViewWithTag<CompanyBasePauseView>("company_base_pause_view")?.refresh(); return }
        var enterpriseIndex = -1; for (i in 0 until panel.childCount) if (panel.getChildAt(i) is EnterpriseLookupView) { enterpriseIndex = i; break }
        val view = CompanyBasePauseView(panel.context); val index = if (enterpriseIndex >= 0) enterpriseIndex + 1 else minOf(4, panel.childCount)
        panel.addView(view, index, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }
}
