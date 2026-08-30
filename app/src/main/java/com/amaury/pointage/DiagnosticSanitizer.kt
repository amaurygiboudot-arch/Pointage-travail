package com.amaury.pointage

/**
 * Réduit les risques de fuite de données dans les diagnostics techniques.
 * Le but est de conserver le type d'erreur et les emplacements de code utiles,
 * sans conserver les valeurs utilisateur ou les contenus de requêtes.
 */
object DiagnosticSanitizer {
    private val email = Regex("[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}", RegexOption.IGNORE_CASE)
    private val url = Regex("https?://\\S+", RegexOption.IGNORE_CASE)
    private val contentUri = Regex("(?:content|file)://\\S+", RegexOption.IGNORE_CASE)
    private val bearer = Regex("(?i)\\b(?:bearer|token|api[_ -]?key|authorization)\\s*[:=]?\\s*[^\\s,;]+")
    private val coordinates = Regex("(?<!\\d)-?\\d{1,3}\\.\\d{4,}\\s*[,; ]\\s*-?\\d{1,3}\\.\\d{4,}(?!\\d)")
    private val phoneLike = Regex("(?<!\\w)(?:\\+?33|0)[1-9](?:[ .-]?\\d{2}){4}(?!\\w)")
    private val longDigits = Regex("(?<!\\d)\\d{9,}(?!\\d)")

    fun message(raw: String?, max: Int = 240): String {
        if (raw.isNullOrBlank()) return "Aucun détail supplémentaire."
        return redact(raw.replace('\n', ' ').replace('\r', ' '))
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(max)
            .ifBlank { "Détail technique masqué." }
    }

    fun safeThrowable(original: Throwable): Throwable {
        val safe = RuntimeException("${original::class.java.name}: ${message(original.message)}")
        safe.stackTrace = safeFrames(original)
        return safe
    }

    fun stackSummary(throwable: Throwable, maxFrames: Int = 28): String = buildString {
        appendLine("Pile technique (valeurs masquées) :")
        safeFrames(throwable, maxFrames).forEach { frame -> appendLine("  at $frame") }
        throwable.cause?.takeIf { it !== throwable }?.let { cause ->
            appendLine("Cause : ${cause::class.java.name}: ${message(cause.message)}")
            safeFrames(cause, 8).forEach { frame -> appendLine("  at $frame") }
        }
    }.trimEnd()

    fun redact(raw: String): String = raw
        .replace(email, "[email masqué]")
        .replace(url, "[url masquée]")
        .replace(contentUri, "[uri masquée]")
        .replace(bearer, "[secret masqué]")
        .replace(coordinates, "[coordonnées masquées]")
        .replace(phoneLike, "[téléphone masqué]")
        .replace(longDigits, "[identifiant numérique masqué]")

    private fun safeFrames(throwable: Throwable, maxFrames: Int = 28): Array<StackTraceElement> =
        throwable.stackTrace
            .asSequence()
            .filterNot { it.className.startsWith("java.lang.reflect.") }
            .take(maxFrames)
            .toList()
            .toTypedArray()
}
