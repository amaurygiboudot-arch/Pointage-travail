package com.amaury.pointage

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
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
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore

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

        addView(bodyText("Une idée pour améliorer HP Travail ? Écris-la ici. Le texte, la version de l'application et des informations techniques sur l'appareil seront enregistrés dans Firebase.").apply {
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

        val user = FirebaseAuth.getInstance().currentUser
        if (user == null) {
            Toast.makeText(context, "Connecte ton compte Google pour envoyer ton idée. Ton brouillon est conservé.", Toast.LENGTH_LONG).show()
            context.startActivity(Intent(context, FirebaseAccountActivity::class.java))
            return
        }

        val appContext = context.applicationContext
        val packageInfo = runCatching {
            appContext.packageManager.getPackageInfo(appContext.packageName, 0)
        }.getOrNull()
        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo?.longVersionCode ?: 0L
        } else {
            @Suppress("DEPRECATION")
            (packageInfo?.versionCode ?: 0).toLong()
        }

        val data = hashMapOf<String, Any?>(
            "text" to idea.take(4000),
            "createdAt" to FieldValue.serverTimestamp(),
            "status" to "new",
            "platform" to "android",
            "uid" to user.uid,
            "installationId" to DeviceRegistry.installId(appContext),
            "appVersionName" to (packageInfo?.versionName ?: "inconnue"),
            "appVersionCode" to versionCode,
            "androidVersion" to Build.VERSION.RELEASE.orEmpty(),
            "sdkInt" to Build.VERSION.SDK_INT,
            "manufacturer" to Build.MANUFACTURER.orEmpty(),
            "model" to Build.MODEL.orEmpty(),
            "reviewOnly" to true,
            "ownerApprovalRequired" to true
        )

        FirebaseFirestore.getInstance()
            .collection("users")
            .document(user.uid)
            .collection("feedback")
            .add(data)
            .addOnSuccessListener {
                ideaInput.setText("")
                prefs.edit().remove("draft_idea").apply()
                Toast.makeText(context, "Merci pour ton idée 💡", Toast.LENGTH_LONG).show()
            }
            .addOnFailureListener { error ->
                Toast.makeText(
                    context,
                    "Envoi impossible : ${error.localizedMessage ?: "erreur Firebase"}. Ton brouillon est conservé.",
                    Toast.LENGTH_LONG
                ).show()
            }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
