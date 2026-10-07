package com.amaury.pointage

import com.amaury.pointage.writing.WritingEngine
import org.junit.Assert.*
import org.junit.Test

class ReaderSpeechChunksV2Test {
    @Test fun boundedChunksPreserveTheWholeTextIncludingWhitespace() {
        val text = "Bonjour.\nCeci est un texte avec des espaces et une fin."
        val chunks = ReaderSpeechChunksV2.split(text, 12)
        assertEquals(text, chunks.joinToString(""))
        assertTrue(chunks.all { it.length <= 12 && it.isNotEmpty() })
    }
    @Test fun emojiCombiningCharactersFlagsAndRtlAreNotBroken() {
        val text = "début 👩‍💻 e\u0301 👍🏽 🇫🇷 مرحبا fin"
        val chunks = ReaderSpeechChunksV2.split(text, 8)
        assertEquals(text, chunks.joinToString(""))
        var end = 0
        for (chunk in chunks) { end += chunk.length; assertTrue(WritingEngine.safeBoundary(text, end)) }
    }
    @Test fun oversizedClusterIsRejectedInsteadOfTruncated() {
        assertThrows(IllegalArgumentException::class.java) { ReaderSpeechChunksV2.split("a" + "\u0301".repeat(10), 5) }
    }
    @Test fun maximumTextSizeIsEnforcedAndBlankTextHasNoChunks() {
        assertTrue(ReaderSpeechChunksV2.split("   \n", 10).isEmpty())
        assertThrows(IllegalArgumentException::class.java) { ReaderSpeechChunksV2.split("a".repeat(50_001), 100) }
    }
}
