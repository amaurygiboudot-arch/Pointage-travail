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
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_TREE_URI, null)?.let(Uri::parse)

    fun saveTreeUri(context: Context, uri: Uri) {
        context.contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_TREE_URI, uri.toString()).apply()
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    fun syncCurrentMonthAsync(context: Context) {
        if (!isConfigured(context)) return
        val app = context.applicationContext
        Thread {
            runCatching {
                val now = Calendar.getInstance(Locale.FRANCE)
                syncMonth(app, now.get(Calendar.YEAR), now.get(Calendar.MONTH))
            }
        }.start()
    }

    fun syncAllAsync(context: Context, onDone: ((Boolean, String) -> Unit)? = null) {
        val app = context.applicationContext
        Thread {
            val result = runCatching {
                val data = PointageStore.load(app)
                val months = linkedSetOf<Pair<Int, Int>>()
                val cal = Calendar.getInstance(Locale.FRANCE)
                for (i in 0 until data.length()) {
                    val item = data.optJSONObject(i) ?: continue
                    val entry = item.optLong("entry", -1L)
                    if (entry <= 0L) continue
                    cal.timeInMillis = entry
                    months += cal.get(Calendar.YEAR) to cal.get(Calendar.MONTH)
                }
                months.forEach { (year, month) -> syncMonth(app, year, month) }
                "${months.size} mois sauvegardé(s)"
            }
            onDone?.invoke(result.isSuccess, result.getOrElse { it.message ?: "Erreur Drive" })
        }.start()
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
            val place = item.optString("zoneAddress").trim().takeIf { it.isNotBlank() }
                ?: "Pointage manuel"
            groups.getOrPut(place) { JSONArray() }.put(item)
        }

        if (groups.isEmpty()) return

        val root = ensureDirectory(context, treeRootDocumentUri(treeUri), ROOT_FOLDER)
        val monthLabel = SimpleDateFormat("MM - MMMM", Locale.FRANCE).format(
            Calendar.getInstance(Locale.FRANCE).apply {
                set(Calendar.YEAR, year); set(Calendar.MONTH, month); set(Calendar.DAY_OF_MONTH, 1)
            }.time
        ).replaceFirstChar { it.uppercase() }

        groups.forEach { (place, data) ->
            val placeFolder = ensureDirectory(context, root, safeName(folderNameForPlace(place)))
            val yearFolder = ensureDirectory(context, placeFolder, year.toString())
            val monthFolder = ensureDirectory(context, yearFolder, safeName(monthLabel))
            val fileName = "Pointage_${year}_${String.format(Locale.FRANCE, "%02d", month + 1)}.pdf"
            val pdfUri = ensureFile(context, monthFolder, fileName, "application/pdf")
            context.contentResolver.openOutputStream(pdfUri, "w")?.use { output ->
                MonthlyPdfReport.write(data, year, month, output)
            } ?: error("Impossible d'écrire $fileName")
        }
    }

    private fun folderNameForPlace(place: String): String {
        val marker = " — "
        return if (place.contains(marker)) place.substringBefore(marker).trim() else place.trim()
    }

    private fun safeName(value: String): String = value
        .replace(Regex("[\\/:*?\"<>|]"), "-")
        .trim()
        .take(80)
        .ifBlank { "Lieu sans nom" }

    private fun treeRootDocumentUri(treeUri: Uri): Uri =
        DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))

    private fun ensureDirectory(context: Context, parent: Uri, name: String): Uri {
        findChild(context, parent, name, DocumentsContract.Document.MIME_TYPE_DIR)?.let { return it }
        return DocumentsContract.createDocument(
            context.contentResolver,
            parent,
            DocumentsContract.Document.MIME_TYPE_DIR,
            name
        ) ?: error("Impossible de créer le dossier $name")
    }

    private fun ensureFile(context: Context, parent: Uri, name: String, mime: String): Uri {
        findChild(context, parent, name, mime)?.let { return it }
        return DocumentsContract.createDocument(context.contentResolver, parent, mime, name)
            ?: error("Impossible de créer le fichier $name")
    }

    private fun findChild(context: Context, parent: Uri, name: String, mime: String): Uri? {
        val parentId = DocumentsContract.getDocumentId(parent)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(parent, parentId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE
        )
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
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
            )
        }
        startActivityForResult(intent, REQUEST_FOLDER)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_FOLDER && resultCode == RESULT_OK) {
            val uri = data?.data
            if (uri != null) {
                runCatching { DriveBackupManager.saveTreeUri(this, uri) }
                    .onSuccess {
                        Toast.makeText(this, "Dossier Drive mémorisé. Synchronisation de l'historique…", Toast.LENGTH_LONG).show()
                        DriveBackupManager.syncAllAsync(this) { ok, message ->
                            runOnUiThread {
                                Toast.makeText(this, if (ok) "Drive : $message" else "Drive : $message", Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                    .onFailure {
                        Toast.makeText(this, "Impossible de mémoriser ce dossier", Toast.LENGTH_LONG).show()
                    }
            }
        }
        finish()
    }
}
