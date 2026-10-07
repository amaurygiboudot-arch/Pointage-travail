package com.amaury.pointage

import android.app.Activity
import android.app.AlertDialog
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.text.method.PasswordTransformationMethod
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewTreeObserver
import android.view.inputmethod.BaseInputConnection
import android.widget.EditText
import android.widget.Toast
import com.amaury.pointage.writing.WritingEngine
import com.amaury.pointage.writing.WritingLocalStore
import com.google.firebase.auth.FirebaseAuth
import java.lang.ref.WeakReference
import java.util.Locale
import java.util.WeakHashMap
import java.util.concurrent.Executors

/** One manual-only writing entry point. The OS keyboard keeps ownership of IME composition. */
object UniversalWritingInstaller {
    private val sessions = WeakHashMap<EditText, Session>()
    private val excluded = WeakHashMap<EditText, Boolean>()
    private val roots = WeakHashMap<Activity, ViewTreeObserver.OnGlobalFocusChangeListener>()
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var installed = false
    var enabled: (Context) -> Boolean = { PersonalizationStoreV2.read(it).writingAssistance }
    var language: (Context) -> String = { Locale.getDefault().language }
    private fun account() = runCatching { FirebaseAuth.getInstance().currentUser?.uid }.getOrNull() ?: "local-guest"

    fun install(application: Application) {
        if (installed) return
        installed = true
        io.execute { runCatching { WritingLocalStore(application).purgeExpiredDrafts() } }
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                if (!roots.containsKey(activity)) {
                    val listener = ViewTreeObserver.OnGlobalFocusChangeListener { _, view -> (view as? EditText)?.let(::attach) }
                    activity.window.decorView.viewTreeObserver.addOnGlobalFocusChangeListener(listener)
                    roots[activity] = listener
                }
                (activity.currentFocus as? EditText)?.let(::attach)
            }
            override fun onActivityPaused(activity: Activity) { sessions.values.toList().forEach { it.flushDraft() } }
            override fun onActivityDestroyed(activity: Activity) {
                roots.remove(activity)?.let { if (activity.window.decorView.viewTreeObserver.isAlive) activity.window.decorView.viewTreeObserver.removeOnGlobalFocusChangeListener(it) }
            }
            override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
        })
        runCatching { FirebaseAuth.getInstance().addAuthStateListener {
            main.post { sessions.values.toList().forEach { session -> session.checkAccount() } }
        } }
    }
    /** Explicit binding is required for persistent drafts: never infer a document identity from view position. */
    fun bind(field: EditText, documentKey: String) {
        require(documentKey.isNotBlank() && documentKey.length <= 160)
        attach(field)?.bindDraft(documentKey)
    }
    fun exclude(field: EditText) { excluded[field] = true }
    fun flush(field: EditText) { sessions[field]?.flushDraft() }
    fun clearSubmitted(field: EditText) { sessions[field]?.clearSubmitted() }
    fun showTools(field: EditText) { attach(field)?.tools() }
    private fun eligible(field: EditText): Boolean {
        if (excluded[field] == true || !field.isEnabled || field.transformationMethod is PasswordTransformationMethod) return false
        val type = field.inputType
        if (type and InputType.TYPE_MASK_CLASS != InputType.TYPE_CLASS_TEXT) return false
        return type and InputType.TYPE_MASK_VARIATION in setOf(InputType.TYPE_TEXT_VARIATION_NORMAL,
            InputType.TYPE_TEXT_VARIATION_LONG_MESSAGE, InputType.TYPE_TEXT_VARIATION_SHORT_MESSAGE)
    }
    private fun attach(field: EditText): Session? {
        if (!eligible(field)) return null
        return sessions[field] ?: Session(field).also { sessions[field] = it }
    }
    private class Session(field: EditText) {
        private val ref = WeakReference(field)
        private val dialogs = mutableListOf<WeakReference<AlertDialog>>()
        private val store = WritingLocalStore(field.context.applicationContext)
        private val engine = WritingEngine(snapshot(field))
        private var scope = account()
        private var document: String? = null
        private var applying = false
        private var saveSequence = 0L
        private var lastQueued = ""
        private val save = Runnable { flushDraft() }
        private val capture = Runnable {
            ref.get()?.let { view ->
                if (checkAccount() && eligible(view) && BaseInputConnection.getComposingSpanStart(view.text) < 0) {
                    engine.record(snapshot(view))
                }
            }
        }
        init {
            field.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    if (applying) return
                    val view = ref.get() ?: return
                    if (!checkAccount()) return
                    // Group rapid typing and avoid copying the complete document on every keystroke.
                    // The IME keeps its composing spans; actions capture the final version again.
                    main.removeCallbacks(capture)
                    if (view.length() <= 50_000) main.postDelayed(capture, 200)
                    main.removeCallbacks(save)
                    if (document != null) main.postDelayed(save, 500)
                }
            })
            field.customSelectionActionModeCallback = toolbar(field.customSelectionActionModeCallback)
            field.customInsertionActionModeCallback = toolbar(field.customInsertionActionModeCallback)
            field.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) { checkAccount() }
                override fun onViewDetachedFromWindow(v: View) { flushDraft() }
            })
        }
        fun checkAccount(): Boolean {
            val latest = account()
            if (scope == latest) return true
            main.removeCallbacks(save)
            main.removeCallbacks(capture)
            saveSequence++
            scope = latest
            dialogs.forEach { it.get()?.dismiss() }
            dialogs.clear()
            lastQueued = ""
            val view = ref.get() ?: return false
            // Never leave another account's unsent draft or undo history visible.
            if (document != null) { applying = true; view.setText(""); applying = false }
            engine.reset(snapshot(view))
            return false
        }
        private fun toolbar(previous: ActionMode.Callback?) = object : ActionMode.Callback {
            override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
                val result = previous?.onCreateActionMode(mode, menu) ?: true
                val view = ref.get()
                if (result && view != null && eligible(view)) menu.add(0, TOOLS, 99, "Aide à l’écriture").setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
                return result
            }
            override fun onPrepareActionMode(mode: ActionMode, menu: Menu) = previous?.onPrepareActionMode(mode, menu) ?: false
            override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
                if (item.itemId == TOOLS) { tools(); mode.finish(); return true }
                return previous?.onActionItemClicked(mode, item) ?: false
            }
            override fun onDestroyActionMode(mode: ActionMode) { previous?.onDestroyActionMode(mode) }
        }
        private fun ready(): EditText? {
            val view = ref.get() ?: return null
            if (!checkAccount() || !eligible(view)) return null
            if (BaseInputConnection.getComposingSpanStart(view.text) >= 0) { toast("Termine la saisie en cours avant cette action."); return null }
            main.removeCallbacks(capture)
            engine.record(snapshot(view))
            return view
        }
        private fun apply(value: WritingEngine.Snapshot) {
            val view = ref.get() ?: return
            main.removeCallbacks(capture)
            applying = true
            try {
                view.text.replace(0, view.length(), value.text)
                view.setSelection(value.start.coerceIn(0, view.length()), value.end.coerceIn(0, view.length()))
            } finally { applying = false }
            main.removeCallbacks(save)
            if (document != null) main.postDelayed(save, 500)
        }
        fun tools() {
            val view = ready() ?: return
            val labels = arrayOf("Annuler la dernière modification", "Rétablir", "Suggestions locales", "Dictionnaire personnel", "Récupérer le brouillon", "Effacer le brouillon enregistré", "Récupérer l’ancien brouillon local")
            AlertDialog.Builder(view.context).setTitle("Aide à l’écriture").setItems(labels) { _, which ->
                if (ready() == null) return@setItems
                when (which) {
                    0 -> engine.undo()?.let(::apply) ?: toast("Aucune modification à annuler")
                    1 -> engine.redo()?.let(::apply) ?: toast("Aucune modification à rétablir")
                    2 -> if (enabled(view.context)) suggestions() else toast("L’assistance à l’écriture est désactivée dans tes préférences.")
                    3 -> dictionary()
                    4 -> recover()
                    5 -> discardDraft()
                    6 -> recoverLegacy()
                }
            }.setNegativeButton("Fermer", null).show().track()
        }
        private fun suggestions() {
            val view = ready() ?: return
            val requestedScope = scope
            val requestedRevision = engine.revision
            val lang = language(view.context)
            io.execute {
                val result = runCatching { store.dictionary(requestedScope, lang) }
                main.post {
                    val current = ref.get() ?: return@post
                    if (!checkAccount() || scope != requestedScope || engine.revision != requestedRevision || snapshot(current).text != engine.current.text) return@post
                    val words = result.getOrElse { toast("Le dictionnaire local n’est pas accessible."); return@post }
                    val suggestions = engine.suggestions(lang, words) + engine.completions(words)
                    if (suggestions.isEmpty()) { toast("Aucune suggestion locale. La vérification grammaticale complète n’est pas disponible."); return@post }
                    AlertDialog.Builder(current.context).setTitle("Propositions à vérifier")
                        .setItems(suggestions.map { "${it.original} → ${it.replacement} (${it.reason})" }.toTypedArray()) { _, index ->
                            val choice = suggestions[index]
                            AlertDialog.Builder(current.context).setTitle("Remplacer ce passage ?")
                                .setMessage("Original : ${choice.original}\nProposition : ${choice.replacement}")
                                .setPositiveButton("Accepter") { _, _ ->
                                    if (ready() != null) engine.accept(choice)?.let(::apply) ?: toast("Le texte a changé. Relance les suggestions.")
                                }.setNegativeButton("Ignorer", null).show().track()
                        }.setNegativeButton("Fermer", null).show().track()
                }
            }
        }
        private fun dictionary() {
            val view = ready() ?: return
            val requestedScope = scope
            val lang = language(view.context)
            io.execute {
                val result = runCatching { store.dictionary(requestedScope, lang) }
                main.post {
                    val current = ref.get() ?: return@post
                    if (!checkAccount() || scope != requestedScope) return@post
                    val words = result.getOrElse { toast("Lecture du dictionnaire impossible."); return@post }
                    val editor = EditText(current.context).apply {
                        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                        exclude(this)
                        minLines = 4; maxLines = 10
                        setText(words.sorted().joinToString("\n"))
                    }
                    val dialog = AlertDialog.Builder(current.context).setTitle("Dictionnaire personnel ($lang)")
                        .setMessage("Un terme par ligne. Ajoute, modifie, colle ou supprime des termes. Stockage chiffré sur cet appareil uniquement ; 1 000 termes maximum.")
                        .setView(editor).setPositiveButton("Enregistrer", null).setNegativeButton("Annuler", null).create()
                    dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        if (!checkAccount() || scope != requestedScope) { dialog.dismiss(); return@setOnClickListener }
                        val updated = editor.text.lines().map(String::trim).filter(String::isNotBlank).toSet()
                        if (updated.size > 1000 || updated.any { it.length > 80 || it.any(Char::isISOControl) }) {
                            editor.error = "Maximum 1 000 termes, 80 caractères par terme"; return@setOnClickListener
                        }
                        io.execute { val saved = runCatching { store.saveDictionary(requestedScope, lang, updated) }
                            main.post { toast(if (saved.isSuccess) "Dictionnaire enregistré" else "Échec d’enregistrement du dictionnaire") } }
                        dialog.dismiss()
                    } }
                    dialog.show()
                    dialog.track()
                }
            }
        }
        fun bindDraft(key: String) { document = key }
        fun flushDraft() {
            main.removeCallbacks(save)
            val view = ref.get() ?: return
            val key = document ?: return
            if (!checkAccount() || !eligible(view)) return
            val snapshot = snapshot(view)
            if (snapshot.text.length > 50_000) { toast("Brouillon limité à 50 000 caractères ; copie ton texte avant de quitter."); return }
            if (snapshot.text == lastQueued) return
            lastQueued = snapshot.text
            val requestedScope = scope
            val sequence = ++saveSequence
            io.execute {
                val result = runCatching { store.saveDraft(requestedScope, key, snapshot) }
                if (result.isFailure) main.post {
                    if (sequence == saveSequence && scope == requestedScope) { lastQueued = ""; toast("Brouillon non enregistré. Conserve une copie de ton texte.") }
                }
            }
        }
        private fun recover() {
            val view = ready() ?: return
            val key = document ?: run { toast("Ce champ ne conserve pas de brouillon."); return }
            val requestedScope = scope
            val original = snapshot(view)
            io.execute {
                val result = runCatching { store.loadDraft(requestedScope, key) }
                main.post {
                    val current = ref.get() ?: return@post
                    if (!checkAccount() || scope != requestedScope || snapshot(current).text != original.text) return@post
                    val recovered = result.getOrElse { toast("Lecture du brouillon impossible."); return@post }
                        ?: run { toast("Aucun brouillon disponible (conservation : 7 jours)."); return@post }
                    AlertDialog.Builder(current.context).setTitle("Restaurer le brouillon ?")
                        .setMessage("Le texte actuel sera remplacé. Tu pourras annuler cette action.")
                        .setPositiveButton("Restaurer") { _, _ ->
                            if (ready() != null && scope == requestedScope && snapshot(current).text == original.text) {
                                engine.record(recovered); apply(recovered)
                            } else toast("Le texte a changé. Relance la récupération.")
                        }.setNegativeButton("Annuler", null).show().track()
                }
            }
        }
        private fun recoverLegacy() {
            val view = ready() ?: return
            if (document != "feedback.idea") { toast("Aucun ancien brouillon pour ce champ."); return }
            val requestedScope = scope
            val original = snapshot(view)
            AlertDialog.Builder(view.context).setTitle("Ancien brouillon sans compte associé")
                .setMessage("Un ancien brouillon peut appartenir à une autre personne ayant utilisé cet appareil. Confirme uniquement si ce brouillon t’appartient. Il sera chiffré pour le compte actuel avant suppression de l’ancienne copie.")
                .setPositiveButton("Ce brouillon m’appartient") { _, _ ->
                    if (ready() == null || scope != requestedScope || snapshot(view).text != original.text) return@setPositiveButton
                    val preferences = view.context.applicationContext.getSharedPreferences("user_feedback", Context.MODE_PRIVATE)
                    io.execute {
                        val result = runCatching {
                            val text = preferences.getString("draft_idea", null)?.takeIf { it.isNotEmpty() }
                                ?: return@runCatching store.loadDraft(requestedScope, "feedback.idea.legacy")
                            require(text.length <= 50_000)
                            val recovered = WritingEngine.Snapshot(text, text.length, text.length)
                            store.saveDraft(requestedScope, "feedback.idea.legacy", recovered)
                            // Old value is removed only after an atomic encrypted write succeeds.
                            if (preferences.getString("draft_idea", null) == text) preferences.edit().remove("draft_idea").commit()
                            recovered
                        }
                        main.post {
                            if (!checkAccount() || scope != requestedScope) return@post
                            val recovered = result.getOrElse { toast("Migration impossible ; l’ancienne copie est conservée."); return@post }
                                ?: run { toast("Aucun ancien brouillon local."); return@post }
                            if (snapshot(view).text == original.text) { engine.record(recovered); apply(recovered) }
                            else toast("Brouillon migré ; utilise Récupérer l’ancien brouillon local pour l’ouvrir.")
                        }
                    }
                }.setNegativeButton("Annuler", null).show().track()
        }
        private fun AlertDialog.track(): AlertDialog {
            dialogs.removeAll { it.get() == null }
            dialogs.add(WeakReference(this))
            PersonalizationRuntimeV2.track(this)
            return this
        }
        private fun discardDraft() {
            val key = document ?: run { toast("Ce champ ne conserve pas de brouillon."); return }
            val requestedScope = scope
            main.removeCallbacks(save)
            // Keep current text, but do not silently re-save it on pause after explicit deletion.
            lastQueued = ref.get()?.text?.toString().orEmpty()
            io.execute { val result = runCatching { store.clearDraft(requestedScope, key) }
                main.post { toast(if (result.isSuccess) "Brouillon enregistré effacé" else "Effacement impossible") } }
        }
        fun clearSubmitted() {
            checkAccount()
            val view = ref.get() ?: return
            apply(WritingEngine.Snapshot("", 0, 0))
            engine.reset(WritingEngine.Snapshot("", 0, 0))
            lastQueued = "submitted"
            flushDraft()
        }
        private fun toast(message: String) { ref.get()?.let { Toast.makeText(it.context, message, Toast.LENGTH_LONG).show() } }
        companion object {
            private fun snapshot(view: EditText) = WritingEngine.Snapshot(view.text.toString(), view.selectionStart.coerceAtLeast(0), view.selectionEnd.coerceAtLeast(0))
        }
    }
    private const val TOOLS = 0x5A170001
}
