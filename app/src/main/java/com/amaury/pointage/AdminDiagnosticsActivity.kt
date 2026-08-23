package com.amaury.pointage

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.security.MessageDigest

object AdminDiagnosticsGate {
    private const val PREFS = "admin_diagnostics"
    private const val KEY_ENABLED = "owner_enabled"

    fun isEnabled(context: Context): Boolean = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)
    fun enable(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, true).apply()

    fun deviceCredentialIntent(context: Context, title: String): Intent? {
        val km = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        if (!km.isDeviceSecure) return null
        return km.createConfirmDeviceCredentialIntent(title, "Accès réservé au propriétaire de HP Travail")
    }
}

class AdminDiagnosticsActivity : Activity() {
    private val requestUnlock = 7301
    private var unlocked = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!AdminDiagnosticsGate.isEnabled(this)) {
            finish()
            return
        }
        val unlock = AdminDiagnosticsGate.deviceCredentialIntent(this, "Diagnostic développeur")
        if (unlock == null) {
            Toast.makeText(this, "Configure d’abord un verrouillage d’écran Android pour protéger cette zone.", Toast.LENGTH_LONG).show()
            finish()
        } else startActivityForResult(unlock, requestUnlock)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == requestUnlock && resultCode == RESULT_OK) {
            unlocked = true
            buildUi()
        } else finish()
    }

    private fun buildUi() {
        val report = CrashRecoveryManager.getLastCrashReport(this)
        val analysis = analyze(report)
        val fingerprint = fingerprint(report)

        fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
        fun rounded(fill: Int, stroke: Int = Color.TRANSPARENT) = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(14).toFloat()
            setColor(fill)
            if (stroke != Color.TRANSPARENT) setStroke(dp(1), stroke)
        }
        fun button(label: String, enabled: Boolean = true, action: () -> Unit) = Button(this).apply {
            text = label
            isAllCaps = false
            textSize = 14f
            isEnabled = enabled
            setTextColor(Color.WHITE)
            backgroundTintList = null
            background = rounded(if (enabled) Color.rgb(26, 26, 26) else Color.rgb(115, 115, 115))
            setOnClickListener { action() }
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(28))
            setBackgroundColor(Color.rgb(246, 246, 246))
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        content.addView(TextView(this).apply {
            text = "🔐 DIAGNOSTIC DÉVELOPPEUR"
            textSize = 21f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.BLACK)
            gravity = Gravity.CENTER
        })
        content.addView(TextView(this).apply {
            text = "Zone privée — protégée par le verrouillage Android de ce téléphone"
            textSize = 13f
            setTextColor(Color.DKGRAY)
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, dp(18))
        })

        content.addView(TextView(this).apply {
            text = "ERREUR DÉTECTÉE\n$fingerprint\n\n$analysis"
            textSize = 14f
            setTextColor(Color.BLACK)
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = rounded(Color.WHITE, Color.rgb(210, 210, 210))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        content.addView(TextView(this).apply {
            text = "RAPPORT TECHNIQUE\n\n$report"
            textSize = 12f
            setTextColor(Color.rgb(25, 25, 25))
            setTextIsSelectable(true)
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = rounded(Color.rgb(232, 232, 232))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })

        content.addView(TextView(this).apply {
            text = "CORRECTION PROPOSÉE PAR CHATGPT\n\nAucune correction reçue pour ce diagnostic. Partage le rapport à ChatGPT ; la proposition pourra ensuite être vérifiée avant toute mise à jour."
            textSize = 14f
            setTextColor(Color.BLACK)
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = rounded(Color.rgb(255, 247, 220), Color.rgb(210, 170, 70))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })

        content.addView(button("PARTAGER LE DIAGNOSTIC À CHATGPT") { shareToChatGpt(report, analysis, fingerprint) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)).apply { topMargin = dp(14) })
        content.addView(button("VALIDER LA CORRECTION", false) {}, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)).apply { topMargin = dp(8) })
        content.addView(button("REFUSER / IGNORER", true) { finish() }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)).apply { topMargin = dp(8) })

        setContentView(scroll)
    }

    private fun shareToChatGpt(report: String, analysis: String, fingerprint: String) {
        val text = buildString {
            appendLine("HP Travail — diagnostic développeur privé")
            appendLine("Identifiant erreur : $fingerprint")
            appendLine()
            appendLine("Analyse locale :")
            appendLine(analysis)
            appendLine()
            appendLine("Rapport :")
            append(report)
            appendLine()
            appendLine()
            append("Analyse cette erreur dans le dépôt HP Travail, propose la correction précise et n’applique rien sans ma validation.")
        }
        val base = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "HP Travail — diagnostic $fingerprint")
            putExtra(Intent.EXTRA_TEXT, text)
        }
        val chatGpt = Intent(base).setPackage("com.openai.chatgpt")
        if (chatGpt.resolveActivity(packageManager) != null) startActivity(chatGpt)
        else startActivity(Intent.createChooser(base, "Partager à ChatGPT"))
    }

    private fun analyze(report: String): String {
        val r = report.lowercase()
        return when {
            "outofmemoryerror" in r -> "Mémoire insuffisante : réduire les bitmaps, caches ou effets graphiques avant de relancer."
            "securityexception" in r -> "Erreur d’autorisation ou de sécurité : vérifier permission, provider, URI ou accès protégé."
            "illegalargumentexception" in r && "required value was null" in r -> "Valeur obligatoire devenue nulle. Priorité : identifier le premier fichier HP Travail dans la pile, puis sécuriser la ressource ou le décodage concerné."
            "nullpointerexception" in r -> "Référence nulle. Priorité : première ligne du code HP Travail dans la pile et ajout d’une validation avant utilisation."
            "http 403" in r || "forbidden" in r -> "Accès réseau refusé par le serveur. Utiliser un mécanisme de secours et éviter de bloquer l’application."
            "true3dbutton" in r || "opengl" in r || "egl" in r -> "Erreur liée au moteur graphique 3D. Revenir automatiquement au rendu 2D sur le téléphone concerné."
            "carbon" in r -> "Erreur liée au thème Carbone ou à ses ressources. Le rendu doit rester facultatif et toujours disposer d’un fallback."
            else -> "Erreur enregistrée. Le rapport complet doit être comparé au code source avant de proposer une modification."
        }
    }

    private fun fingerprint(report: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(report.toByteArray())
        return bytes.take(6).joinToString("") { "%02X".format(it) }
    }
}
