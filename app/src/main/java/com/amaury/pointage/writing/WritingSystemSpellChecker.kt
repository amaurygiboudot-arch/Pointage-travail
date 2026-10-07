package com.amaury.pointage.writing

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.textservice.SentenceSuggestionsInfo
import android.view.textservice.SpellCheckerSession
import android.view.textservice.SuggestionsInfo
import android.view.textservice.TextInfo
import android.view.textservice.TextServicesManager
import java.util.Locale

/** User-invoked Android provider. No backend, API key, telemetry or automatic remote fallback. */
internal class WritingSystemSpellChecker(private val context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private var session: SpellCheckerSession? = null
    private var completion: ((Result<List<WritingProviderEdits.Candidate>>) -> Unit)? = null
    private var generation = 0
    private val timeout = Runnable { finish(Result.failure(IllegalStateException("Le correcteur Android n’a pas répondu."))) }

    @Suppress("DEPRECATION")
    fun request(text: String, language: String, callback: (Result<List<WritingProviderEdits.Candidate>>) -> Unit) {
        cancel()
        completion = callback
        if (text.isBlank() || text.length > 5_000) {
            finish(Result.failure(IllegalArgumentException("Sélectionne un passage de 5 000 caractères maximum."))); return
        }
        val manager = context.getSystemService(Context.TEXT_SERVICES_MANAGER_SERVICE) as? TextServicesManager
        try {
            val requestedGeneration = generation
            val listener = object : SpellCheckerSession.SpellCheckerSessionListener {
                override fun onGetSentenceSuggestions(results: Array<out SentenceSuggestionsInfo>?) {
                    if (requestedGeneration == generation) receive(results)
                }
                override fun onGetSuggestions(results: Array<out SuggestionsInfo>?) = Unit
            }
            session = manager?.newSpellCheckerSession(null, Locale.forLanguageTag(language), listener, false)
            if (session == null) {
                finish(Result.failure(IllegalStateException("Aucun correcteur Android disponible pour cette langue. Vérifie les réglages système.")))
                return
            }
            main.postDelayed(timeout, 8_000)
            session?.getSentenceSuggestions(arrayOf(TextInfo(text, COOKIE, generation)), 3)
        } catch (_: Exception) {
            finish(Result.failure(IllegalStateException("Le correcteur Android est indisponible.")))
        }
    }
    private fun receive(results: Array<out SentenceSuggestionsInfo>?) {
        if (completion == null) return
        val candidates = mutableListOf<WritingProviderEdits.Candidate>()
        results.orEmpty().forEach { sentence ->
            for (index in 0 until sentence.suggestionsCount.coerceAtMost(1_000)) {
                val info = sentence.getSuggestionsInfoAt(index) ?: continue
                if (info.cookie != COOKIE || info.sequence != generation) continue
                val typo = info.suggestionsAttributes and SuggestionsInfo.RESULT_ATTR_LOOKS_LIKE_TYPO != 0
                // API 31 grammar bit: the constant is inlined; older providers simply never return it.
                val grammar = info.suggestionsAttributes and SuggestionsInfo.RESULT_ATTR_LOOKS_LIKE_GRAMMAR_ERROR != 0
                if (!typo && !grammar) continue
                val options = (0 until info.suggestionsCount.coerceIn(0, 3)).mapNotNull { info.getSuggestionAt(it) }
                candidates += WritingProviderEdits.Candidate(sentence.getOffsetAt(index), sentence.getLengthAt(index), options)
            }
        }
        finish(Result.success(candidates))
    }
    fun cancel() {
        generation++
        completion = null
        main.removeCallbacks(timeout)
        runCatching { session?.cancel() }
        runCatching { session?.close() }
        session = null
    }
    private fun finish(result: Result<List<WritingProviderEdits.Candidate>>) {
        val callback = completion ?: return
        cancel()
        callback(result)
    }
    companion object { private const val COOKIE = 0x57524954 }
}
