package com.amaury.pointage.writing

import org.junit.Assert.*
import org.junit.Test

class WritingProviderEditsTest {
    @Test fun refusesInvalidRangesNumbersAndBrokenUnicode() {
        val text = "😀 125 travial"
        val candidates = listOf(
            WritingProviderEdits.Candidate(-1, 1, listOf("x")),
            WritingProviderEdits.Candidate(Int.MAX_VALUE, 10, listOf("x")),
            WritingProviderEdits.Candidate(1, 1, listOf("x")),
            WritingProviderEdits.Candidate(3, 3, listOf("150")),
            WritingProviderEdits.Candidate(7, 7, listOf("travail")))
        val result = WritingProviderEdits.suggestions(text, 4, candidates, emptySet())
        assertEquals(1, result.size)
        assertEquals("travail", result.single().replacement)
        assertEquals(4L, result.single().revision)
    }
    @Test fun personalWordsRemainProtectedAndUnsafeReplacementsExcluded() {
        val candidate = WritingProviderEdits.Candidate(0, 7, listOf("travail", "", "ligne\ntexte"))
        assertTrue(WritingProviderEdits.suggestions("Travial", 2, listOf(candidate), setOf("travial")).isEmpty())
        assertEquals(listOf("travail"), WritingProviderEdits.suggestions("Travial", 2, listOf(candidate), emptySet()).map { it.replacement })
    }
    @Test fun nativeSuggestionStillCannotReplaceNewerDocumentVersion() {
        val engine = WritingEngine(WritingEngine.Snapshot("travial", 7, 7))
        val suggestion = WritingProviderEdits.suggestions("travial", engine.revision,
            listOf(WritingProviderEdits.Candidate(0, 7, listOf("travail"))), emptySet()).single()
        engine.record(WritingEngine.Snapshot("autre texte", 11, 11))
        assertNull(engine.accept(suggestion))
        assertEquals("autre texte", engine.current.text)
    }
    @Test fun confirmedDictationCanBeUndoneWithoutLosingOriginalSelection() {
        val original = WritingEngine.Snapshot("Début fin", 6, 9)
        val engine = WritingEngine(original)
        val result = engine.accept(WritingEngine.Suggestion(engine.revision, 6, 9, "fin", "aucun paiement de 125 €", "Dictée vérifiée"))
        assertEquals("Début aucun paiement de 125 €", result?.text)
        assertEquals(original, engine.undo())
    }
}
