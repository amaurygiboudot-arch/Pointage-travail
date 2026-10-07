package com.amaury.pointage.writing

import java.util.Locale

/** Shared, local and explicitly accepted edits. Never changes numbers or runs on structured fields. */
class WritingEngine(initial: Snapshot = Snapshot("", 0, 0)) {
    data class Snapshot(val text: String, val start: Int, val end: Int)
    data class Suggestion(val revision: Long, val start: Int, val end: Int, val original: String,
                          val replacement: String, val reason: String)
    var current: Snapshot = initial
        private set
    var revision: Long = 0
        private set
    private val undo = ArrayDeque<Snapshot>()
    private val redo = ArrayDeque<Snapshot>()
    val canUndo get() = undo.isNotEmpty()
    val canRedo get() = redo.isNotEmpty()

    fun record(next: Snapshot) {
        if (next.text == current.text) { current = next; return }
        undo.addLast(current)
        // Bound retained text, including long pasted documents, instead of unbounded history.
        while (undo.size > 40 || undo.sumOf { it.text.length.toLong() } > 512_000) undo.removeFirst()
        redo.clear()
        current = next
        revision++
    }
    fun undo(): Snapshot? {
        if (undo.isEmpty()) return null
        redo.addLast(current)
        current = undo.removeLast()
        revision++
        return current
    }
    fun redo(): Snapshot? {
        if (redo.isEmpty()) return null
        undo.addLast(current)
        current = redo.removeLast()
        revision++
        return current
    }
    fun reset(snapshot: Snapshot) { current = snapshot; undo.clear(); redo.clear(); revision++ }
    fun accept(suggestion: Suggestion): Snapshot? {
        val s = suggestion
        val text = current.text
        if (s.revision != revision || s.start !in 0..text.length || s.end !in s.start..text.length ||
            text.substring(s.start, s.end) != s.original || !safeBoundary(text, s.start) || !safeBoundary(text, s.end)) return null
        val result = text.replaceRange(s.start, s.end, s.replacement)
        val cursor = s.start + s.replacement.length
        return Snapshot(result, cursor, cursor).also(::record)
    }
    fun suggestions(language: String, dictionary: Set<String>): List<Suggestion> {
        val text = current.text
        if (text.length > 50_000) return emptyList()
        val known = dictionary.map { it.lowercase(Locale.ROOT) }.toSet()
        val results = mutableListOf<Suggestion>()
        // Conservative French vocabulary only; no unsupported grammar/AI claims.
        val corrections = if (language.startsWith("fr")) mapOf(
            "bonjor" to "bonjour", "bonjout" to "bonjour", "merçi" to "merci",
            "beaucoups" to "beaucoup", "travial" to "travail", "aujourdhui" to "aujourd’hui"
        ) else emptyMap()
        WORD.findAll(text).forEach { match ->
            if (results.size >= 12) return@forEach
            val word = match.value
            val normalized = word.lowercase(Locale.ROOT)
            val replacement = corrections[normalized]
            if (replacement != null && normalized !in known) {
                val value = when {
                    word.all { !it.isLetter() || it.isUpperCase() } -> replacement.uppercase(Locale.ROOT)
                    word.first().isUpperCase() -> replacement.replaceFirstChar { it.titlecase(Locale.ROOT) }
                    else -> replacement
                }
                results += Suggestion(revision, match.range.first, match.range.last + 1, word, value, "Orthographe locale")
            }
        }
        return results
    }
    fun completions(dictionary: Set<String>): List<Suggestion> {
        if (current.start != current.end || current.start !in 0..current.text.length || current.text.length > 50_000) return emptyList()
        val prefix = WORD.findAll(current.text.take(current.start)).lastOrNull()?.takeIf { it.range.last + 1 == current.start } ?: return emptyList()
        if (prefix.value.length < 2 || (current.start < current.text.length && current.text[current.start].isLetter())) return emptyList()
        return dictionary.filter { it.length > prefix.value.length && it.startsWith(prefix.value, ignoreCase = true) }
            .sorted().take(5).map { Suggestion(revision, prefix.range.first, current.start, prefix.value, it, "Dictionnaire personnel") }
    }
    companion object {
        private val WORD = Regex("[\\p{L}\\p{M}]+(?:[’'][\\p{L}\\p{M}]+)*")
        /** Conservative cluster guard: do not split surrogate, combining, emoji modifier or ZWJ sequences. */
        fun safeBoundary(text: String, index: Int): Boolean {
            if (index !in 0..text.length) return false
            if (index == 0 || index == text.length) return true
            if (Character.isHighSurrogate(text[index - 1]) && Character.isLowSurrogate(text[index])) return false
            val after = text.codePointAt(index)
            val before = text.codePointBefore(index)
            val type = Character.getType(after)
            if (type in listOf(Character.NON_SPACING_MARK.toInt(), Character.COMBINING_SPACING_MARK.toInt(), Character.ENCLOSING_MARK.toInt())) return false
            if (after == 0x200D || before == 0x200D || after in 0xFE00..0xFE0F || after in 0x1F3FB..0x1F3FF) return false
            if (after in 0x1F1E6..0x1F1FF && before in 0x1F1E6..0x1F1FF) return false
            return true
        }
    }
}
