package com.amaury.pointage.v2

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.text.Html
import androidx.core.content.FileProvider
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

/** Reads an agreement entirely on-device. Extracted full text is never persisted or uploaded. */
object CompanyAgreementDocumentReaderV2 {
    data class Result(
        val temporaryFile: File,
        val displayName: String,
        val mimeType: String,
        val sha256: String,
        val kind: CompanyAgreementImportPolicyV2.Kind,
        val extractedText: String,
        val pageCount: Int?,
        val truncated: Boolean
    )

    class ImportException(message: String) : Exception(message)

    private data class Metadata(val name: String, val size: Long?)
    private data class CopiedDocument(val file: File, val size: Long, val sha256: String, val header: ByteArray)
    private data class Extraction(val text: String, val pageCount: Int? = null, val truncated: Boolean = false)

    fun read(context: Context, source: Uri, onProgress: (String) -> Unit = {}): Result {
        val metadata = metadata(context, source)
        if (metadata.size != null && !CompanyAgreementImportPolicyV2.isSizeAllowed(metadata.size)) {
            throw ImportException("Le document doit faire entre 1 octet et 32 Mo.")
        }

        onProgress("Copie sécurisée du document…")
        val copied = copyToTemporaryFile(context, source)
        try {
            val resolvedMime = context.contentResolver.getType(source).orEmpty().substringBefore(';').trim()
            val kind = CompanyAgreementImportPolicyV2.detectKind(resolvedMime, metadata.name, copied.header)
            if (kind == CompanyAgreementImportPolicyV2.Kind.UNSUPPORTED) {
                throw ImportException("Format non pris en charge. Utilise un PDF, une image, un fichier texte, HTML, XML ou DOCX.")
            }

            val extraction = when (kind) {
                CompanyAgreementImportPolicyV2.Kind.TEXT -> extractText(copied.file)
                CompanyAgreementImportPolicyV2.Kind.HTML -> extractHtml(copied.file)
                CompanyAgreementImportPolicyV2.Kind.XML -> extractXmlFile(copied.file)
                CompanyAgreementImportPolicyV2.Kind.DOCX -> extractDocx(copied.file)
                CompanyAgreementImportPolicyV2.Kind.PDF -> extractPdf(copied.file, onProgress)
                CompanyAgreementImportPolicyV2.Kind.IMAGE -> extractImage(copied.file, onProgress)
                CompanyAgreementImportPolicyV2.Kind.UNSUPPORTED -> error("unreachable")
            }
            val normalized = CompanyAgreementImportPolicyV2.normalizeExtractedText(extraction.text)
            val mime = resolvedMime.takeIf { isMimeCompatible(kind, it) } ?: defaultMime(kind)
            val safeName = metadata.name.replace(Regex("[\\r\\n\\u0000]+"), " ").trim().take(180)
            return Result(
                temporaryFile = copied.file,
                displayName = safeName.ifBlank { "accord.${CompanyAgreementImportPolicyV2.extensionFor(kind)}" },
                mimeType = mime,
                sha256 = copied.sha256,
                kind = kind,
                extractedText = normalized,
                pageCount = extraction.pageCount,
                truncated = extraction.truncated || extraction.text.length > CompanyAgreementImportPolicyV2.MAX_EXTRACTED_CHARS
            )
        } catch (error: Throwable) {
            copied.file.delete()
            throw error
        }
    }

    private fun metadata(context: Context, source: Uri): Metadata {
        var name = source.lastPathSegment.orEmpty().substringAfterLast('/')
        var size: Long? = null
        runCatching {
            context.contentResolver.query(source, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIndex >= 0) name = cursor.getString(nameIndex).orEmpty()
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex).takeIf { it >= 0 }
                }
            }
        }
        return Metadata(name = name, size = size)
    }

    private fun copyToTemporaryFile(context: Context, source: Uri): CopiedDocument {
        val directory = File(context.cacheDir, "company_agreement_import").apply {
            if (!exists() && !mkdirs()) throw ImportException("Impossible de préparer l’import local.")
        }
        val target = File.createTempFile("agreement-", ".tmp", directory)
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            val header = ByteArrayOutputStream(16)
            var total = 0L
            val stream = context.contentResolver.openInputStream(source)
                ?: throw ImportException("Impossible d’ouvrir le document sélectionné.")
            stream.use { input ->
                FileOutputStream(target).use { output ->
                    val buffer = ByteArray(32 * 1024)
                    while (true) {
                        ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue
                        total += read
                        if (total > CompanyAgreementImportPolicyV2.MAX_FILE_BYTES) {
                            throw ImportException("Le document dépasse la limite de 32 Mo.")
                        }
                        val headerRemaining = 16 - header.size()
                        if (headerRemaining > 0) header.write(buffer, 0, minOf(read, headerRemaining))
                        digest.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                    }
                    output.fd.sync()
                }
            }
            if (!CompanyAgreementImportPolicyV2.isSizeAllowed(total)) {
                throw ImportException("Le document sélectionné est vide.")
            }
            CopiedDocument(target, total, digest.digest().joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 0xff) }, header.toByteArray())
        } catch (error: Throwable) {
            target.delete()
            throw error
        }
    }

    private fun extractText(file: File): Extraction = Extraction(decodeText(readTextBytes(file)))

    private fun extractHtml(file: File): Extraction {
        val source = decodeText(readTextBytes(file))
        @Suppress("DEPRECATION")
        val text = Html.fromHtml(source).toString()
        return Extraction(text)
    }

    private fun extractXmlFile(file: File): Extraction = Extraction(extractXmlText(decodeText(readTextBytes(file)), docx = false))

    private fun readTextBytes(file: File): ByteArray {
        if (file.length() > CompanyAgreementImportPolicyV2.MAX_TEXT_BYTES) {
            throw ImportException("Le texte du document dépasse la limite de 8 Mo.")
        }
        return file.readBytes()
    }

    private fun decodeText(bytes: ByteArray): String {
        val withoutBom = if (bytes.size >= 3 && bytes[0] == 0xef.toByte() && bytes[1] == 0xbb.toByte() && bytes[2] == 0xbf.toByte()) {
            bytes.copyOfRange(3, bytes.size)
        } else bytes
        return runCatching {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(withoutBom))
                .toString()
        }.getOrElse { withoutBom.toString(Charsets.ISO_8859_1) }
    }

    private fun extractDocx(file: File): Extraction {
        var documentXml: ByteArray? = null
        ZipInputStream(FileInputStream(file)).use { zip ->
            var entries = 0
            while (true) {
                ensureActive()
                val entry = zip.nextEntry ?: break
                entries += 1
                if (entries > 2_000) throw ImportException("Document DOCX invalide ou trop complexe.")
                if (!entry.isDirectory && entry.name == "word/document.xml") {
                    documentXml = readLimited(zip, CompanyAgreementImportPolicyV2.MAX_TEXT_BYTES)
                    break
                }
                zip.closeEntry()
            }
        }
        val xml = documentXml ?: throw ImportException("Ce fichier ne contient pas de document DOCX lisible.")
        return Extraction(extractXmlText(decodeText(xml), docx = true))
    }

    private fun readLimited(input: InputStream, limit: Int): ByteArray {
        val output = ByteArrayOutputStream(minOf(limit, 64 * 1024))
        val buffer = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            ensureActive()
            val read = input.read(buffer)
            if (read < 0) break
            if (read == 0) continue
            total += read
            if (total > limit) throw ImportException("Le texte interne du document dépasse la limite de 8 Mo.")
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private fun extractXmlText(source: String, docx: Boolean): String {
        if (source.contains("<!DOCTYPE", ignoreCase = true) || source.contains("<!ENTITY", ignoreCase = true)) {
            throw ImportException("Le document XML contient une structure non autorisée.")
        }
        val parser = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }.newPullParser()
        parser.setInput(StringReader(source))
        val output = StringBuilder(minOf(source.length, CompanyAgreementImportPolicyV2.MAX_EXTRACTED_CHARS))
        var insideDocxText = !docx
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            ensureActive()
            when (event) {
                XmlPullParser.START_TAG -> {
                    val name = parser.name.lowercase(Locale.ROOT)
                    if (docx && name == "t") insideDocxText = true
                    if (name == "tab") output.append(' ')
                    if (name == "br") output.append('\n')
                }
                XmlPullParser.TEXT -> if (insideDocxText) output.append(parser.text)
                XmlPullParser.END_TAG -> {
                    val name = parser.name.lowercase(Locale.ROOT)
                    if (docx && name == "t") insideDocxText = false
                    if (name in setOf("p", "div", "section", "article", "tr", "li")) output.append('\n')
                }
            }
            if (output.length > CompanyAgreementImportPolicyV2.MAX_EXTRACTED_CHARS) break
            event = parser.next()
        }
        return output.toString()
    }

    private fun extractPdf(file: File, onProgress: (String) -> Unit): Extraction {
        val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        val renderer = try {
            PdfRenderer(descriptor)
        } catch (error: Throwable) {
            descriptor.close()
            throw ImportException("PDF illisible ou protégé par un mot de passe.")
        }
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        try {
            val pageCount = renderer.pageCount
            if (pageCount <= 0) throw ImportException("Le PDF ne contient aucune page.")
            if (pageCount > CompanyAgreementImportPolicyV2.MAX_PDF_PAGES) {
                throw ImportException("Le PDF contient plus de ${CompanyAgreementImportPolicyV2.MAX_PDF_PAGES} pages. Sélectionne uniquement le document de l’accord.")
            }
            val output = StringBuilder()
            var truncated = false
            for (index in 0 until pageCount) {
                ensureActive()
                onProgress("Analyse locale du PDF — page ${index + 1}/$pageCount")
                try {
                    renderer.openPage(index).use { page ->
                        val rawWidth = page.width.coerceAtLeast(1)
                        val rawHeight = page.height.coerceAtLeast(1)
                        val scale = minOf(1_800f / rawWidth, 2_400f / rawHeight, 3f).coerceAtLeast(0.01f)
                        val width = (rawWidth * scale).toInt().coerceIn(1, 1_800)
                        val height = (rawHeight * scale).toInt().coerceIn(1, 2_400)
                        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                        try {
                            bitmap.eraseColor(Color.WHITE)
                            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            val result = Tasks.await(
                                recognizer.process(InputImage.fromBitmap(bitmap, 0)),
                                45,
                                TimeUnit.SECONDS
                            )
                            if (result.text.isNotBlank()) output.append(result.text).append("\n\n")
                        } finally {
                            bitmap.recycle()
                        }
                    }
                } catch (error: InterruptedException) {
                    throw error
                } catch (_: Exception) {
                    truncated = true
                }
                if (output.length > CompanyAgreementImportPolicyV2.MAX_EXTRACTED_CHARS) {
                    truncated = true
                    break
                }
            }
            return Extraction(output.toString(), pageCount = pageCount, truncated = truncated)
        } finally {
            recognizer.close()
            renderer.close()
            descriptor.close()
        }
    }

    private fun extractImage(file: File, onProgress: (String) -> Unit): Extraction {
        onProgress("Lecture locale de l’image…")
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw ImportException("Image illisible.")
        if (bounds.outWidth > 100_000 || bounds.outHeight > 100_000) throw ImportException("Dimensions de l’image non prises en charge.")
        var sample = 1
        while ((bounds.outWidth / sample) * (bounds.outHeight / sample).toLong() > 8_000_000L ||
            bounds.outWidth / sample > 3_200 || bounds.outHeight / sample > 3_200
        ) sample *= 2
        val bitmap = BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: throw ImportException("Image illisible.")
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        return try {
            val result = try {
                Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, 0)), 45, TimeUnit.SECONDS)
            } catch (error: InterruptedException) {
                throw error
            } catch (_: Exception) {
                return Extraction("", pageCount = 1, truncated = true)
            }
            Extraction(result.text, pageCount = 1)
        } finally {
            recognizer.close()
            bitmap.recycle()
        }
    }

    private fun ensureActive() {
        if (Thread.currentThread().isInterrupted) throw InterruptedException("Import annulé")
    }

    private fun defaultMime(kind: CompanyAgreementImportPolicyV2.Kind): String = when (kind) {
        CompanyAgreementImportPolicyV2.Kind.TEXT -> "text/plain"
        CompanyAgreementImportPolicyV2.Kind.HTML -> "text/html"
        CompanyAgreementImportPolicyV2.Kind.XML -> "application/xml"
        CompanyAgreementImportPolicyV2.Kind.DOCX -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        CompanyAgreementImportPolicyV2.Kind.PDF -> "application/pdf"
        CompanyAgreementImportPolicyV2.Kind.IMAGE -> "image/jpeg"
        CompanyAgreementImportPolicyV2.Kind.UNSUPPORTED -> "application/octet-stream"
    }

    private fun isMimeCompatible(kind: CompanyAgreementImportPolicyV2.Kind, mimeType: String): Boolean {
        val mime = mimeType.lowercase(Locale.ROOT)
        return when (kind) {
            CompanyAgreementImportPolicyV2.Kind.TEXT -> mime.startsWith("text/") || mime == "application/json"
            CompanyAgreementImportPolicyV2.Kind.HTML -> mime == "text/html" || mime == "application/xhtml+xml"
            CompanyAgreementImportPolicyV2.Kind.XML -> mime == "application/xml" || mime == "text/xml"
            CompanyAgreementImportPolicyV2.Kind.DOCX -> mime == "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            CompanyAgreementImportPolicyV2.Kind.PDF -> mime == "application/pdf"
            CompanyAgreementImportPolicyV2.Kind.IMAGE -> mime.startsWith("image/")
            CompanyAgreementImportPolicyV2.Kind.UNSUPPORTED -> false
        }
    }
}

/** Keeps the selected original in private app storage and exposes only that narrow directory. */
object CompanyAgreementDocumentStoreV2 {
    data class Persisted(val relativePath: String, val createdNew: Boolean)

    fun persist(context: Context, companyId: String, result: CompanyAgreementDocumentReaderV2.Result): Persisted {
        val root = File(context.filesDir, "company_agreements").apply {
            if (!exists() && !mkdirs()) throw CompanyAgreementDocumentReaderV2.ImportException("Impossible de conserver le document.")
        }
        val companySegment = companyId.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.take(80).ifBlank { "company" }
        val directory = File(root, companySegment).apply {
            if (!exists() && !mkdirs()) throw CompanyAgreementDocumentReaderV2.ImportException("Impossible de conserver le document.")
        }
        val extension = imageAwareExtension(result)
        val target = File(directory, "${result.sha256}.$extension")
        val createdNew = !target.exists()
        if (createdNew) {
            if (!result.temporaryFile.renameTo(target)) {
                result.temporaryFile.copyTo(target, overwrite = false)
                result.temporaryFile.delete()
            }
        } else {
            result.temporaryFile.delete()
        }
        val relative = target.relativeTo(context.filesDir).invariantSeparatorsPath
        return Persisted(relativePath = relative, createdNew = createdNew)
    }

    fun discard(result: CompanyAgreementDocumentReaderV2.Result?) {
        result?.temporaryFile?.takeIf { it.isFile }?.delete()
    }

    fun contentUri(context: Context, relativePath: String): Uri? {
        val file = resolve(context, relativePath) ?: return null
        return runCatching {
            FileProvider.getUriForFile(context, "${context.packageName}.agreement-files", file)
        }.getOrNull()
    }

    fun rollback(context: Context, persisted: Persisted) {
        if (!persisted.createdNew) return
        resolve(context, persisted.relativePath)?.delete()
    }

    private fun resolve(context: Context, relativePath: String): File? {
        if (relativePath.isBlank() || relativePath.startsWith('/') || relativePath.split('/').any { it == ".." }) return null
        val root = File(context.filesDir, "company_agreements").canonicalFile
        val candidate = File(context.filesDir, relativePath).canonicalFile
        val inside = candidate.path.startsWith(root.path + File.separator)
        return candidate.takeIf { inside && it.isFile }
    }

    private fun imageAwareExtension(result: CompanyAgreementDocumentReaderV2.Result): String {
        if (result.kind != CompanyAgreementImportPolicyV2.Kind.IMAGE) {
            return CompanyAgreementImportPolicyV2.extensionFor(result.kind)
        }
        val mime = result.mimeType.lowercase(Locale.ROOT)
        val name = result.displayName.lowercase(Locale.ROOT)
        return when {
            mime == "image/png" || name.endsWith(".png") -> "png"
            mime == "image/webp" || name.endsWith(".webp") -> "webp"
            else -> "jpg"
        }
    }
}
