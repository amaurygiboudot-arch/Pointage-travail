package com.amaury.pointage.v2

import java.util.Locale

/** Pure validation rules shared by the local company-agreement importer and its tests. */
object CompanyAgreementImportPolicyV2 {
    const val MAX_FILE_BYTES = 32L * 1024L * 1024L
    const val MAX_TEXT_BYTES = 8 * 1024 * 1024
    const val MAX_EXTRACTED_CHARS = 1_200_000
    const val MAX_PDF_PAGES = 120

    enum class Kind { TEXT, HTML, XML, DOCX, PDF, IMAGE, UNSUPPORTED }

    fun detectKind(mimeType: String?, displayName: String?, header: ByteArray): Kind {
        val mime = mimeType.orEmpty().substringBefore(';').trim().lowercase(Locale.ROOT)
        val name = displayName.orEmpty().trim().lowercase(Locale.ROOT)
        val pdfMagic = header.startsWithAscii("%PDF-")
        val zipMagic = header.size >= 4 && header[0] == 0x50.toByte() && header[1] == 0x4b.toByte() &&
            header[2] in setOf(0x03.toByte(), 0x05.toByte(), 0x07.toByte()) &&
            header[3] in setOf(0x04.toByte(), 0x06.toByte(), 0x08.toByte())
        val imageMagic = isKnownImageHeader(header)

        val expectsPdf = mime == "application/pdf" || name.endsWith(".pdf")
        if (expectsPdf) return if (pdfMagic) Kind.PDF else Kind.UNSUPPORTED
        if (pdfMagic) return Kind.PDF

        val expectsDocx = mime == "application/vnd.openxmlformats-officedocument.wordprocessingml.document" ||
            name.endsWith(".docx")
        if (expectsDocx) return if (zipMagic) Kind.DOCX else Kind.UNSUPPORTED

        val expectsImage = mime.startsWith("image/") || listOf(".jpg", ".jpeg", ".png", ".webp").any(name::endsWith)
        if (expectsImage) return if (imageMagic) Kind.IMAGE else Kind.UNSUPPORTED
        if (imageMagic) return Kind.IMAGE

        return when {
            mime == "text/html" || mime == "application/xhtml+xml" || name.endsWith(".html") || name.endsWith(".htm") -> Kind.HTML
            mime == "application/xml" || mime == "text/xml" || name.endsWith(".xml") -> Kind.XML
            mime.startsWith("text/") || mime == "application/json" || name.endsWith(".txt") || name.endsWith(".json") -> Kind.TEXT
            else -> Kind.UNSUPPORTED
        }
    }

    fun isSizeAllowed(sizeBytes: Long): Boolean = sizeBytes in 1..MAX_FILE_BYTES

    fun stableAgreementId(sha256: String): String {
        val normalized = sha256.trim().lowercase(Locale.ROOT)
        require(normalized.length == 64 && normalized.all { it in '0'..'9' || it in 'a'..'f' })
        return "LOCAL-ACCO-${normalized.take(24).uppercase(Locale.ROOT)}"
    }

    fun titleFrom(displayName: String?): String {
        val leaf = displayName.orEmpty().trim().substringAfterLast('/')
        val raw = leaf.substringBeforeLast('.', leaf)
        return raw.replace(Regex("\\s+"), " ").trim().take(120).ifBlank { "Accord d’entreprise" }
    }

    fun extensionFor(kind: Kind): String = when (kind) {
        Kind.TEXT -> "txt"
        Kind.HTML -> "html"
        Kind.XML -> "xml"
        Kind.DOCX -> "docx"
        Kind.PDF -> "pdf"
        Kind.IMAGE -> "jpg"
        Kind.UNSUPPORTED -> "bin"
    }

    fun normalizeExtractedText(value: String): String {
        val cleaned = value
            .replace('\u0000', ' ')
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .replace('\u000c', '\n')
            .lineSequence()
            .map { it.replace(Regex("[\\t\\u000B ]+"), " ").trim() }
            .joinToString("\n")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
        return cleaned.take(MAX_EXTRACTED_CHARS)
    }

    fun isMeaningfulText(value: String): Boolean {
        val text = value.trim()
        return text.length >= 80 && text.count(Char::isLetter) >= 40
    }

    private fun ByteArray.startsWithAscii(value: String): Boolean {
        if (size < value.length) return false
        return value.indices.all { this[it].toInt() and 0xff == value[it].code }
    }

    private fun isKnownImageHeader(header: ByteArray): Boolean {
        val jpeg = header.size >= 3 && header[0] == 0xff.toByte() && header[1] == 0xd8.toByte() && header[2] == 0xff.toByte()
        val png = header.size >= 8 && header[0] == 0x89.toByte() && header[1] == 0x50.toByte() &&
            header[2] == 0x4e.toByte() && header[3] == 0x47.toByte() && header[4] == 0x0d.toByte() &&
            header[5] == 0x0a.toByte() && header[6] == 0x1a.toByte() && header[7] == 0x0a.toByte()
        val webp = header.size >= 12 && header.copyOfRange(0, 4).decodeToString() == "RIFF" &&
            header.copyOfRange(8, 12).decodeToString() == "WEBP"
        return jpeg || png || webp
    }
}
