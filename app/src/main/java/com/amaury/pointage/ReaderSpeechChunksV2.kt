package com.amaury.pointage

import com.amaury.pointage.writing.WritingEngine

/** Bounded, lossless chunks; never silently truncate the text or split a known Unicode cluster. */
object ReaderSpeechChunksV2 {
    const val MAX_TEXT_LENGTH = 50_000
    fun split(text: String, maximumChunkLength: Int): List<String> {
        require(text.length <= MAX_TEXT_LENGTH) { "La lecture est limitée à 50 000 caractères. Sélectionne un passage plus court." }
        require(maximumChunkLength in 2..10_000)
        if (text.isBlank()) return emptyList()
        val result = mutableListOf<String>()
        var start = 0
        while (start < text.length) {
            var end = minOf(start + maximumChunkLength, text.length)
            while (end > start && !WritingEngine.safeBoundary(text, end)) end--
            require(end > start) { "Ce passage contient un caractère composé trop long pour le moteur vocal." }
            if (end < text.length) {
                // Prefer a word boundary in the second half; keep all whitespace in the chunks.
                val preferred = (end - 1 downTo start + (end - start) / 2).firstOrNull {
                    text[it].isWhitespace() && WritingEngine.safeBoundary(text, it + 1)
                }
                if (preferred != null) end = preferred + 1
            }
            result += text.substring(start, end)
            start = end
        }
        return result
    }
}
