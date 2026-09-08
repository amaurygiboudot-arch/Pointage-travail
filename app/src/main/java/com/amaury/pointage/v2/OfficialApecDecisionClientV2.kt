package com.amaury.pointage.v2

import android.content.Context
import com.amaury.pointage.v2.engine.ConventionMinimumSalaryV2
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.TaskCompletionSource
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors

/**
 * Client lecture seule de la Commission paritaire Apec.
 *
 * Il télécharge l'index puis tous les agréments PDF rattachés exactement à l'IDCC demandé.
 * Aucun document n'est choisi par heuristique et aucun domaine tiers n'est suivi en redirection.
 */
object OfficialApecDecisionClientV2 {
    private const val MAX_INDEX_BYTES = 2 * 1024 * 1024
    private const val MAX_PDF_BYTES = 12 * 1024 * 1024
    private const val MAX_CANDIDATES = 20
    private const val MAX_REDIRECTS = 3
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 20_000
    private const val USER_AGENT = "HoraTrack APEC legal verification"
    private val executor = Executors.newSingleThreadExecutor()

    data class FetchedDocument(
        val candidate: OfficialApecDecisionIndexV2.Candidate,
        val document: OfficialApecProtectionCategoryParserV2.Document,
        val pages: Int
    )

    data class Result(
        val idcc: String,
        val candidatesFound: Int,
        val documents: List<FetchedDocument>,
        /** Vrai uniquement si chaque candidat découvert a été téléchargé et extrait sans perte. */
        val technicalCoverageComplete: Boolean,
        val warnings: List<String>
    )

    private data class Download(
        val finalUrl: String,
        val bytes: ByteArray
    )

    fun fetch(context: Context, idcc: String): Task<Result> {
        val completion = TaskCompletionSource<Result>()
        val app = context.applicationContext
        executor.execute {
            try {
                completion.setResult(fetchBlocking(app, idcc))
            } catch (error: Exception) {
                completion.setException(error)
            } catch (error: Throwable) {
                completion.setException(RuntimeException(error))
            }
        }
        return completion.task
    }

    private fun fetchBlocking(context: Context, idcc: String): Result {
        val wanted = ConventionMinimumSalaryV2.normalizeIdcc(idcc)
        if (wanted.isBlank()) {
            return Result("", 0, emptyList(), false, listOf("APEC : IDCC invalide ; collecte non lancée."))
        }

        val indexDownload = runCatching {
            download(
                initialUrl = OfficialApecDecisionIndexV2.INDEX_URL,
                maxBytes = MAX_INDEX_BYTES,
                requirePdf = false,
                accept = "text/html,application/xhtml+xml"
            )
        }.getOrElse { error ->
            return Result(
                idcc = wanted,
                candidatesFound = 0,
                documents = emptyList(),
                technicalCoverageComplete = false,
                warnings = listOf("APEC : index officiel inaccessible (${error.javaClass.simpleName}) ; aucune absence d'agrément n'est déduite.")
            )
        }

        val html = String(indexDownload.bytes, StandardCharsets.UTF_8)
        val index = OfficialApecDecisionIndexV2.parse(html, wanted)
        val candidates = index.candidates
        if (candidates.isEmpty()) {
            return Result(
                idcc = wanted,
                candidatesFound = 0,
                documents = emptyList(),
                technicalCoverageComplete = false,
                warnings = (index.reasons + "APEC : aucun document officiel exploitable n'est disponible pour validation automatique.").distinct()
            )
        }
        if (candidates.size > MAX_CANDIDATES) {
            return Result(
                idcc = wanted,
                candidatesFound = candidates.size,
                documents = emptyList(),
                technicalCoverageComplete = false,
                warnings = (index.reasons + "APEC : nombre d'agréments anormalement élevé pour l'IDCC $wanted ; téléchargement automatique bloqué.").distinct()
            )
        }

        val fetched = mutableListOf<FetchedDocument>()
        val warnings = index.reasons.toMutableList()
        candidates.forEach { candidate ->
            val download = runCatching {
                download(
                    initialUrl = candidate.sourceUrl,
                    maxBytes = MAX_PDF_BYTES,
                    requirePdf = true,
                    accept = "application/pdf"
                )
            }.getOrElse { error ->
                warnings += "APEC ${candidate.documentId} : téléchargement officiel impossible (${error.javaClass.simpleName})."
                return@forEach
            }

            val extraction = OfficialApecPdfTextExtractorV2.extract(context, download.bytes)
            val content = extraction.content
            if (content == null) {
                warnings += extraction.reasons.map { "APEC ${candidate.documentId} : $it" }
                return@forEach
            }

            fetched += FetchedDocument(
                candidate = candidate,
                document = OfficialApecProtectionCategoryParserV2.Document(
                    documentId = candidate.documentId,
                    sourceUrl = download.finalUrl,
                    content = content
                ),
                pages = extraction.pages
            )
        }

        val complete = fetched.size == candidates.size
        return Result(
            idcc = wanted,
            candidatesFound = candidates.size,
            documents = fetched,
            technicalCoverageComplete = complete,
            warnings = buildList {
                addAll(warnings)
                if (!complete) {
                    add("APEC : tous les agréments trouvés n'ont pas pu être contrôlés ; couverture documentaire incomplète.")
                }
                if (fetched.isNotEmpty()) {
                    add("APEC : ${fetched.size} agrément(s) officiel(s) téléchargé(s) avec couche texte native exploitable.")
                }
            }.distinct()
        )
    }

    private fun download(
        initialUrl: String,
        maxBytes: Int,
        requirePdf: Boolean,
        accept: String
    ): Download {
        var current = initialUrl
        repeat(MAX_REDIRECTS + 1) { redirectCount ->
            if (!isAllowedOfficialUrl(current, requirePdf)) {
                throw IOException("URL APEC refusée")
            }
            val connection = URI(current).toURL().openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "GET"
                connection.instanceFollowRedirects = false
                connection.connectTimeout = CONNECT_TIMEOUT_MS
                connection.readTimeout = READ_TIMEOUT_MS
                connection.useCaches = false
                connection.setRequestProperty("Accept", accept)
                connection.setRequestProperty("User-Agent", USER_AGENT)
                connection.setRequestProperty("Connection", "close")

                val status = connection.responseCode
                if (status in 300..399) {
                    if (redirectCount >= MAX_REDIRECTS) throw IOException("Trop de redirections APEC")
                    val location = connection.getHeaderField("Location") ?: throw IOException("Redirection APEC sans cible")
                    current = resolveAllowedRedirect(current, location, requirePdf)
                        ?: throw IOException("Redirection APEC hors domaine officiel")
                    return@repeat
                }
                if (status != HttpURLConnection.HTTP_OK) {
                    throw IOException("HTTP APEC $status")
                }

                val announced = connection.contentLengthLong
                if (announced > maxBytes) throw IOException("Réponse APEC trop volumineuse")
                val bytes = connection.inputStream.use { readBounded(it, maxBytes) }
                return Download(current, bytes)
            } finally {
                connection.disconnect()
            }
        }
        throw IOException("Redirections APEC non résolues")
    }

    internal fun isAllowedOfficialUrl(value: String, requirePdf: Boolean): Boolean = runCatching {
        val uri = URI(value.trim())
        if (!uri.scheme.equals("https", ignoreCase = true)) return@runCatching false
        if (!uri.host.equals("commission-paritaire.apec.fr", ignoreCase = true)) return@runCatching false
        if (!requirePdf) return@runCatching true
        val path = uri.path.orEmpty()
        path.startsWith("/assets/files/", ignoreCase = true) && path.endsWith(".pdf", ignoreCase = true)
    }.getOrDefault(false)

    internal fun resolveAllowedRedirect(currentUrl: String, location: String, requirePdf: Boolean): String? = runCatching {
        val resolved = URI(currentUrl).resolve(location.trim()).toASCIIString()
        resolved.takeIf { isAllowedOfficialUrl(it, requirePdf) }
    }.getOrNull()

    internal fun readBounded(input: InputStream, maxBytes: Int): ByteArray {
        require(maxBytes > 0)
        val output = ByteArrayOutputStream(minOf(8192, maxBytes))
        val buffer = ByteArray(8192)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > maxBytes) throw IOException("Réponse APEC trop volumineuse")
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }
}
