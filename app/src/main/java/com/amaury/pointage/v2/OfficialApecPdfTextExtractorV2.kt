package com.amaury.pointage.v2

import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper

/**
 * Extraction déterministe du texte natif d'un agrément PDF APEC.
 *
 * Aucun OCR n'est utilisé ici : si le PDF ne contient pas de couche texte exploitable,
 * la preuve APEC reste incomplète et aucune catégorie n'est déduite.
 */
object OfficialApecPdfTextExtractorV2 {
    private const val MAX_PDF_BYTES = 12 * 1024 * 1024
    private const val MAX_PAGES = 80
    private const val MAX_TEXT_CHARS = 2_000_000
    private const val MIN_TEXT_CHARS = 80

    data class Diagnostic(
        val content: String?,
        val pages: Int,
        val reasons: List<String>
    )

    fun extract(context: Context, pdfBytes: ByteArray): Diagnostic {
        preflight(pdfBytes)?.let { reason ->
            return Diagnostic(null, 0, listOf(reason))
        }

        return runCatching {
            PDFBoxResourceLoader.init(context.applicationContext)
            PDDocument.load(pdfBytes).use { document ->
                val pages = document.numberOfPages
                documentReason(document.isEncrypted, pages)?.let { reason ->
                    return@use Diagnostic(null, pages, listOf(reason))
                }

                val text = PDFTextStripper().apply {
                    sortByPosition = true
                }.getText(document)
                    .replace("\r\n", "\n")
                    .replace('\r', '\n')
                    .trim()

                extractedTextReason(text)?.let { reason ->
                    return@use Diagnostic(null, pages, listOf(reason))
                }

                Diagnostic(
                    content = text,
                    pages = pages,
                    reasons = emptyList()
                )
            }
        }.getOrElse { error ->
            Diagnostic(
                content = null,
                pages = 0,
                reasons = listOf(
                    "APEC PDF : extraction du texte natif impossible (${error.javaClass.simpleName}) ; aucun OCR ni contenu supposé n'est utilisé."
                )
            )
        }
    }

    internal fun preflight(pdfBytes: ByteArray): String? {
        if (pdfBytes.isEmpty()) return "APEC PDF : document vide."
        if (pdfBytes.size > MAX_PDF_BYTES) {
            return "APEC PDF : document trop volumineux pour une validation locale sûre."
        }
        if (pdfBytes.size < PDF_HEADER.size || !pdfBytes.copyOfRange(0, PDF_HEADER.size).contentEquals(PDF_HEADER)) {
            return "APEC PDF : signature PDF officielle inexploitable ou absente."
        }
        return null
    }

    internal fun documentReason(encrypted: Boolean, pages: Int): String? {
        if (encrypted) return "APEC PDF : document chiffré ; extraction automatique bloquée."
        if (pages <= 0) return "APEC PDF : aucune page exploitable."
        if (pages > MAX_PAGES) return "APEC PDF : nombre de pages anormalement élevé ; extraction automatique bloquée."
        return null
    }

    internal fun extractedTextReason(text: String): String? {
        val trimmed = text.trim()
        if (trimmed.length < MIN_TEXT_CHARS) {
            return "APEC PDF : couche texte native absente ou insuffisante ; aucun OCR n'est utilisé."
        }
        if (trimmed.length > MAX_TEXT_CHARS) {
            return "APEC PDF : couche texte anormalement volumineuse ; validation automatique bloquée."
        }
        return null
    }

    private val PDF_HEADER = "%PDF-".toByteArray(Charsets.US_ASCII)
}
