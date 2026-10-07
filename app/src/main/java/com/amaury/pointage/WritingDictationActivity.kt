package com.amaury.pointage

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import android.text.InputType
import android.text.Editable
import android.text.TextWatcher
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.amaury.pointage.writing.WritingDictationBridge
import com.google.firebase.auth.FirebaseAuth

/** Non-exported relay to the installed speech-input activity. No microphone recording in this app. */
class WritingDictationActivity : Activity() {
    private var token = ""
    private var pendingRecognition = false
    private var auth: FirebaseAuth? = null
    private var consent: AlertDialog? = null
    private val authListener = FirebaseAuth.AuthStateListener {
        if (token.isNotEmpty() && WritingDictationBridge.request(token) == null) cancel()
    }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        token = state?.getString(WritingDictationBridge.TOKEN)
            ?: intent.getStringExtra(WritingDictationBridge.TOKEN).orEmpty()
        val request = WritingDictationBridge.request(token) ?: run { finish(); return }
        auth = runCatching { FirebaseAuth.getInstance() }.getOrNull()
        auth?.addAuthStateListener(authListener)
        pendingRecognition = state?.getBoolean("recognition_pending") ?: false
        request.preview?.let { preview(it); return }
        if (!pendingRecognition) askConsent()
    }
    private fun askConsent() {
        val request = WritingDictationBridge.request(token) ?: run { cancel(); return }
        consent = AlertDialog.Builder(this).setTitle("Dictée avec le service Android")
            .setMessage("Le service vocal configuré sur ton téléphone gère l’écoute et peut transmettre l’audio à son fournisseur. Le mode hors ligne sera demandé, sans garantie que le fournisseur le respecte. AGKGMG ne conserve aucun enregistrement. Tu vérifieras et confirmeras le texte avant insertion.")
            .setPositiveButton("Démarrer la dictée") { _, _ -> launchRecognition(request.language) }
            .setNegativeButton("Annuler") { _, _ -> cancel() }
            .setOnCancelListener { cancel() }.show().also { PersonalizationRuntimeV2.track(it) }
    }
    @Suppress("DEPRECATION")
    private fun launchRecognition(language: String) {
        if (WritingDictationBridge.request(token) == null) { cancel(); return }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
            .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "Dicte ton texte ; tu pourras le vérifier avant insertion.")
        try { pendingRecognition = true; startActivityForResult(intent, RECOGNIZE) }
        catch (_: ActivityNotFoundException) { fail("Aucun service de dictée Android disponible.") }
        catch (_: SecurityException) { fail("Le service de dictée Android n’est pas autorisé.") }
    }
    @Deprecated("Platform activity result entry point is used to avoid a new dependency")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != RECOGNIZE) return
        pendingRecognition = false
        if (WritingDictationBridge.request(token) == null) { cancel(); return }
        if (resultCode != RESULT_OK) { cancel(); return }
        val result = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (result.isNullOrBlank()) { fail("Aucun texte reconnu ; ton texte initial est conservé."); return }
        if (result.length > 5_000) { fail("Dictée trop longue. Recommence avec moins de 5 000 caractères."); return }
        WritingDictationBridge.request(token)?.preview = result
        preview(result)
    }
    private fun preview(text: String) {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        panel.addView(TextView(this).apply {
            this.text = "Vérifie la transcription, en particulier les noms, nombres et négations. Rien n’a encore été inséré."
            textSize = 18f
        })
        val editor = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 5
            setText(text)
            isSaveEnabled = false
            UniversalWritingInstaller.exclude(this)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    WritingDictationBridge.request(token)?.preview = s?.toString()
                }
            })
        }
        panel.addView(editor, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        panel.addView(Button(this).apply {
            this.text = "Insérer le texte vérifié"
            setOnClickListener {
                val value = editor.text.toString()
                if (value.isBlank() || value.length > 5_000) { editor.error = "Entre 1 et 5 000 caractères"; return@setOnClickListener }
                if (!WritingDictationBridge.deliver(token, value)) Toast.makeText(this@WritingDictationActivity,
                    "Le champ ou le compte a changé. Aucun texte n’a été remplacé.", Toast.LENGTH_LONG).show()
                finish()
            }
        })
        panel.addView(Button(this).apply {
            this.text = "Recommencer la dictée"
            setOnClickListener {
                WritingDictationBridge.request(token)?.let { it.preview = null; launchRecognition(it.language) } ?: cancel()
            }
        })
        panel.addView(Button(this).apply { this.text = "Annuler"; setOnClickListener { cancel() } })
        setContentView(ScrollView(this).apply { addView(panel) })
    }
    override fun onSaveInstanceState(state: Bundle) {
        super.onSaveInstanceState(state)
        state.putString(WritingDictationBridge.TOKEN, token)
        state.putBoolean("recognition_pending", pendingRecognition)
        // No transcript/audio/field data is persisted into the system state Bundle.
    }
    override fun onResume() {
        super.onResume()
        if (token.isNotEmpty() && WritingDictationBridge.request(token) == null) cancel()
    }
    override fun onDestroy() {
        auth?.removeAuthStateListener(authListener)
        consent?.dismiss()
        if (isFinishing) WritingDictationBridge.cancel(token)
        super.onDestroy()
    }
    private fun fail(message: String) { Toast.makeText(this, message, Toast.LENGTH_LONG).show(); cancel() }
    private fun cancel() {
        WritingDictationBridge.cancel(token)
        if (pendingRecognition) runCatching { finishActivity(RECOGNIZE) }
        pendingRecognition = false
        finish()
    }
    companion object { private const val RECOGNIZE = 1 }
}
