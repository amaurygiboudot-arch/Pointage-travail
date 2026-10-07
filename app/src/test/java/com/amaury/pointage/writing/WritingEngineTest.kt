package com.amaury.pointage.writing

import org.junit.Assert.*
import org.junit.Test

class WritingEngineTest {
    @Test fun staleSuggestionNeverOverwritesLaterTyping() {
        val engine = WritingEngine(WritingEngine.Snapshot("bonjor", 6, 6))
        val suggestion = engine.suggestions("fr", emptySet()).single()
        engine.record(WritingEngine.Snapshot("bonjor Amaury", 13, 13))
        assertNull(engine.accept(suggestion))
        assertEquals("bonjor Amaury", engine.current.text)
    }
    @Test fun acceptedCorrectionUndoRedoPreservesOriginalAndSelection() {
        val original = WritingEngine.Snapshot("bonjor 1 234,50 €", 2, 4)
        val engine = WritingEngine(original)
        assertEquals("bonjour 1 234,50 €", engine.accept(engine.suggestions("fr", emptySet()).single())?.text)
        assertEquals(original, engine.undo())
        assertEquals("bonjour 1 234,50 €", engine.redo()?.text)
        engine.undo()
        engine.record(WritingEngine.Snapshot("autre", 5, 5))
        assertNull(engine.redo())
    }
    @Test fun dictionarySuppressesCorrectionAndCompletesLocally() {
        val engine = WritingEngine(WritingEngine.Snapshot("bonjor", 6, 6))
        assertTrue(engine.suggestions("fr", setOf("bonjor")).isEmpty())
        engine.reset(WritingEngine.Snapshot("Oce", 3, 3))
        assertEquals("Oceplast", engine.completions(setOf("Oceplast")).single().replacement)
        assertTrue(engine.suggestions("en", emptySet()).isEmpty())
    }
    @Test fun unicodeClustersAreNeverSplit() {
        assertFalse(WritingEngine.safeBoundary("a😀b", 2))
        assertFalse(WritingEngine.safeBoundary("e\u0301", 1))
        assertFalse(WritingEngine.safeBoundary("👩‍💻", 2))
        assertFalse(WritingEngine.safeBoundary("👍🏽", 2))
        assertFalse(WritingEngine.safeBoundary("🇫🇷", 2))
        assertTrue(WritingEngine.safeBoundary("مرحبا", 2))
    }
    @Test fun longTextKeepsEditingButSkipsAnalysis() {
        val engine = WritingEngine()
        engine.record(WritingEngine.Snapshot("x".repeat(50_001), 50_001, 50_001))
        assertTrue(engine.suggestions("fr", emptySet()).isEmpty())
        assertEquals("", engine.undo()?.text)
    }
}
