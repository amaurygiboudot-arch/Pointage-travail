package com.amaury.pointage

import android.content.Context
import android.content.pm.PackageManager
import android.Manifest
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.amaury.pointage.v2.V2RuntimeAccountScope
import com.amaury.pointage.v2.V2RuntimeReader
import java.time.LocalDate
import java.time.ZoneId

internal object DailyDiagnosticExportV2 {
    internal fun canonicalOrigin(raw: String?): String =
        ClosureOriginV2.values().firstOrNull { it.name == raw }?.name ?: "UNKNOWN"

    fun render(context: Context, scope: String, date: LocalDate): String = V2RuntimeAccountScope.withScope {
        check(scope == V2RuntimeAccountScope.operationScope()) { "Compte modifié : rouvrir le diagnostic." }
        val zone = ZoneId.systemDefault()
        val now = System.currentTimeMillis()
        val read = DailyDiagnosticStoreV2.read(context, scope)
        val events = DailyDiagnosticModelV2.forDay(read.events, date, zone)
        val start = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val runtime = V2RuntimeReader.diagnostic(context, now)
        val allSessions = runtime.history.sessions
        val sessions = allSessions.filter {
            (it.realArrivalMs ?: it.countedEntryMs)?.let { arrival ->
                arrival < end && (it.realExitMs ?: it.countedExitMs ?: now) >= start
            } ?: ((it.realExitMs ?: it.countedExitMs)?.let { exit -> exit in start until end } == true)
        }
        val reliable = runtime.history.reliable
        check(scope == V2RuntimeAccountScope.currentScope()) { "Compte modifié : export annulé." }
        fun time(value: Long?) = value?.let { DailyDiagnosticModelV2.timestamp(it, zone) } ?: "inconnu / non enregistré"
        buildString {
            appendLine("HORATRACK — DIAGNOSTIC DE JOURNÉE v1")
            appendLine("Journée : $date ; fuseau de présentation : $zone")
            appendLine("Exporté : ${time(now)} ; Android ${Build.VERSION.SDK_INT} ; application ${BuildConfig.VERSION_NAME}")
            appendLine("Partage volontaire. Horaires personnels : vérifier ce fichier avant de le transmettre.")
            appendLine("Aucun identifiant de compte, nom de lieu, adresse, coordonnée GPS ou secret inclus.")
            appendLine()
            appendLine("COUVERTURE ET LIMITES")
            appendLine("Enregistrement actuellement actif : ${read.enabled} ; journal lisible : ${read.readable}")
            appendLine("Écriture perdue connue dans ce processus (tous comptes, attribution inconnue) : ${DailyDiagnosticStoreV2.hasKnownWriteGap()} ; écritures encore en attente tous comptes : ${DailyDiagnosticStoreV2.pendingWrites()}")
            appendLine("Conservation : 14 jours et 2 000 événements maximum (purge lors de la prochaine utilisation) ; journal tronqué : ${read.truncated}")
            appendLine("Premier événement encore conservé : ${time(read.events.firstOrNull()?.recordedAtMs)}")
            appendLine("Dernier événement encore conservé : ${time(read.events.lastOrNull()?.recordedAtMs)}")
            appendLine("La trace commence à son activation : aucune reconstruction rétroactive. Une absence d'événement ne prouve pas une absence de déplacement.")
            appendLine("Des lacunes restent possibles : processus arrêté, GPS non livré, changement de compte, écriture échouée ou file saturée. Les interruptions exactes ne sont pas déduites.")
            appendLine("Les heures GPS sont celles livrées/observées par l'application, pas une preuve de l'heure physique exacte de franchissement.")
            appendLine()
            appendLine("ÉTAT DU TÉLÉPHONE À L'EXPORT UNIQUEMENT (pas l'état historique)")
            appendLine("Localisation précise autorisée : ${ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED}")
            if (Build.VERSION.SDK_INT >= 29) appendLine("Localisation arrière-plan autorisée : ${ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED}")
            val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            appendLine("Économie d'énergie active : ${power?.isPowerSaveMode}")
            appendLine()
            appendLine("TRACE ENREGISTRÉE — observation | traitement | événement | code | valeur | zone pseudonyme")
            if (events.isEmpty()) appendLine("Aucun événement conservé pour cette date : journée non démontrable par cette trace.")
            events.forEach { appendLine("${time(it.observedAtMs)} | ${time(it.recordedAtMs)} | ${it.kind} | ${it.code} | ${it.value ?: ""} | ${it.zoneToken ?: ""}") }
            appendLine("Valeur : nombre de zones pour GEOFENCE_* ; fin attendue fournie pour ENTRY_REQUEST/EXIT_REQUEST ; heure retenue pour ENTRY_WRITE/EXIT_WRITE (millisecondes UTC). PAUSE_START/END_WRITE distingue début/fin ; SUCCESS_PAID/UNPAID indique le statut payé. Zone pseudonyme : corrélation locale, sans nom ni adresse, renouvelée après effacement du journal.")
            appendLine("REQUEST = tentative, WRITE/SUCCESS = écriture confirmée, WRITE/FAILED = écriture échouée. Une tentative sans succès ne prouve aucune modification.")
            appendLine("EXIT_REQUEST : code = origine de la demande (MANUAL, WIDGET, AUTOMATIC, GPS_CONFIRMATION ou UNKNOWN), pas preuve de fermeture. EXIT_WRITE/SUCCESS confirme l'écriture ; aucune origine n'est reconstruite pour les anciennes traces. GPS_CONFIRMATION désigne la confirmation utilisateur d'une observation GPS, pas une clôture automatique.")
            appendLine()
            appendLine("POINTAGES ACTUELLEMENT CONSERVÉS — instantané à l'export, pas trace historique")
            appendLine("Source complète et fiable : $reliable")
            appendLine("Historique ancien non attribué : ${V2RuntimeAccountScope.UNASSIGNED_WARNING in runtime.history.warnings}")
            if (V2RuntimeAccountScope.UNASSIGNED_WARNING in runtime.history.warnings) appendLine("L'intégrité de l'ancien journal non attribué n'est pas vérifiée par cette lecture du compte courant.")
            appendLine("Journal du compte courant lisible et cohérent : ${runtime.current.reliable}")
            // Reader warnings are fixed application messages, never persisted user labels or IDs.
            runtime.history.warnings.distinct().forEach { appendLine("Motif historique complet : $it") }
            runtime.current.warnings.distinct().forEach { appendLine("Motif journal du compte : $it") }
            if (!reliable) appendLine("Historique complet et calcul global restent à confirmer, même si une session courante est lisible.")
            appendLine("Sessions impossibles à rattacher à une date : ${allSessions.count { it.realArrivalMs == null && it.countedEntryMs == null && it.realExitMs == null && it.countedExitMs == null }}")
            if (sessions.isEmpty()) appendLine("Aucune session retournée ; si la source est non fiable, cela ne signifie pas qu'aucun travail n'a été effectué.")
            sessions.forEachIndexed { index, session ->
                appendLine("Session ${index + 1} : ${session.status} ; base ${session.timeBasis}")
                appendLine("Arrivée réelle ${time(session.realArrivalMs)} ; retenue ${time(session.countedEntryMs)}")
                appendLine("Sortie réelle ${time(session.realExitMs)} ; retenue ${time(session.countedExitMs)}")
                appendLine("Origine canonique : ${canonicalOrigin(session.closureOrigin)} ; observation GPS utilis\u00e9e : ${time(session.departureObservedAtMs)}")
                session.pauses.forEach { appendLine("Pause ${time(it.startMs)} → ${time(it.endMs)} ; payée ${it.paid ?: "à confirmer"} ; source ${it.source}") }
                session.travels.forEach { appendLine("Déplacement ${time(it.startMs)} → ${time(it.endMs)} ; qualification ${it.classification}") }
            }
            if (!reliable && runtime.current.reliable) {
                appendLine("SESSION COURANTE DU COMPTE — instantané séparé à l'export, toutes dates, sans validation de l'historique complet")
                val current = runtime.current.snapshot.session
                if (current == null) appendLine("Aucune session courante dans le journal du compte ; cela ne décrit pas l'ancien historique non attribué.")
                else {
                    appendLine("État ${current.status} ; base ${current.timeBasis}")
                    appendLine("Arrivée réelle ${time(current.realArrivalMs)} ; retenue ${time(current.countedEntryMs)}")
                    appendLine("Sortie réelle ${time(current.realExitMs)} ; retenue ${time(current.countedExitMs)}")
                    appendLine("Origine canonique : ${canonicalOrigin(current.closureOrigin)} ; observation GPS utilis\u00e9e : ${time(current.departureObservedAtMs)}")
                    current.pauses.forEach { appendLine("Pause ${time(it.startMs)} → ${time(it.endMs)} ; payée ${it.paid ?: "à confirmer"} ; source ${it.source}") }
                    current.travels.forEach { appendLine("Déplacement ${time(it.startMs)} → ${time(it.endMs)} ; qualification ${it.classification}") }
                }
            }
            appendLine()
            appendLine("RÉCIT À DEMANDER À L'UTILISATEUR APRÈS LECTURE")
            appendLine("1. À quelle heure es-tu réellement arrivé et as-tu commencé à travailler ?")
            appendLine("2. Quelles pauses as-tu prises (début, fin, payées ou non si connu) ?")
            appendLine("3. Quels déplacements, changements de site et retours as-tu effectués ?")
            appendLine("4. À quelle heure as-tu fini de travailler puis quitté le site ?")
            appendLine("5. As-tu répondu à une question, pointé manuellement, coupé le GPS ou redémarré le téléphone ?")
            appendLine("COMPARAISON À REMPLIR : récit utilisateur | détection téléphone | pointage retenu | écart | preuve ou hypothèse.")
            appendLine("Ne jamais considérer le GPS comme vérité absolue. Ne pas corriger l'historique sur une simple hypothèse.")
        }
    }
}
