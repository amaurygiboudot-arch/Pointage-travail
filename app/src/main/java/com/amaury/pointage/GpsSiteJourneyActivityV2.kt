package com.amaury.pointage

import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.app.Activity
import com.amaury.pointage.v2.V2RuntimeAccountScope
import com.google.firebase.auth.FirebaseAuth
import java.text.DateFormat
import java.util.Date

/** Read-only local evidence. No Maps transmission, location request or payroll write. */
class GpsSiteJourneyActivityV2 : Activity() {
    private var displayedScope: String? = null
    private var auth: FirebaseAuth? = null
    private val accountListener = FirebaseAuth.AuthStateListener {
        if (displayedScope != V2RuntimeAccountScope.currentScope()) {
            setContentView(TextView(this).apply { text = "Compte modifié — ferme puis rouvre les trajets." })
            finish()
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState) }
    override fun onStart() {
        super.onStart()
        displayedScope = V2RuntimeAccountScope.currentScope()
        auth = runCatching { FirebaseAuth.getInstance() }.getOrNull()
        auth?.addAuthStateListener(accountListener)
    }
    override fun onStop() {
        auth?.removeAuthStateListener(accountListener)
        auth = null
        super.onStop()
    }
    override fun onResume() { super.onResume(); render() }

    private fun render() {
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (16 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
        }
        setContentView(ScrollView(this).apply { addView(body) })
        fun text(value: String, size: Float = 16f) {
            body.addView(TextView(this).apply {
                text = value; textSize = size
                setPadding(0, 12, 0, 12)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        body.addView(Button(this).apply { text = "Retour"; setOnClickListener { finish() } })
        text("Arrivées, départs et trajets", 22f)
        text("Les passages au domicile sont exclus des trajets. Choisis le rôle « Domicile » pour ce lieu dans les zones GPS. Les durées ci-dessous sont des intervalles observés entre sites, à confirmer avant tout usage pour la paie.", 14f)
        text("Ce journal est conservé sur cet appareil. Il n'est pas encore inclus dans la sauvegarde cloud.", 13f)
        GpsSiteJourneyStoreV2.warning(this)?.let { text(it) }
        val events = GpsSiteJourneyStoreV2.read(this)
        if (displayedScope == null || displayedScope != V2RuntimeAccountScope.currentScope()) {
            body.removeAllViews()
            text("Compte de pointage indisponible ou modifié.")
            return
        }
        if (events == null) {
            text("Journal indisponible ou écriture non confirmée. Aucun trajet n'est présenté comme fiable.")
            return
        }
        val journeys = GpsSiteJourneyJournalV2.journeys(events) ?: return
        val format = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM)
        fun at(ms: Long) = format.format(Date(ms))
        text("Intervalles entre sites", 19f)
        if (journeys.isEmpty()) text("Aucun intervalle entre deux sites distincts n'est encore établi.")
        journeys.takeLast(100).asReversed().forEach { journey ->
            val seconds = journey.durationMs / 1000
            val duration = "${seconds / 3600} h ${(seconds % 3600) / 60} min ${seconds % 60} s"
            text("${journey.departure.label} → ${journey.arrival.label}\n${at(journey.departure.atMs)} → ${at(journey.arrival.atMs)}\nDurée observée : $duration\n${journey.status} · Kilomètres inconnus\n${journey.reason}")
        }
        text("Passages GPS enregistrés", 19f)
        text("Heure de réception GPS : elle peut différer du franchissement réel. Les passages à des sites qui se chevauchent restent visibles même sans trajet calculable.", 14f)
        if (events.isEmpty()) text("Aucun passage GPS enregistré dans ce journal.")
        events.takeLast(100).asReversed().forEach { event ->
            val action = when (event.kind) {
                GpsSiteObservationKindV2.WORK_ENTER -> "ENTRÉE · Travail"
                GpsSiteObservationKindV2.WORK_EXIT -> "SORTIE · Travail"
                GpsSiteObservationKindV2.HOME_ENTER -> "Arrivée domicile · trajet exclu"
                GpsSiteObservationKindV2.HOME_EXIT -> "Départ domicile · trajet exclu"
                GpsSiteObservationKindV2.INTERRUPTION -> "Surveillance interrompue · continuité inconnue"
            }
            text("${at(event.atMs)}\n$action${if (event.label.isBlank()) "" else "\n${event.label}"}")
        }
        if (events.size > 100 || journeys.size > 100) text("Affichage des 100 derniers éléments de chaque liste. Les observations antérieures restent conservées localement.", 13f)
    }
}
