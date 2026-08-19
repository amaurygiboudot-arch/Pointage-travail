package com.amaury.pointage

import android.content.Context
import android.graphics.Color
import android.text.InputType
import android.util.AttributeSet
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class SuggestionBoxView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    private val prefs = context.getSharedPreferences("user_feedback", Context.MODE_PRIVATE)
    private val ideaInput: EditText

    init {
        orientation = VERTICAL
        setPadding(0, dp(18), 0, dp(8))

        val guide = adaptiveButton("📖  NOTICE D'UTILISATION").apply {
            setOnClickListener { UserGuideDialog.show(context) }
        }
        addView(guide, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(14)
        })

        addView(sectionTitle("IDÉES & AMÉLIORATIONS"))

        addView(bodyText("Une idée pour améliorer HP Travail ? Écris-la ici. Seul ce texte et des informations techniques de version/appareil seront envoyés.").apply {
            setPadding(0, 0, 0, dp(8))
        })

        addView(bodyText("Les idées sont uniquement des propositions. Elles sont examinées par le propriétaire de l'application et ne peuvent jamais modifier automatiquement HP Travail.").apply {
            setTextColor(Color.parseColor("#D6A84B"))
            setPadding(0, 0, 0, dp(10))
        })

        ideaInput = EditText(context).apply {
            hint = "Ex. : ce serait bien d'avoir…"
            textSize = 14f
            minLines = 4
            maxLines = 8
            gravity = Gravity.TOP or Gravity.START
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setBackgroundResource(R.drawable.hp_panel)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setText(prefs.getString("draft_idea", "").orEmpty())
        }
        addView(ideaInput, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val send = adaptiveButton("💡  ENVOYER L'IDÉE").apply {
            setOnClickListener { sendIdea() }
        }
        addView(send, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(8)
        })

        addView(sectionTitle("RAPPORTS D'ERREUR").apply {
            setPadding(0, dp(18), 0, dp(6))
        })

        addView(bodyText("Les rapports automatiques servent à corriger les crashs. Ils n'incluent pas l'historique, les adresses, les salaires ni les données GPS enregistrées. Un rapport ne déclenche jamais de modification automatique du code.").apply {
            setPadding(0, 0, 0, dp(6))
        })

        val reportsSwitch = Switch(context).apply {
            text = "Envoyer automatiquement les rapports d'erreur anonymisés"
            textSize = 14f
            gravity = Gravity.CENTER_VERTICAL
            minHeight = dp(64)
            setPadding(0, dp(8), 0, dp(8))
            isSingleLine = false
            maxLines = 3
            isChecked = TelemetryManager.crashReportsEnabled(context)
            setOnCheckedChangeListener { _, enabled ->
                TelemetryManager.setCrashReportsEnabled(context, enabled)
                Toast.makeText(
                    context,
                    if (enabled) "Rapports d'erreur activés" else "Rapports d'erreur désactivés",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
        addView(reportsSwitch, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        ideaInput.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) prefs.edit().putString("draft_idea", ideaInput.text.toString()).apply()
        }
    }

    private fun adaptiveButton(label: String) = Button(context).apply {
        text = label
        isAllCaps = false
        textSize = 14f
        gravity = Gravity.CENTER
        isSingleLine = false
        maxLines = 2
        minHeight = dp(54)
        minimumHeight = 0
        setPadding(dp(14), dp(10), dp(14), dp(10))
        setBackgroundResource(R.drawable.hp_panel)
    }

    private fun sectionTitle(value: String) = TextView(context).apply {
        text = value
        textSize = 16f
        setTextColor(Color.parseColor("#D6A84B"))
        setPadding(0, 0, 0, dp(8))
    }

    private fun bodyText(value: String) = TextView(context).apply {
        text = value
        textSize = 14f
    }

    private fun sendIdea() {
        val idea = ideaInput.text.toString().trim()
        if (idea.isBlank()) {
            Toast.makeText(context, "Écris d'abord ton idée", Toast.LENGTH_SHORT).show()
            return
        }

        prefs.edit().putString("draft_idea", idea).apply()

        if (!TelemetryManager.isConfigured()) {
            Toast.makeText(
                context,
                "Le service d'envoi des idées n'est pas encore configuré. Ton brouillon est conservé.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val queued = runCatching { TelemetryManager.sendIdea(context, idea) }.getOrDefault(false)
        if (queued) {
            ideaInput.setText("")
            prefs.edit().remove("draft_idea").apply()
            Toast.makeText(context, "Merci pour ton idée 💡", Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(context, "Envoi impossible pour le moment. Ton brouillon est conservé.", Toast.LENGTH_LONG).show()
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
