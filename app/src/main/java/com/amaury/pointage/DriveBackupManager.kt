package com.amaury.pointage

import com.amaury.pointage.billing.BillingPdfGate
import com.amaury.pointage.billing.BillingContract
import com.amaury.pointage.billing.BillingBackend
import java.io.File

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.widget.Toast
import com.amaury.pointage.v2.HoraTrackV2
import com.amaury.pointage.v2.V2BackupManager
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.Executors

object DriveBackupManager {
    private const val PREFS = "drive_backup"
    private const val KEY_TREE_URI = "tree_uri"
    private const val ROOT_FOLDER = "Pointage Travail"
    private val executor = Executors.newSingleThreadExecutor()
    private val storageLock = Any()

    internal enum class SyncOwner { V2_SNAPSHOT, LEGACY_REPORTS }

    internal fun syncOwner(v2Enabled: Boolean): SyncOwner =
        if (v2Enabled) SyncOwner.V2_SNAPSHOT else SyncOwner.LEGACY_REPORTS

    /** Tous les accès au dossier Drive partagé passent par ce verrou unique. */
    internal fun <T> withStorageAccess(block: () -> T): T =
        synchronized(storageLock) { block() }

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
        DriveBackupWorker.cancel(context)
    }

    fun syncCurrentMonthAsync(context: Context) {
        if (!isConfigured(context)) return
        if (syncOwner(HoraTrackV2.ENABLED) == SyncOwner.V2_SNAPSHOT) {
            V2BackupManager.backupIfConfiguredAsync(context)
            return
        }
        val app = context.applicationContext
        executor.execute {
            runCatching {
                withStorageAccess {
                    syncCompletedDays(app)
                    syncClosedMonths(app)
                }
            }.onFailure { android.util.Log.w("DriveBackup", "Sauvegarde des rapports non effectuée", it) }
        }
    }

    fun syncAllAsync(context: Context, onDone: ((Boolean, String) -> Unit)? = null) {
        val app = context.applicationContext
        executor.execute {
            val result = syncAllBlocking(app)
            onDone?.invoke(result.isSuccess, result.getOrElse { it.message ?: "Erreur Drive" })
        }
    }

    /** Le Worker possède son exécution : aucun executor détaché ni attente de callback. */
    internal fun syncAllBlocking(
        context: Context,
        stopped: () -> Boolean = { false }
    ): Result<String> = runCatching {
        withStorageAccess {
            // Recontrôler après l'attente du verrou : une annulation ou déconnexion
            // peut arriver pendant qu'une autre sauvegarde utilise le dossier.
            if (stopped()) throw java.util.concurrent.CancellationException("Sauvegarde arrêtée")
            if (!isConfigured(context)) return@withStorageAccess "Sauvegarde Drive désactivée"
            if (syncOwner(HoraTrackV2.ENABLED) == SyncOwner.V2_SNAPSHOT) {
                V2BackupManager.backupToConfiguredDrive(context).getOrThrow()
                "sauvegarde AGKGMG V2 à jour"
            } else {
                syncCompletedDays(context)
                if (stopped()) throw java.util.concurrent.CancellationException("Sauvegarde arrêtée")
                syncClosedMonths(context)
                "sauvegarde quotidienne et mensuelle à jour"
            }
        }
    }

    private fun loadReliablePointage(context: Context): JSONArray {
        check(syncOwner(HoraTrackV2.ENABLED) == SyncOwner.LEGACY_REPORTS) {
            "Lecture PointageStore interdite : la sauvegarde AGKGMG V2 est propriétaire"
        }
        return PointageStore.load(context)
    }

    private fun syncCompletedDays(context: Context) {
        val all = loadReliablePointage(context)
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
            publishAuthorizedPdf(context, dailyFolder, "Pointage_$dateName.pdf") { output ->
                DailyPdfReport.write(context, data, dayStart, dayEnd, output)
            }
        }
    }

    private fun syncClosedMonths(context: Context) {
        val all = loadReliablePointage(context)
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
        months.forEach { (y, m) -> syncMonthLocked(context, y, m) }
    }

    fun syncMonth(context: Context, year: Int, month: Int) {
        if (syncOwner(HoraTrackV2.ENABLED) == SyncOwner.V2_SNAPSHOT) {
            V2BackupManager.backupToConfiguredDrive(context).getOrThrow()
            return
        }
        withStorageAccess { syncMonthLocked(context, year, month) }
    }

    private fun syncMonthLocked(context: Context, year: Int, month: Int) {
        val treeUri = savedTreeUri(context) ?: return
        val all = loadReliablePointage(context)
        if (all.length() == 0) return
        val root = ensureDirectory(context, treeRootDocumentUri(treeUri), ROOT_FOLDER)
        val monthLabel = SimpleDateFormat("MM - MMMM", Locale.FRANCE).format(
            Calendar.getInstance(Locale.FRANCE).apply { set(year, month, 1) }.time
        ).replaceFirstChar { it.uppercase() }
        val placeFolder = ensureDirectory(context, root, "AGKGMG")
        val yearFolder = ensureDirectory(context, placeFolder, year.toString())
        val monthFolder = ensureDirectory(context, yearFolder, safeName(monthLabel))
        val fileName = "Récapitulatif_${year}_${String.format(Locale.FRANCE, "%02d", month + 1)}.pdf"
        publishAuthorizedPdf(context, monthFolder, fileName) { output ->
            MonthlyPdfReport.write(context, all, year, month, output)
        }
    }

    /** A background backup never starts a purchase or exports an unpaid PDF. */
    private fun publishAuthorizedPdf(context: Context, folder: Uri, name: String, write: (java.io.OutputStream) -> Unit) {
        val uid = BillingBackend.uid() ?: error("Compte requis pour la sauvegarde PDF")
        val prepared = File.createTempFile("drive_report_", ".pdf", context.cacheDir)
        try {
            prepared.outputStream().use(write)
            check(BillingPdfGate.authorizeBackground(context, prepared)) {
                "PDF Drive non exporté : achat ou abonnement à vérifier dans l'application. Les données de pointage restent conservées."
            }
            check(BillingBackend.uid() == uid) { "Compte modifié pendant la sauvegarde PDF" }
            publishPreparedPdf(context, folder, name, prepared) { BillingBackend.uid() == uid }
        } finally {
            prepared.delete()
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

    /** Publish only to a fresh document: a transport failure must never corrupt an older report. */
    internal fun publishPreparedPdf(context: Context, parent: Uri, name: String, prepared: File, accountUnchanged: () -> Boolean) {
        check(accountUnchanged()) { "Compte modifié pendant la sauvegarde PDF" }
        val hash = BillingContract.documentId(prepared)
        fun matches(uri: Uri): Boolean = context.contentResolver.openInputStream(uri)?.use { input ->
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(8192)
            while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
            digest.digest().joinToString("") { "%02x".format(it) } == hash
        } ?: error("Impossible de vérifier le PDF existant")
        val existing = findChild(context, parent, name, "application/pdf")
        if (existing != null && matches(existing)) return
        val targetName = if (existing == null) name else name.removeSuffix(".pdf") + "_$hash.pdf"
        val previous = if (existing == null) null else findChild(context, parent, targetName, "application/pdf")
        if (previous != null) {
            check(matches(previous)) { "Un rapport existant doit être vérifié avant publication" }
            return
        }
        check(accountUnchanged()) { "Compte modifié pendant la sauvegarde PDF" }
        val destination = DocumentsContract.createDocument(context.contentResolver, parent, "application/pdf", targetName)
            ?: error("Impossible de créer $targetName")
        try {
            context.contentResolver.openOutputStream(destination, "w")?.use { output ->
                prepared.inputStream().use { input ->
                    val buffer = ByteArray(8192)
                    while (true) { val count = input.read(buffer); if (count < 0) break; check(accountUnchanged()); output.write(buffer, 0, count) }
                }
            } ?: error("Impossible d'écrire $targetName")
            check(accountUnchanged()) { "Compte modifié pendant la sauvegarde PDF" }
            check(BillingContract.documentId(prepared) == hash && matches(destination)) { "PDF sauvegardé incomplet" }
            check(accountUnchanged()) { "Compte modifié pendant la sauvegarde PDF" }
        } catch (error: Exception) {
            runCatching { DocumentsContract.deleteDocument(context.contentResolver, destination) }
            throw error
        }
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
                        Toast.makeText(this, "Dossier Drive mémorisé. Sauvegarde automatique activée.", Toast.LENGTH_LONG).show()
                        DriveBackupManager.syncAllAsync(this) { _, message -> runOnUiThread { Toast.makeText(this, "Drive : $message", Toast.LENGTH_LONG).show() } }
                    }
                    .onFailure { Toast.makeText(this, "Impossible de mémoriser ce dossier", Toast.LENGTH_LONG).show() }
            }
        }
        finish()
    }
}
