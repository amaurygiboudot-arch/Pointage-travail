package com.amaury.pointage.billing

import java.io.File
import java.security.MessageDigest

/** Identifiants à créer dans Play Console ; aucune valeur locale ne débloque un droit. */
object BillingContract {
    const val PREMIUM = "horatrack_premium"
    const val PLUS = "horatrack_plus"
    const val ANALYSIS = "horatrack_analysis"
    const val PDF = "horatrack_pdf"
    val products = setOf(PREMIUM, PLUS, ANALYSIS, PDF)
    fun accountId(uid: String): String = hex(MessageDigest.getInstance("SHA-256").digest(uid.toByteArray(Charsets.UTF_8)))
    fun documentId(file: File): String {
        check(file.isFile && file.length() > 0) { "PDF introuvable" }
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        return hex(digest.digest())
    }
    fun authorizedPdf(data: Any?, documentId: String): Boolean {
        val map = data as? Map<*, *> ?: return false
        return map["authorized"] == true && map["documentSha256"] == documentId &&
            Regex("[a-f0-9]{64}").matches(documentId)
    }
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
}
