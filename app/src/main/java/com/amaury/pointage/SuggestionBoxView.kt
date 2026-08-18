package com.amaury.pointage

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.text.InputType
import android.util.AttributeSet
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
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

        addView(TextView(context).apply {
            text = "IDÉES & AMÉLIORATIONS"
            textSize = 16f
            setTextColor(Color.parseColor("#D6A84B"))
            setPadding(0, 0, 0, dp(8))
        })

        addView(TextView(context).apply {
            text = "Une idée pour améliorer HP Travail ? Écris-la ici."
            textSize = 13f
            setPadding(0, 0, 0, dp(8))
        })

        ideaInput = EditText(context).apply {
            hint = "Ex. : ce serait bien d'avoir…"
            minLines = 4
            maxLines = 8
            gravity = Gravity.TOP or Gravity.START
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setBackgroundResource(R.drawable.hp_panel)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setText(prefs.getString("draft_idea", "").orEmpty())
        }
        addView(ideaInput, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val send = Button(context).apply {
            text = "💡  PARTAGER L'IDÉE"
            isAllCaps = false
            textSize = 13f
            setBackgroundResource(R.drawable.hp_panel)
            setOnClickListener { shareIdea() }
        }
        addView(send, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)).apply {
            topMargin = dp(8)
        })

        ideaInput.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) prefs.edit().putString("draft_idea", ideaInput.text.toString()).apply()
        }
    }

    private fun shareIdea() {
        val idea = ideaInput.text.toString().trim()
        if (idea.isBlank()) {
            Toast.makeText(context, "Écris d'abord ton idée", Toast.LENGTH_SHORT).show()
            return
        }

        prefs.edit().putString("draft_idea", idea).apply()
        val version = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull().orEmpty()

        val text = buildString {
            append("💡 Idée pour HP Travail")
            if (version.isNotBlank()) append(" — version $version")
            append("\n\n")
            append(idea)
        }

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Idée d'amélioration HP Travail")
            putExtra(Intent.EXTRA_TEXT, text)
        }
        runCatching {
            context.startActivity(Intent.createChooser(intent, "Envoyer l'idée avec…").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure {
            Toast.makeText(context, "Aucune application disponible pour partager l'idée", Toast.LENGTH_LONG).show()
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
