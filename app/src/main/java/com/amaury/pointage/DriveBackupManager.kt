package com.amaury.pointage

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.widget.Toast
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

object DriveBackupManager {
    private const val PREFS = "drive_backup"
    private const val KEY_TREE_URI = "tree_uri"
    private const val ROOT_FOLDER = "Pointage Travail"

    fun isConfigured(context: Context): Boolean =
        !context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_TREE_URI, null).isNullOrBlank()

    fun savedTreeUri(context: Context): Uri? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_TREE_URI, null)?.let(Uri::parse)

    fun saveTreeUri(context: Context, uri: Uri) {
        context.contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_TREE_URI, uri.toString()).apply()
        DriveBackupScheduler.schedule(context)
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
        DriveBackupScheduler.cancel(context)
    }

    fun syncCurrentMonthAsync(context: Context) {
        if (!isConfigured(context)) return
        val app = context.applicationContext
        Thread { runCatching { syncCompletedDays(app); syncClosedMonths(app) } }.start()
    }

    fun syncAutomaticAsync(context: Context) {
        if (!isConfigured(context)) return
        val app = context.applicationContext
        Thread { runCatching { syncCompletedDays(app); syncClosedMonths(app) } }.start()
    }

    fun syncAllAsync(context: Context, onDone: ((Boolean, String) -> Unit)? = null) {
        val app = context.applicationContext
        Thread {
            val result = runCatching {
                syncCompletedDays(app)
                syncClosedMonths(app)
                "export PDF quotidien et mensuel à jour"
            }
            onDone?.invoke(result.isSuccess, result.getOrElse { it.message ?: "Erreur Drive" })
        }.start()
    }

    private fun syncCompletedDays(context: Context) {
        val all = PointageStore.load(context)
        if (all.length() == 0) return
        val today = startOfDay(System.currentTimeMillis())
        val days = linkedSetOf<Long>()
        for (i in 0 until all.length()) {
            val item = all.optJSONObject(i) ?: continue
            val entry = item.optLong("entry", -1L)
            if (entry <= 0L || item.isNull("exit")) continue
            val day = startOfDay(entry)
            if (day < today) days += day
        }
        days.forEach { writeDailyReports(context, all, it) }
    }

    private fun writeDailyReports(context: Context, all: JSONArray, dayStart: Long) {
        val dayEnd = Calendar.getInstance(Locale.FRANCE).apply { timeInMillis = dayStart; add(Calendar.DAY_OF_MONTH, 1) }.timeInMillis
        val groups = linkedMapOf<String, JSONArray>()
        for (i in 0 until all.length()) {
            val item = all.optJSONObject(i) ?: continue
            val entry = item.optLong("entry", -1L)
            if (entry !in dayStart until dayEnd || item.isNull("exit")) continue
            val place = item.optString("zoneAddress").trim().takeIf { it.isNotBlank() } ?: "Pointage manuel"
            groups.getOrPut(place) { JSONArray() }.put(item)
        }
        if (groups.isEmpty()) return

        val treeUri = savedTreeUri(context) ?: return
        val root = ensureDirectory(context, treeRootDocumentUri(treeUri), ROOT_FOLDER)
        val cal = Calendar.getInstance(Locale.FRANCE).apply { timeInMillis = dayStart }
        val year = cal.get(Calendar.YEAR)
        val monthLabel = SimpleDateFormat("MM - MMMM", Locale.FRANCE).format(cal.time).replaceFirstChar { it.uppercase() }
        val dateName = SimpleDateFormat("yyyy-MM-dd", Locale.FRANCE).format(cal.time)

        groups.forEach { (place, data) ->
            val placeFolder = ensureDirectory(context, root, safeName(folderNameForPlace(place)))
            val yearFolder = ensureDirectory(context, placeFolder, year.toString())
            val monthFolder = ensureDirectory(context, yearFolder, safeName(monthLabel))
            val dailyFolder = ensureDirectory(context, monthFolder, "Journées")
            val file = ensureFile(context, dailyFolder, "Pointage_$dateName.pdf", "application/pdf")
            context.contentResolver.openOutputStream(file, "w")?.use { DailyPdfReport.write(context, data, dayStart, dayEnd, it) }
                ?: error("Impossible d'écrire le PDF quotidien")
        }
    }

    private fun syncClosedMonths(context: Context) {
        val all = PointageStore.load(context)
        if (all.length() == 0) return
        val current = Calendar.getInstance(Locale.FRANCE)
        val currentKey = current.get(Calendar.YEAR) * 12 + current.get(Calendar.MONTH)
        val months = linkedSetOf<Pair<Int, Int>>()
        val cal = Calendar.getInstance(Locale.FRANCE)
        for (i in 0 until all.length()) {
            val item = all.optJSONObject(i) ?: continue
            val entry = item.optLong("entry", -1L)
            if (entry <= 0L) continue
            cal.timeInMillis = entry
            val y = cal.get(Calendar.YEAR); val m = cal.get(Calendar.MONTH)
            if (y * 12 + m < currentKey) months += y to m
        }
        months.forEach { (y, m) -> syncMonth(context, y, m) }
    }

    fun syncMonth(context: Context, year: Int, month: Int) {
        val treeUri = savedTreeUri(context) ?: return
        val all = PointageStore.load(context)
        val groups = linkedMapOf<String, JSONArray>()
        val cal = Calendar.getInstance(Locale.FRANCE)
        for (i in 0 until all.length()) {
            val item = all.optJSONObject(i) ?: continue
            val entry = item.optLong("entry", -1L)
            if (entry <= 0L) continue
            cal.timeInMillis = entry
            if (cal.get(Calendar.YEAR) != year || cal.get(Calendar.MONTH) != month) continue
            val place = item.optString("zoneAddress").trim().takeIf { it.isNotBlank() } ?: "Pointage manuel"
            groups.getOrPut(place) { JSONArray() }.put(item)
        }
        if (groups.isEmpty()) return

        val root = ensureDirectory(context, treeRootDocumentUri(treeUri), ROOT_FOLDER)
        val monthLabel = SimpleDateFormat("MM - MMMM", Locale.FRANCE).format(
            Calendar.getInstance(Locale.FRANCE).apply { set(year, month, 1) }.time
        ).replaceFirstChar { it.uppercase() }

        groups.forEach { (place, data) ->
            val placeFolder = ensureDirectory(context, root, safeName(folderNameForPlace(place)))
            val yearFolder = ensureDirectory(context, placeFolder, year.toString())
            val monthFolder = ensureDirectory(context, yearFolder, safeName(monthLabel))
            val fileName = "Récapitulatif_${year}_${String.format(Locale.FRANCE, "%02d", month + 1)}.pdf"
            val pdfUri = ensureFile(context, monthFolder, fileName, "application/pdf")
            context.contentResolver.openOutputStream(pdfUri, "w")?.use { MonthlyPdfReport.write(context, data, year, month, it) }
                ?: error("Impossible d'écrire $fileName")
        }
    }

    private fun startOfDay(time: Long): Long = Calendar.getInstance(Locale.FRANCE).apply {
        timeInMillis = time
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun folderNameForPlace(place: String): String = if (place.contains(" — ")) place.substringBefore(" — ").trim() else place.trim()
    private fun safeName(value: String): String = value.replace(Regex("[\\/:*?\"<>|]"), "-").trim().take(80).ifBlank { "Lieu sans nom" }
    private fun treeRootDocumentUri(treeUri: Uri): Uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))

    private fun ensureDirectory(context: Context, parent: Uri, name: String): Uri {
        findChild(context, parent, name, DocumentsContract.Document.MIME_TYPE_DIR)?.let { return it }
        return DocumentsContract.createDocument(context.contentResolver, parent, DocumentsContract.Document.MIME_TYPE_DIR, name)
            ?: error("Impossible de créer le dossier $name")
    }

    private fun ensureFile(context: Context, parent: Uri, name: String, mime: String): Uri {
        findChild(context, parent, name, mime)?.let { return it }
        return DocumentsContract.createDocument(context.contentResolver, parent, mime, name) ?: error("Impossible de créer $name")
    }

    private fun findChild(context: Context, parent: Uri, name: String, mime: String): Uri? {
        val parentId = DocumentsContract.getDocumentId(parent)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(parent, parentId)
        val projection = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE)
        context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            while (cursor.moveToNext()) {
                if (cursor.getString(nameIndex) == name && cursor.getString(mimeIndex) == mime) {
                    return DocumentsContract.buildDocumentUriUsingTree(parent, cursor.getString(idIndex))
                }
            }
        }
        return null
    }
}

class DriveFolderPickerActivity : Activity() {
    companion object { private const val REQUEST_FOLDER = 7301 }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
        }, REQUEST_FOLDER)
    }
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_FOLDER && resultCode == RESULT_OK) {
            data?.data?.let { uri ->
                runCatching { DriveBackupManager.saveTreeUri(this, uri) }
                    .onSuccess {
                        Toast.makeText(this, "Dossier Drive mémorisé. Export PDF automatique activé.", Toast.LENGTH_LONG).show()
                        DriveBackupManager.syncAllAsync(this) { ok, message -> runOnUiThread { Toast.makeText(this, "Drive : $message", Toast.LENGTH_LONG).show() } }
                    }
                    .onFailure { Toast.makeText(this, "Impossible de mémoriser ce dossier", Toast.LENGTH_LONG).show() }
            }
        }
        finish()
    }
}
