package com.amaury.pointage.writing

/** Validate untrusted provider offsets and bind them to the exact analyzed document version. */
object WritingProviderEdits {
    data class Candidate(val start: Int, val length: Int, val replacements: List<String>)
    fun suggestions(text: String, revision: Long, candidates: List<Candidate>, dictionary: Set<String>): List<WritingEngine.Suggestion> {
        val protectedWords = dictionary.map { it.lowercase(java.util.Locale.ROOT) }.toSet()
        return candidates.asSequence().filter { it.start >= 0 && it.length > 0 && it.start <= text.length - it.length }
            .filter { WritingEngine.safeBoundary(text, it.start) && WritingEngine.safeBoundary(text, it.start + it.length) }
            .flatMap { candidate ->
                val original = text.substring(candidate.start, candidate.start + candidate.length)
                if (original.lowercase(java.util.Locale.ROOT) in protectedWords || original.any(Char::isDigit)) emptySequence()
                else candidate.replacements.asSequence().filter { it.isNotBlank() && it.length <= 240 && it != original && it.none(Char::isISOControl) }
                    .take(3).map { replacement -> WritingEngine.Suggestion(revision, candidate.start,
                        candidate.start + candidate.length, original, replacement, "Correcteur Android — à vérifier") }
            }.distinctBy { Triple(it.start, it.end, it.replacement) }.take(30).toList()
    }
}
