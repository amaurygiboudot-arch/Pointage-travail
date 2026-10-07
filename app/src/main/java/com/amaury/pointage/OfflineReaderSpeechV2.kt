package com.amaury.pointage

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import java.lang.ref.WeakReference
import java.util.Locale

/** A dialog-scoped, explicitly started reader. Never creates an engine during view traversal. */
internal class OfflineReaderSpeechV2(context: Context, private val owner: String,
                                     private val status: (String) -> Unit) : Application.ActivityLifecycleCallbacks {
    private val app = context.applicationContext as Application
    private val host = WeakReference(findActivity(context))
    private val main = Handler(Looper.getMainLooper())
    private var engine: TextToSpeech? = null
    private var generation = 0
    private var registered = false
    private var disposed = false
    private var chunks = emptyList<String>()
    private var next = 0
    private var selectedVoiceName: String? = null
    private val initializationTimeout = Runnable { fail("Le moteur vocal ne répond pas.") }

    fun read(text: CharSequence, locale: Locale) {
        if (disposed || owner != PersonalizationStoreV2.accountScope()) return
        stop()
        if (text.isBlank()) { status("Aucun texte à lire."); return }
        if (text.length > ReaderSpeechChunksV2.MAX_TEXT_LENGTH) {
            status("Sélectionne un passage de 50 000 caractères maximum."); return
        }
        val content = text.toString()
        val run = generation
        app.registerActivityLifecycleCallbacks(this)
        registered = true
        status("Préparation de la voix hors ligne…")
        main.postDelayed(initializationTimeout, 8_000)
        try {
            // Post the init callback: some engines fail synchronously before constructor assignment.
            engine = TextToSpeech(app) { result -> main.post {
                if (run != generation || disposed) return@post
                main.removeCallbacks(initializationTimeout)
                if (!stillAllowed() || result != TextToSpeech.SUCCESS) { fail("Lecture vocale hors ligne indisponible."); return@post }
                val active = engine ?: return@post
                val voice = runCatching { selectInstalledVoice(active.voices.orEmpty(), locale) }.getOrNull()
                if (voice == null) { fail("Aucune voix hors ligne installée pour cette langue dans le moteur configuré. Aucun téléchargement n’a été lancé."); return@post }
                if (runCatching { active.setVoice(voice) }.getOrDefault(TextToSpeech.ERROR) != TextToSpeech.SUCCESS ||
                    active.voice?.name != voice.name || active.voice?.isNetworkConnectionRequired != false) {
                    fail("La voix hors ligne n’a pas pu être sélectionnée."); return@post
                }
                selectedVoiceName = voice.name
                chunks = runCatching { ReaderSpeechChunksV2.split(content, TextToSpeech.getMaxSpeechInputLength().coerceAtMost(3_500)) }
                    .getOrElse { fail(it.message ?: "Ce texte ne peut pas être lu."); return@post }
                next = 0
                active.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) = Unit
                    override fun onDone(id: String?) { main.post {
                        if (run == generation && id == "$run:${next - 1}") speakNext(run)
                    } }
                    @Deprecated("Legacy engines use this callback")
                    override fun onError(id: String?) { main.post { if (run == generation) fail("La lecture vocale a été interrompue.") } }
                    override fun onError(id: String?, errorCode: Int) { main.post { if (run == generation) fail("La voix hors ligne ne peut pas lire ce passage.") } }
                })
                status("Lecture en cours, avec une voix hors ligne.")
                speakNext(run)
            } }
        } catch (_: Exception) { fail("Lecture vocale hors ligne indisponible.") }
    }
    private fun speakNext(run: Int) {
        if (run != generation) return
        if (!stillAllowed()) { stop(); return }
        if (next >= chunks.size) { stop(); status("Lecture terminée."); return }
        val active = engine ?: return
        // Recheck before every chunk; never fall back to a voice that requires a network connection.
        val voice = active.voice
        if (voice == null || voice.name != selectedVoiceName || voice.isNetworkConnectionRequired ||
            TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED in voice.features.orEmpty()) {
            fail("La voix hors ligne n’est plus disponible."); return
        }
        val index = next++
        val result = runCatching { active.speak(chunks[index], TextToSpeech.QUEUE_FLUSH, null, "$run:$index") }
            .getOrDefault(TextToSpeech.ERROR)
        if (result != TextToSpeech.SUCCESS) fail("La lecture vocale n’a pas pu démarrer.")
    }
    fun stop() {
        generation++
        main.removeCallbacks(initializationTimeout)
        chunks = emptyList()
        next = 0
        selectedVoiceName = null
        val previous = engine
        engine = null
        runCatching { previous?.stop() }
        runCatching { previous?.shutdown() }
        if (registered) { app.unregisterActivityLifecycleCallbacks(this); registered = false }
        status("Lecture arrêtée.")
    }
    fun dispose() {
        if (disposed) return
        disposed = true
        stop()
    }
    private fun fail(message: String) { stop(); status(message) }
    private fun stillAllowed() = !disposed && owner == PersonalizationStoreV2.accountScope() &&
        host.get()?.let { !it.isFinishing && !it.isDestroyed } == true
    override fun onActivityPaused(activity: Activity) { if (activity === host.get()) stop() }
    override fun onActivityDestroyed(activity: Activity) { if (activity === host.get()) dispose() }
    override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
    companion object {
        private fun selectInstalledVoice(voices: Set<Voice>, locale: Locale): Voice? = voices.asSequence()
            .filter { !it.isNetworkConnectionRequired && it.locale.language == locale.language &&
                (locale.script.isEmpty() || it.locale.script.isEmpty() || it.locale.script == locale.script) &&
                TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features.orEmpty() }
            .sortedWith(compareByDescending<Voice> { it.locale == locale }
                .thenByDescending { it.locale.country == locale.country }.thenByDescending { it.quality })
            .firstOrNull()
        private fun findActivity(context: Context): Activity? {
            var current = context
            val visited = mutableSetOf<Context>()
            while (visited.add(current)) {
                if (current is Activity) return current
                current = (current as? ContextWrapper)?.baseContext ?: return null
            }
            return null
        }
    }
}
