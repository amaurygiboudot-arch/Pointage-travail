package com.amaury.pointage

import android.app.Activity
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
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class OwnerFeedbackActivity : Activity() {
    private lateinit var db: FirebaseFirestore
    private lateinit var list: LinearLayout
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        db = FirebaseFirestore.getInstance()
        setContentView(buildContent())
        verifyOwnerAndLoad()
    }

    private fun buildContent(): ScrollView {
        val theme = AppThemeCatalog.current(this)
        val dark = AppThemeCatalog.useDarkPalette(this)
        val background = if (dark) theme.darkBackground else theme.lightBackground
        val panel = if (dark) theme.darkPanel else theme.lightPanel
        val text = if (dark) theme.darkText else theme.lightText
        val hint = if (dark) theme.darkHint else theme.lightHint
        val accent = if (dark) theme.accentLight else theme.accent
        val pad = dp(18)

        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, dp(28))
            setBackgroundColor(background)

            addView(TextView(this@OwnerFeedbackActivity).apply {
                this.text = "📥  BOÎTE À IDÉES REÇUES"
                textSize = 22f
                gravity = Gravity.CENTER
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(accent)
                setPadding(0, dp(8), 0, dp(12))
            })

            status = TextView(this@OwnerFeedbackActivity).apply {
                this.text = "Vérification du compte propriétaire…"
                textSize = 14f
                gravity = Gravity.CENTER
                setTextColor(hint)
                setPadding(0, 0, 0, dp(14))
            }
            addView(status)

            addView(Button(this@OwnerFeedbackActivity).apply {
                this.text = "↻  ACTUALISER"
                isAllCaps = false
                setTextColor(text)
                background = GradientDrawable().apply {
                    cornerRadius = dp(14).toFloat()
                    setColor(panel)
                    setStroke(dp(1), accent)
                }
                setOnClickListener { verifyOwnerAndLoad() }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)).apply {
                bottomMargin = dp(14)
            })
        }

        return ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(background)
            addView(list, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    private fun verifyOwnerAndLoad() {
        val user = FirebaseAuth.getInstance().currentUser
        if (user == null) {
            status.text = "Connecte d’abord ton compte Google."
            return
        }
        status.text = "Vérification du compte propriétaire…"
        db.collection("users").document(user.uid).get()
            .addOnSuccessListener { profile ->
                if (profile.getBoolean("owner") == true) loadFeedback() else {
                    status.text = "Accès réservé au propriétaire de HP Travail."
                }
            }
            .addOnFailureListener { error ->
                status.text = "Impossible de vérifier l’accès : ${error.localizedMessage ?: "erreur Firebase"}"
            }
    }

    private fun loadFeedback() {
        status.text = "Chargement des idées…"
        db.collectionGroup("feedback").get()
            .addOnSuccessListener { result ->
                val docs = result.documents.sortedByDescending { createdAtMillis(it) }
                renderFeedback(docs)
            }
            .addOnFailureListener { error ->
                status.text = "Lecture impossible : ${error.localizedMessage ?: "règles Firebase à vérifier"}"
            }
    }

    private fun renderFeedback(docs: List<DocumentSnapshot>) {
        while (list.childCount > 3) list.removeViewAt(3)
        status.text = if (docs.isEmpty()) "Aucune idée reçue pour le moment." else "${docs.size} idée(s) reçue(s)"
        docs.forEach { doc -> list.addView(feedbackCard(doc)) }
    }

    private fun feedbackCard(doc: DocumentSnapshot): LinearLayout {
        val theme = AppThemeCatalog.current(this)
        val dark = AppThemeCatalog.useDarkPalette(this)
        val panel = if (dark) theme.darkPanel else theme.lightPanel
        val text = if (dark) theme.darkText else theme.lightText
        val hint = if (dark) theme.darkHint else theme.lightHint
        val accent = if (dark) theme.accentLight else theme.accent
        val idea = doc.getString("text").orEmpty().ifBlank { "(idée sans texte)" }
        val currentStatus = doc.getString("status") ?: "new"
        val device = listOfNotNull(doc.getString("manufacturer"), doc.getString("model")).joinToString(" ").trim()
        val version = doc.getString("appVersionName") ?: "?"
        val uid = doc.getString("uid").orEmpty()
        val date = createdAtMillis(doc).takeIf { it > 0L }?.let { SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.FRANCE).format(Date(it)) } ?: "date inconnue"

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(panel)
                setStroke(dp(1), accent)
            }

            addView(TextView(this@OwnerFeedbackActivity).apply {
                this.text = idea
                textSize = 16f
                setTextColor(text)
                setTypeface(typeface, Typeface.BOLD)
            })
            addView(TextView(this@OwnerFeedbackActivity).apply {
                this.text = "État : ${labelForStatus(currentStatus)}  •  $date\nVersion $version${if (device.isNotBlank()) "  •  $device" else ""}${if (uid.isNotBlank()) "\nUtilisateur : ${uid.take(10)}…" else ""}"
                textSize = 12f
                setTextColor(hint)
                setPadding(0, dp(7), 0, dp(9))
            })

            val actions = LinearLayout(this@OwnerFeedbackActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
            actions.addView(statusButton("À VOIR", "new", doc, accent), LinearLayout.LayoutParams(0, dp(46), 1f))
            actions.addView(statusButton("RETENUE", "accepted", doc, accent), LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginStart = dp(5) })
            actions.addView(statusButton("REFUSÉE", "rejected", doc, accent), LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginStart = dp(5) })
            actions.addView(statusButton("FAITE", "done", doc, accent), LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginStart = dp(5) })
            addView(actions)
        }.also {
            it.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(12)
            }
        }
    }

    private fun statusButton(label: String, value: String, doc: DocumentSnapshot, accent: Int) = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 10f
        setPadding(dp(2), 0, dp(2), 0)
        setTextColor(accent)
        setBackgroundResource(R.drawable.hp_panel)
        setOnClickListener {
            doc.reference.update("status", value)
                .addOnSuccessListener {
                    Toast.makeText(this@OwnerFeedbackActivity, "Idée classée : ${labelForStatus(value)}", Toast.LENGTH_SHORT).show()
                    loadFeedback()
                }
                .addOnFailureListener { error ->
                    Toast.makeText(this@OwnerFeedbackActivity, "Modification refusée : ${error.localizedMessage ?: "Firebase"}", Toast.LENGTH_LONG).show()
                }
        }
    }

    private fun createdAtMillis(doc: DocumentSnapshot): Long = when (val value = doc.get("createdAt")) {
        is Timestamp -> value.toDate().time
        is Date -> value.time
        else -> 0L
    }

    private fun labelForStatus(value: String) = when (value) {
        "accepted" -> "Retenue"
        "rejected" -> "Refusée"
        "done" -> "Traitée"
        else -> "Nouvelle"
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
