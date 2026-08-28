package com.amaury.pointage.v2

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.amaury.pointage.DriveBackupManager
import org.json.JSONArray
import org.json.JSONObject

/** Sauvegarde des données fonctionnelles HoraTrack, sans jetons d'authentification. */
object V2BackupManager {
    private const val FORMAT_VERSION = 2
    private const val ROOT_FOLDER = "Pointage Travail"
    private const val FILE_NAME = "HoraTrack_V2_backup.json"

    private val preferenceFiles = listOf(
        "horatrack_v2_test_runtime",
        "horatrack_v2_integration",
        "horatrack_v2_migration",
        "horatrack_v2_legal_sources",
        "salary_settings",
        "gps_settings",
        "shift_profiles",
        "appearance_settings",
        "widget_style",
        "place_names",
        "smart_setup",
        "welcome_preview"
    )

    data class RestoreResult(val restoredFiles:Int, val mergedSessions:Int)

    fun backupToConfiguredDrive(context: Context): Result<Uri> = runCatching {
        val tree = DriveBackupManager.savedTreeUri(context) ?: error("Choisis d'abord un dossier Google Drive")
        val root = treeRootDocumentUri(tree)
        val appFolder = ensureDirectory(context, root, ROOT_FOLDER)
        val file = ensureFile(context, appFolder, FILE_NAME, "application/json")
        val payload = snapshot(context).toString(2)
        context.contentResolver.openOutputStream(file, "w")?.bufferedWriter(Charsets.UTF_8)?.use { it.write(payload) }
            ?: error("Impossible d'écrire la sauvegarde")
        context.getSharedPreferences("horatrack_v2_backup", Context.MODE_PRIVATE).edit()
            .putLong("last_backup_ms", System.currentTimeMillis()).apply()
        file
    }

    fun restoreFromUri(context: Context, uri: Uri): Result<RestoreResult> = runCatching {
        val raw = context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
            ?: error("Impossible de lire la sauvegarde")
        val root = JSONObject(raw)
        require(root.optInt("formatVersion", 0) in 1..FORMAT_VERSION) { "Format de sauvegarde non reconnu" }
        val files = root.optJSONObject("preferences") ?: error("Sauvegarde incomplète")
        var restoredFiles = 0
        var mergedSessions = 0

        preferenceFiles.forEach { name ->
            val saved = files.optJSONObject(name) ?: return@forEach
            if (name == "horatrack_v2_test_runtime") {
                mergedSessions += restoreRuntime(context, saved)
            } else {
                mergePreferences(context, name, saved)
            }
            restoredFiles++
        }
        V2ProfileStore.bind(context)
        V2MigrationManager.ensureMigrated(context)
        RestoreResult(restoredFiles, mergedSessions)
    }

    fun snapshot(context: Context): JSONObject {
        val all = JSONObject()
        preferenceFiles.forEach { name ->
            all.put(name, encodePreferences(context, name))
        }
        return JSONObject()
            .put("formatVersion", FORMAT_VERSION)
            .put("schemaVersion", HoraTrackV2.SCHEMA_VERSION)
            .put("createdAtMs", System.currentTimeMillis())
            .put("preferences", all)
    }

    private fun encodePreferences(context: Context, name: String): JSONObject {
        val out = JSONObject()
        context.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE).all.forEach { (key, value) ->
            when (value) {
                is String -> out.put(key, JSONObject().put("t", "s").put("v", value))
                is Boolean -> out.put(key, JSONObject().put("t", "b").put("v", value))
                is Int -> out.put(key, JSONObject().put("t", "i").put("v", value))
                is Long -> out.put(key, JSONObject().put("t", "l").put("v", value))
                is Float -> out.put(key, JSONObject().put("t", "f").put("v", value.toDouble()))
                is Set<*> -> out.put(key, JSONObject().put("t", "set").put("v", JSONArray(value.filterIsInstance<String>())))
            }
        }
        return out
    }

    private fun mergePreferences(context: Context, name: String, saved: JSONObject) {
        val editor = context.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE).edit()
        val keys = saved.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val item = saved.optJSONObject(key) ?: continue
            when (item.optString("t")) {
                "s" -> editor.putString(key, item.optString("v"))
                "b" -> editor.putBoolean(key, item.optBoolean("v"))
                "i" -> editor.putInt(key, item.optInt("v"))
                "l" -> editor.putLong(key, item.optLong("v"))
                "f" -> editor.putFloat(key, item.optDouble("v").toFloat())
                "set" -> {
                    val a = item.optJSONArray("v") ?: JSONArray()
                    val set = buildSet { for (i in 0 until a.length()) a.optString(i).takeIf { it.isNotBlank() }?.let(::add) }
                    editor.putStringSet(key, set)
                }
            }
        }
        editor.apply()
    }

    private fun restoreRuntime(context: Context, saved: JSONObject): Int {
        val prefs = context.applicationContext.getSharedPreferences("horatrack_v2_test_runtime", Context.MODE_PRIVATE)
        val currentOpen = numeric(prefs.all["real_entry"]) > 0L && numeric(prefs.all["real_exit"]) == 0L
        val savedHistory = decodeTypedString(saved.optJSONObject("history"))?.let { runCatching { JSONArray(it) }.getOrNull() } ?: JSONArray()
        val currentHistory = runCatching { JSONArray(prefs.getString("history", "[]") ?: "[]") }.getOrElse { JSONArray() }
        val seen = mutableSetOf<String>()
        for (i in 0 until currentHistory.length()) currentHistory.optJSONObject(i)?.let { seen += historySignature(it) }
        var merged = 0
        for (i in 0 until savedHistory.length()) {
            val item = savedHistory.optJSONObject(i) ?: continue
            val sig = historySignature(item)
            if (sig !in seen) { currentHistory.put(item); seen += sig; merged++ }
        }
        prefs.edit().putString("history", currentHistory.toString()).apply()

        // Sur une installation vide, la session courante sauvegardée peut être restaurée.
        // Sur une installation déjà en cours de pointage, elle n'écrase jamais la session locale.
        if (!currentOpen && numeric(prefs.all["real_entry"]) == 0L) {
            val withoutHistory = JSONObject(saved.toString()).apply { remove("history") }
            mergePreferences(context, "horatrack_v2_test_runtime", withoutHistory)
            prefs.edit().putString("history", currentHistory.toString()).apply()
        }
        return merged
    }

    private fun decodeTypedString(item: JSONObject?): String? =
        item?.takeIf { it.optString("t") == "s" }?.optString("v")

    private fun historySignature(o: JSONObject): String = listOf(
        o.optString("id"),
        o.optLong("realEntry", 0L).toString(),
        o.optLong("realExit", 0L).toString(),
        o.optLong("countedEntry", 0L).toString(),
        o.optLong("countedExit", 0L).toString()
    ).joinToString(":")

    private fun numeric(value: Any?): Long = when (value) {
        is Number -> value.toLong()
        is String -> value.toLongOrNull() ?: 0L
        else -> 0L
    }

    private fun treeRootDocumentUri(treeUri: Uri): Uri =
        DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))

    private fun ensureDirectory(context: Context, parent: Uri, name: String): Uri {
        findChild(context, parent, name, DocumentsContract.Document.MIME_TYPE_DIR)?.let { return it }
        return DocumentsContract.createDocument(context.contentResolver, parent, DocumentsContract.Document.MIME_TYPE_DIR, name)
            ?: error("Impossible de créer $name")
    }

    private fun ensureFile(context: Context, parent: Uri, name: String, mime: String): Uri {
        findChild(context, parent, name, mime)?.let { return it }
        return DocumentsContract.createDocument(context.contentResolver, parent, mime, name)
            ?: error("Impossible de créer $name")
    }

    private fun findChild(context: Context, parent: Uri, name: String, mime: String): Uri? {
        val parentId = DocumentsContract.getDocumentId(parent)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(parent, parentId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE
        )
        context.contentResolver.query(children, projection, null, null, null)?.use { cursor ->
            val id = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val n = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val m = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            while (cursor.moveToNext()) {
                if (cursor.getString(n) == name && cursor.getString(m) == mime) {
                    return DocumentsContract.buildDocumentUriUsingTree(parent, cursor.getString(id))
                }
            }
        }
        return null
    }
}
