package com.amaury.pointage

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.amaury.pointage.v2.V2RuntimeAccountScope
import com.amaury.pointage.v2.V2RuntimeReader
import com.amaury.pointage.v2.V2RuntimeStore
import com.amaury.pointage.v2.model.DecisionStatusV2
import com.amaury.pointage.v2.model.SessionStatusV2
import com.amaury.pointage.v2.model.WorkSegmentKindV2
import com.amaury.pointage.v2.model.WorkSessionV2
import com.google.firebase.auth.FirebaseAuth
import java.text.DateFormat
import java.util.Date

/** Review the existing canonical sessions; never create a second pointage history. */
object GpsPointageReviewDialogV2 {
    fun show(activity: Activity) {
        val scope = V2RuntimeAccountScope.currentScope()
        val read = V2RuntimeReader.allSessions(activity)
        if (scope == null || scope != V2RuntimeAccountScope.currentScope() || !read.reliable) {
            AlertDialog.Builder(activity).setTitle("Pointages à vérifier")
                .setMessage(V2RuntimeReader.warningText(read.warnings))
                .setPositiveButton("Fermer", null).show()
            return
        }
        val density = activity.resources.displayMetrics.density
        val padding = (16 * density).toInt()
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }
        fun text(value: String) = TextView(activity).also {
            it.text = value
            it.textSize = 16f
            it.setPadding(0, padding / 2, 0, padding / 2)
            content.addView(it)
        }
        text("Les heures GPS enregistrent une présence observée. Confirme le travail effectif avant de l’utiliser pour la paie. Les pauses sont conservées ; les trajets sont traités séparément.")
        content.addView(Button(activity).apply {
            this.text = "Consulter les trajets entre sites"
            setOnClickListener {
                if (scope == V2RuntimeAccountScope.currentScope())
                    activity.startActivity(Intent(activity, GpsSiteJourneyActivityV2::class.java))
            }
        })
        val pending = read.sessions.filter(::needsQualification)
            .sortedByDescending { it.realArrivalMs }
        if (pending.isEmpty()) text("Aucune présence GPS fermée à qualifier. Les pointages restent consultables dans l’historique.")
        val dialog = AlertDialog.Builder(activity).setTitle("Qualifier les pointages")
            .setView(ScrollView(activity).apply { addView(content) })
            .setPositiveButton("Fermer", null).create()
        val formatter = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
        var shown = 0
        fun appendPage() {
            pending.drop(shown).take(40).forEach { session ->
                val arrival = session.realArrivalMs?.let { formatter.format(Date(it)) } ?: "À vérifier"
                val departure = session.realExitMs?.let { formatter.format(Date(it)) } ?: "À vérifier"
                text("${session.placeLabel?.takeIf { it.isNotBlank() } ?: "Présence observée"}\n$arrival → $departure\nTemps payé à qualifier")
                content.addView(Button(activity).apply {
                    this.text = "Qualifier cet intervalle"
                    setOnClickListener {
                        val confirmation = AlertDialog.Builder(activity).setTitle("Quelle activité sur cet intervalle ?")
                            .setMessage("$arrival → $departure\n\nConfirme uniquement la présence sur ce site. Les pauses déjà saisies et les horaires resteront inchangés. Cette décision ne qualifie aucun trajet.")
                            .setPositiveButton("Travail effectif") { _, _ ->
                                qualify(activity, scope, session, true) { dialog.dismiss(); show(activity) }
                            }
                            .setNeutralButton("Hors travail") { _, _ ->
                                qualify(activity, scope, session, false) { dialog.dismiss(); show(activity) }
                            }
                            .setNegativeButton("À vérifier", null).show()
                        protect(confirmation, scope)
                    }
                })
            }
            shown = (shown + 40).coerceAtMost(pending.size)
            if (shown < pending.size) content.addView(Button(activity).apply {
                this.text = "Afficher les pointages suivants"
                setOnClickListener { content.removeView(this); appendPage() }
            })
        }
        appendPage()
        dialog.show()
        protect(dialog, scope) { content.removeAllViews() }
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun protect(dialog: AlertDialog, scope: String, clear: () -> Unit = {}) {
        val auth = runCatching { FirebaseAuth.getInstance() }.getOrNull()
        if (auth == null) { clear(); dialog.dismiss(); return }
        val listener = FirebaseAuth.AuthStateListener {
            if (scope != V2RuntimeAccountScope.currentScope()) { clear(); dialog.dismiss() }
        }
        dialog.setOnDismissListener { auth.removeAuthStateListener(listener) }
        auth.addAuthStateListener(listener)
    }

    internal fun needsQualification(session: WorkSessionV2): Boolean =
        session.status == SessionStatusV2.CLOSED && session.workSegments.singleOrNull()?.let {
            it.kind == WorkSegmentKindV2.TO_CONFIRM && it.status == DecisionStatusV2.TO_CONFIRM &&
                it.startMs == session.realArrivalMs && it.endMs == session.realExitMs
        } == true

    private fun qualify(activity: Activity, scope: String, session: WorkSessionV2, worked: Boolean, refresh: () -> Unit) {
        val saved = V2RuntimeStore.qualifyObservedPresence(activity, scope, session, worked)
        Toast.makeText(activity, if (saved) "Qualification enregistrée" else
            "Enregistrement refusé : le compte ou le pointage a changé, ou le stockage est indisponible. Vérifie à nouveau.", Toast.LENGTH_LONG).show()
        refresh()
    }
}
