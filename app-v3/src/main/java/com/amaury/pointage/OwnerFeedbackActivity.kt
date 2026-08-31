package com.amaury.pointage

import android.app.Activity
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
    private lateinit var tabNew: Button
    private lateinit var tabDone: Button
    private lateinit var tabRejected: Button
    private var currentTab = "active"
    private var allDocs: List<DocumentSnapshot> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        db = FirebaseFirestore.getInstance()
        setContentView(buildContent())
        verifyOwnerAndLoad()
    }

    private fun buildContent(): ScrollView {
        val theme = AppThemeCatalog.current(this)
        val dark = AppThemeCatalog.useDarkPalette(this)
        val backgroundColor = if (dark) theme.darkBackground else theme.lightBackground
        val panelColor = if (dark) theme.darkPanel else theme.lightPanel
        val textColor = if (dark) theme.darkText else theme.lightText
        val hintColor = if (dark) theme.darkHint else theme.lightHint
        val accentColor = if (dark) theme.accentLight else theme.accent
        val pad = dp(18)

        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, dp(28))
            setBackgroundColor(backgroundColor)

            addView(TextView(this@OwnerFeedbackActivity).apply {
                text = "📥  BOÎTE À IDÉES"
                textSize = 22f
                gravity = Gravity.CENTER
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(accentColor)
                setPadding(0, dp(8), 0, dp(12))
            })

            status = TextView(this@OwnerFeedbackActivity).apply {
                text = "Vérification du compte propriétaire…"
                textSize = 14f
                gravity = Gravity.CENTER
                setTextColor(hintColor)
                setPadding(0, 0, 0, dp(12))
            }
            addView(status)

            val tabs = LinearLayout(this@OwnerFeedbackActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
            tabNew = tabButton("REÇUES", "active", textColor, accentColor, panelColor)
            tabDone = tabButton("FAITES", "done", textColor, accentColor, panelColor)
            tabRejected = tabButton("REFUSÉES", "rejected", textColor, accentColor, panelColor)
            tabs.addView(tabNew, LinearLayout.LayoutParams(0, dp(48), 1f))
            tabs.addView(tabDone, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(5) })
            tabs.addView(tabRejected, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(5) })
            addView(tabs, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(10)
            })

            addView(Button(this@OwnerFeedbackActivity).apply {
                text = "↻  ACTUALISER"
                isAllCaps = false
                setTextColor(textColor)
                background = GradientDrawable().apply {
                    cornerRadius = dp(14).toFloat()
                    setColor(panelColor)
                    setStroke(dp(1), accentColor)
                }
                setOnClickListener { verifyOwnerAndLoad() }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)).apply {
                bottomMargin = dp(14)
            })
        }

        return ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(backgroundColor)
            addView(list, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    private fun tabButton(label: String, tab: String, textColor: Int, accentColor: Int, panelColor: Int) = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 11f
        setPadding(dp(2), 0, dp(2), 0)
        setTextColor(if (currentTab == tab) accentColor else textColor)
        background = GradientDrawable().apply {
            cornerRadius = dp(13).toFloat()
            setColor(panelColor)
            setStroke(dp(if (currentTab == tab) 2 else 1), accentColor)
        }
        setOnClickListener {
            currentTab = tab
            updateTabs()
            renderCurrentTab()
        }
    }

    private fun updateTabs() {
        val theme = AppThemeCatalog.current(this)
        val dark = AppThemeCatalog.useDarkPalette(this)
        val textColor = if (dark) theme.darkText else theme.lightText
        val accentColor = if (dark) theme.accentLight else theme.accent
        val panelColor = if (dark) theme.darkPanel else theme.lightPanel
        listOf(tabNew to "active", tabDone to "done", tabRejected to "rejected").forEach { (button, tab) ->
            val selected = currentTab == tab
            button.setTextColor(if (selected) accentColor else textColor)
            button.background = GradientDrawable().apply {
                cornerRadius = dp(13).toFloat()
                setColor(panelColor)
                setStroke(dp(if (selected) 2 else 1), accentColor)
            }
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
                if (profile.getBoolean("owner") == true) loadFeedback()
                else status.text = "Accès réservé au propriétaire de HP Travail."
            }
            .addOnFailureListener { error ->
                status.text = "Impossible de vérifier l’accès : ${error.localizedMessage ?: "erreur Firebase"}"
            }
    }

    private fun loadFeedback() {
        status.text = "Chargement des idées…"
        db.collectionGroup("feedback").get()
            .addOnSuccessListener { result ->
                allDocs = result.documents.sortedByDescending { createdAtMillis(it) }
                renderCurrentTab()
            }
            .addOnFailureListener { error ->
                status.text = "Lecture impossible : ${error.localizedMessage ?: "règles Firebase à vérifier"}"
            }
    }

    private fun renderCurrentTab() {
        while (list.childCount > 4) list.removeViewAt(4)
        val docs = allDocs.filter { doc ->
            when (currentTab) {
                "done" -> doc.getString("status") == "done"
                "rejected" -> doc.getString("status") == "rejected"
                else -> doc.getString("status") !in setOf("done", "rejected")
            }
        }
        status.text = when (currentTab) {
            "done" -> if (docs.isEmpty()) "Aucune idée faite." else "${docs.size} idée(s) faite(s)"
            "rejected" -> if (docs.isEmpty()) "Aucune idée refusée." else "${docs.size} idée(s) refusée(s)"
            else -> if (docs.isEmpty()) "Aucune idée en attente." else "${docs.size} idée(s) reçue(s)"
        }
        docs.forEach { doc -> list.addView(feedbackCard(doc)) }
    }

    private fun feedbackCard(doc: DocumentSnapshot): LinearLayout {
        val theme = AppThemeCatalog.current(this)
        val dark = AppThemeCatalog.useDarkPalette(this)
        val panelColor = if (dark) theme.darkPanel else theme.lightPanel
        val textColor = if (dark) theme.darkText else theme.lightText
        val hintColor = if (dark) theme.darkHint else theme.lightHint
        val accentColor = if (dark) theme.accentLight else theme.accent
        val idea = doc.getString("text").orEmpty().ifBlank { "(idée sans texte)" }
        val currentStatus = doc.getString("status") ?: "new"
        val device = listOfNotNull(doc.getString("manufacturer"), doc.getString("model")).joinToString(" ").trim()
        val version = doc.getString("appVersionName") ?: "?"
        val uid = doc.getString("uid").orEmpty()
        val date = createdAtMillis(doc).takeIf { it > 0L }
            ?.let { SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.FRANCE).format(Date(it)) }
            ?: "date inconnue"

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(panelColor)
                setStroke(dp(1), accentColor)
            }
            addView(TextView(this@OwnerFeedbackActivity).apply {
                text = idea
                textSize = 16f
                setTextColor(textColor)
                setTypeface(typeface, Typeface.BOLD)
            })
            addView(TextView(this@OwnerFeedbackActivity).apply {
                text = "État : ${labelForStatus(currentStatus)}  •  $date\nVersion $version${if (device.isNotBlank()) "  •  $device" else ""}${if (uid.isNotBlank()) "\nUtilisateur : ${uid.take(10)}…" else ""}"
                textSize = 12f
                setTextColor(hintColor)
                setPadding(0, dp(7), 0, dp(9))
            })

            val actions = LinearLayout(this@OwnerFeedbackActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
            if (currentTab == "active") {
                actions.addView(statusButton("À VOIR", "new", doc, accentColor), LinearLayout.LayoutParams(0, dp(46), 1f))
                actions.addView(statusButton("RETENUE", "accepted", doc, accentColor), LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginStart = dp(5) })
                actions.addView(statusButton("REFUSER", "rejected", doc, accentColor), LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginStart = dp(5) })
                actions.addView(statusButton("FAITE", "done", doc, accentColor), LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginStart = dp(5) })
            } else {
                actions.addView(statusButton("RESTAURER", "new", doc, accentColor), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)))
            }
            addView(actions)
        }.also {
            it.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12) }
        }
    }

    private fun statusButton(label: String, value: String, doc: DocumentSnapshot, accentColor: Int) = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 10f
        setPadding(dp(2), 0, dp(2), 0)
        setTextColor(accentColor)
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
        "done" -> "Faite"
        else -> "Nouvelle"
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
