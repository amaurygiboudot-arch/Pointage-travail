package com.amaury.pointage

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import com.amaury.pointage.v2.V2RuntimeStore
import com.amaury.pointage.v2.engine.MonthlyPdfReportV2
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** Flux d'export mensuel basé sur HoraTrackMotor, indépendant de l'ancien rapport. */
class V2MonthlyPdfActivity : Activity() {
    companion object { private const val REQUEST_CREATE = 9401 }
    private var year = 0
    private var month = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        chooseMonth()
    }

    private fun chooseMonth() {
        val labels = ArrayList<String>()
        val months = ArrayList<Calendar>()
        val format = SimpleDateFormat("MMMM yyyy", Locale.FRANCE)
        val cursor = Calendar.getInstance(Locale.FRANCE).apply {
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        repeat(36) {
            months += cursor.clone() as Calendar
            labels += format.format(cursor.time).replaceFirstChar { it.uppercase() }
            cursor.add(Calendar.MONTH, -1)
        }
        AlertDialog.Builder(this)
            .setTitle("Mois du rapport")
            .setItems(labels.toTypedArray()) { _, which ->
                val selected = months[which]
                year = selected.get(Calendar.YEAR)
                month = selected.get(Calendar.MONTH)
                createDocument(selected)
            }
            .setNegativeButton("Annuler") { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }

    private fun createDocument(selected: Calendar) {
        val fileLabel = SimpleDateFormat("yyyy_MM", Locale.FRANCE).format(selected.time)
        startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/pdf"
            putExtra(Intent.EXTRA_TITLE, "HoraTrack_$fileLabel.pdf")
        }, REQUEST_CREATE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_CREATE) return
        if (resultCode != RESULT_OK) { finish(); return }
        val uri = data?.data ?: run { finish(); return }
        val result = runCatching {
            contentResolver.openOutputStream(uri)?.use { output ->
                MonthlyPdfReportV2.write(V2RuntimeStore.allSessions(this), year, month, output)
            } ?: error("Impossible d'ouvrir le fichier")
        }
        Toast.makeText(
            this,
            if (result.isSuccess) "PDF HoraTrack enregistré" else "Impossible de générer le PDF : ${result.exceptionOrNull()?.message ?: "erreur inconnue"}",
            Toast.LENGTH_LONG
        ).show()
        finish()
    }
}
