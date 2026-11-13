package com.amaury.pointage.billing

import java.io.File
import java.io.FileOutputStream

/** Durable bytes are recovery data, never proof of payment or a permission to preview. */
object PdfPendingVault {
    private val hashPattern = Regex("[a-f0-9]{64}")
    private val lock = Any()
    private val protectedRoots = listOf("billing_pdf_pending", "billing_pdf_archive", "billing_service_pending",
        "billing_service_drafts", "paid_service_prepared", "monthly_pdf_pending")

    fun capture(filesDir: File, accountId: String, source: File, displayName: String): File = synchronized(lock) {
        check(hashPattern.matches(accountId)) { "Compte PDF invalide" }
        val hash = validate(filesDir, accountId, source)
        val snapshot = publish(filesDir, accountId, source, hash, "billing_pdf_pending")
        val folder = snapshot.parentFile
        val title = displayName.substringAfterLast('/').substringAfterLast('\\').replace('\n', ' ').replace('\r', ' ').take(160).ifBlank { "AGKGMG.pdf" }
        val manifest = File(folder, "$hash.title")
        if (!manifest.exists()) {
            val temporary = File.createTempFile("title_", ".tmp", folder)
            try {
                FileOutputStream(temporary).use { output -> output.write(title.toByteArray(Charsets.UTF_8)); output.fd.sync() }
                check(temporary.renameTo(manifest)) { "Impossible de conserver le nom du PDF" }
            } finally { temporary.delete() }
        }
        snapshot
    }

    /** Publish only complete, synced, verified bytes; a failed copy never exposes a partial final PDF. */
    internal fun publish(filesDir: File, accountId: String, source: File, hash: String, root: String,
        copy: (File, FileOutputStream) -> Unit = { input, output -> input.inputStream().use { it.copyTo(output) } }
    ): File = synchronized(lock) {
        check(root in listOf("billing_pdf_pending", "billing_pdf_archive", "billing_service_pending"))
        check(hashPattern.matches(hash) && validate(filesDir, accountId, source) == hash)
        val folder = File(filesDir, "$root/$accountId").apply { check(mkdirs() || isDirectory) }
        val snapshot = File(folder, "$hash.pdf")
        checkOwnedPath(filesDir, accountId, snapshot)
        if (runCatching { validate(filesDir, accountId, snapshot) == hash }.getOrDefault(false)) return snapshot
        // Also replaces partial files left by older versions, but only from an intact source.
        val temporary = File.createTempFile("capture_", ".tmp", folder)
        try {
            FileOutputStream(temporary).use { output -> copy(source, output); output.fd.sync() }
            check(BillingContract.documentId(temporary) == hash) { "Le PDF a changé pendant sa conservation" }
            check(temporary.renameTo(snapshot)) { "Impossible de conserver ce PDF" }
        } finally { temporary.delete() }
        snapshot
    }

    fun documents(filesDir: File, accountId: String): List<File> = validDocuments(filesDir, accountId, "billing_pdf_pending")

    private fun validDocuments(filesDir: File, accountId: String, root: String): List<File> {
        if (!hashPattern.matches(accountId)) return emptyList()
        return File(filesDir, "$root/$accountId").listFiles().orEmpty()
            .filter { it.isFile && Regex("[a-f0-9]{64}\\.pdf").matches(it.name) }
            .filter { file -> runCatching { validate(filesDir, accountId, file) == file.nameWithoutExtension }.getOrDefault(false) }
    }

    /** Validate before deduplication so an interrupted old archive cannot hide intact pending bytes. */
    fun availableDocuments(filesDir: File, accountId: String): List<File> =
        (validDocuments(filesDir, accountId, "billing_pdf_archive") + documents(filesDir, accountId))
            .distinctBy { it.nameWithoutExtension }.sortedByDescending { it.lastModified() }

    fun name(file: File): String? = runCatching {
        val title = File(file.parentFile, "${file.nameWithoutExtension}.title")
        title.takeIf { it.isFile && it.length() <= 640 }?.readText(Charsets.UTF_8)?.take(160)
    }.getOrNull()

    fun validate(filesDir: File, accountId: String, source: File): String {
        checkOwnedPath(filesDir, accountId, source)
        val hash = BillingContract.documentId(source)
        checkContentAddress(filesDir, source, hash)
        return hash
    }

    fun checkOwnedPath(filesDir: File, accountId: String, source: File) {
        check(hashPattern.matches(accountId))
        val requested = source.canonicalFile
        check(protectedRoots.none { root ->
            val shared = File(filesDir, root).canonicalFile
            val own = File(shared, accountId).canonicalFile
            requested.path.startsWith(shared.path + File.separator) && !requested.path.startsWith(own.path + File.separator)
        }) { "Ce PDF appartient à un autre compte" }
    }

    private fun checkContentAddress(filesDir: File, source: File, hash: String) {
        val path = source.canonicalPath
        if (listOf("billing_pdf_pending", "billing_pdf_archive", "billing_service_pending").any { path.startsWith(File(filesDir, it).canonicalPath + File.separator) }) {
            check(source.name == "$hash.pdf") { "PDF conservé corrompu" }
        }
    }
}
