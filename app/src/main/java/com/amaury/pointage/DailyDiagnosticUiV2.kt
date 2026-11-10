package com.amaury.pointage

import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.content.ClipData
import android.content.Intent
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.widget.Switch
import androidx.core.content.FileProvider
import com.amaury.pointage.v2.V2RuntimeAccountScope
import java.io.File
import java.time.LocalDate
import java.util.UUID
import com.google.firebase.auth.FirebaseAuth

internal object DailyDiagnosticUiV2 {
    fun install(activity: MainActivity) {
        val section = SettingsV2Host.section(activity, SettingsV2Host.TAG_POINTAGE) ?: return
        if (section.findViewWithTag<Button>("daily_diagnostic_v2") != null) return
        section.addView(Button(activity).apply {
            tag = "daily_diagnostic_v2"
            text = "DIAGNOSTIC DE LA JOURNÉE"
            minHeight = (48 * resources.displayMetrics.density).toInt()
            setOnClickListener { show(activity) }
        })
    }
    private fun protect(dialog: AlertDialog, scope: String, onChange: () -> Unit = {}) {
        val auth = runCatching { FirebaseAuth.getInstance() }.getOrNull() ?: run { dialog.dismiss(); return }
        val listener = FirebaseAuth.AuthStateListener {
            if (scope != V2RuntimeAccountScope.currentScope()) { onChange(); dialog.dismiss() }
        }
        dialog.setOnDismissListener { auth.removeAuthStateListener(listener) }
        auth.addAuthStateListener(listener)
    }
    internal fun show(activity: Activity) {
        val scope = V2RuntimeAccountScope.currentScope() ?: return
        var mainDialog: AlertDialog? = null
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (20 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
        }
        fun notice(text: String) { Toast.makeText(activity, text, Toast.LENGTH_LONG).show() }
        File(activity.cacheDir, "daily_diagnostic_exports").listFiles()?.filter {
            it.lastModified() < System.currentTimeMillis() - 24 * 60 * 60 * 1000L
        }?.forEach { it.delete() }
        val state = DailyDiagnosticStoreV2.read(activity, scope)
        content.addView(TextView(activity).apply {
            text = "Journal local facultatif, séparé pour chaque compte. Conserve jusqu’à 14 jours et 2 000 événements (purge à la prochaine utilisation), sans coordonnées GPS ni nom de lieu. Il commence à l'activation : les journées passées ne sont pas reconstituées. Après partage ici, nous comparerons la trace à ton récit."
        })
        content.addView(Switch(activity).apply {
            text = "Enregistrer le diagnostic sur cet appareil"
            minHeight = (48 * resources.displayMetrics.density).toInt()
            isChecked = state.enabled
            setOnCheckedChangeListener { _, checked ->
                if (!DailyDiagnosticStoreV2.setEnabled(activity, scope, checked)) {
                    notice("Diagnostic indisponible ou compte modifié : fermer puis rouvrir.")
                    isEnabled = false
                }
            }
        })
        if (!state.readable) content.addView(TextView(activity).apply { text = "Journal illisible. Tu peux l'effacer puis réactiver l'enregistrement ; tes pointages seront conservés." })
        var date = LocalDate.now()
        content.addView(Button(activity).apply {
            text = "Journée : $date"
            setOnClickListener {
                DatePickerDialog(activity, { _, year, month, day ->
                    date = LocalDate.of(year, month + 1, day); text = "Journée : $date"
                }, date.year, date.monthValue - 1, date.dayOfMonth).show()
            }
        })
        content.addView(Button(activity).apply {
            text = "PRÉVISUALISER ET PARTAGER"
            setOnClickListener {
                val report = runCatching { DailyDiagnosticExportV2.render(activity, scope, date) }.getOrElse {
                    notice("Export indisponible : vérifier le compte et réessayer."); return@setOnClickListener
                }
                val preview = TextView(activity).apply { text = report; setTextIsSelectable(true); setPadding(24, 16, 24, 16) }
                val previewDialog = AlertDialog.Builder(activity).setTitle("Diagnostic du $date")
                    .setView(ScrollView(activity).apply { addView(preview) })
                    .setNegativeButton("Fermer", null)
                    .setPositiveButton("Partager le fichier") { _, _ ->
                        if (scope != V2RuntimeAccountScope.currentScope()) { notice("Compte modifié : export annulé."); return@setPositiveButton }
                        runCatching {
                            val dir = File(activity.cacheDir, "daily_diagnostic_exports").apply { mkdirs() }
                            dir.listFiles()?.forEach { it.delete() }
                            val file = File(dir, "HoraTrack-diagnostic-$date-${UUID.randomUUID()}.txt").apply { writeText(report, Charsets.UTF_8) }
                            val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.daily-diagnostic-files", file)
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"; putExtra(Intent.EXTRA_STREAM, uri)
                                clipData = ClipData.newRawUri("Diagnostic HoraTrack", uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            activity.startActivity(Intent.createChooser(intent, "Partager le diagnostic"))
                        }.onFailure { notice("Impossible de partager le diagnostic.") }
                    }.show()
                protect(previewDialog, scope) { preview.text = "" }
            }
        })
        content.addView(Button(activity).apply {
            text = "EFFACER LE DIAGNOSTIC ET ARRÊTER"
            setOnClickListener {
                AlertDialog.Builder(activity).setMessage("Effacer uniquement ce diagnostic local ? Les pointages sont conservés.")
                    .setNegativeButton("Annuler", null).setPositiveButton("Effacer") { _, _ ->
                        val cleared = DailyDiagnosticStoreV2.clear(activity, scope)
                        notice(if (cleared) "Diagnostic effacé et enregistrement arrêté." else "Compte modifié : action annulée.")
                        if (cleared) mainDialog?.dismiss()
                    }.show()
            }
        })
        val dialog = AlertDialog.Builder(activity).setTitle("Diagnostic de la journée").setView(ScrollView(activity).apply { addView(content) })
            .setPositiveButton("Fermer", null).show()
        mainDialog = dialog
        protect(dialog, scope)
    }
}
