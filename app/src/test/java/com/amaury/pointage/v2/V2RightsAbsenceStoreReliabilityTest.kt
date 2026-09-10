package com.amaury.pointage.v2

import com.amaury.pointage.v2.engine.AbsencePayrollImpactV2
import com.amaury.pointage.v2.model.AbsenceProvidentTreatmentV2
import com.amaury.pointage.v2.model.AbsenceSalaryTreatmentV2
import com.amaury.pointage.v2.model.AbsenceSubrogationV2
import com.amaury.pointage.v2.model.DecisionStatusV2
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class V2RightsAbsenceStoreReliabilityTest {
    private val zone = ZoneId.of("UTC")
    private val start = LocalDate.of(2026, 9, 10).atStartOfDay(zone).toInstant().toEpochMilli()
    private val end = LocalDate.of(2026, 9, 11).atStartOfDay(zone).toInstant().toEpochMilli()

    private fun valid(
        id:String="absence-1",
        status:DecisionStatusV2=DecisionStatusV2.CONFIRMED,
        treatment:AbsenceSalaryTreatmentV2=AbsenceSalaryTreatmentV2.UNPAID,
        provident:AbsenceProvidentTreatmentV2=AbsenceProvidentTreatmentV2.TO_CONFIRM
    )=JSONObject()
        .put("id",id)
        .put("employerId","company-1")
        .put("type",AbsencePayrollImpactV2.TYPE_UNPAID)
        .put("startMs",start)
        .put("endMs",end)
        .put("salaryTreatment",treatment.name)
        .put("fullDay",true)
        .put("status",status.name)
        .put("subrogation",AbsenceSubrogationV2.TO_CONFIRM.name)
        .put("providentTreatment",provident.name)
        .put("employerProvidentOverlapNetAmount",JSONObject.NULL)
        .put("providentRelayTargetGross60Amount",JSONObject.NULL)
        .put("providentRelaySocialSecurityGrossAmount",JSONObject.NULL)
        .put("providentRelayObservedGrossAmount",JSONObject.NULL)

    @Test
    fun `historique absence vide explicite est fiable`() {
        val result=V2RightsStore.decodeAbsences("[]")
        assertTrue(result.reliable)
        assertTrue(result.absences.isEmpty())
        assertTrue(result.warnings.isEmpty())
    }

    @Test
    fun `json absence vide ou illisible nest jamais pris pour aucune absence`() {
        val blank=V2RightsStore.decodeAbsences("   ")
        val malformed=V2RightsStore.decodeAbsences("not-json")
        assertFalse(blank.reliable)
        assertFalse(malformed.reliable)
        assertTrue(blank.absences.isEmpty())
        assertTrue(malformed.absences.isEmpty())
    }

    @Test
    fun `une entree invalide contamine tout le stockage`() {
        val raw=JSONArray().put(valid()).put(JSONObject()).toString()
        val result=V2RightsStore.decodeAbsences(raw)
        assertFalse(result.reliable)
        assertEquals(1,result.absences.size)
    }

    @Test
    fun `statut manquant nest jamais invente comme confirme`() {
        val raw=valid().apply{remove("status")}
        val result=V2RightsStore.decodeAbsences(JSONArray().put(raw).toString())
        assertFalse(result.reliable)
        assertTrue(result.absences.isEmpty())
    }

    @Test
    fun `doublon identifiant absence rend le stockage ambigu`() {
        val raw=JSONArray().put(valid()).put(valid()).toString()
        val result=V2RightsStore.decodeAbsences(raw)
        assertFalse(result.reliable)
        assertEquals(2,result.absences.size)
    }

    @Test
    fun `controle relais partiel rend le stockage non fiable`() {
        val raw=valid().apply{put("providentRelayTargetGross60Amount",120.0)}
        val result=V2RightsStore.decodeAbsences(JSONArray().put(raw).toString())
        assertFalse(result.reliable)
        assertTrue(result.absences.isEmpty())
    }

    @Test
    fun `montant prevoyance confirme doit etre present et coherent`() {
        val missing=valid(provident=AbsenceProvidentTreatmentV2.NET_AMOUNT_CONFIRMED)
        val result=V2RightsStore.decodeAbsences(JSONArray().put(missing).toString())
        assertFalse(result.reliable)
        assertTrue(result.absences.isEmpty())
    }

    @Test
    fun `source corrompue bloque le moteur de paie au lieu de produire zero absence fiable`() {
        val stored=V2RightsStore.decodeAbsences("not-json")
        val absences=V2RightsStore.asAbsenceList(stored)
        val impact=AbsencePayrollImpactV2.forMonth(
            absences=absences,
            referenceDate=LocalDate.of(2026,9,30),
            acceptedEmployerIds=setOf("company-1"),
            zoneId=zone
        )
        assertTrue(impact.requiresPayrollReview)
        assertNull(impact.unpaidFullCalendarDays)
        assertTrue(impact.warnings.any{it.contains("stockage",ignoreCase=true)})
    }

    @Test
    fun `source vide fiable reste une vraie absence de donnees`() {
        val absences=V2RightsStore.asAbsenceList(V2RightsStore.decodeAbsences("[]"))
        val impact=AbsencePayrollImpactV2.forMonth(
            absences=absences,
            referenceDate=LocalDate.of(2026,9,30),
            acceptedEmployerIds=setOf("company-1"),
            zoneId=zone
        )
        assertFalse(impact.requiresPayrollReview)
        assertEquals(0,impact.unpaidFullCalendarDays)
        assertTrue(impact.warnings.isEmpty())
    }

    @Test
    fun `absence valide continue detre traitee normalement`() {
        val decoded=V2RightsStore.decodeAbsences(JSONArray().put(valid()).toString())
        val impact=AbsencePayrollImpactV2.forMonth(
            absences=V2RightsStore.asAbsenceList(decoded),
            referenceDate=LocalDate.of(2026,9,30),
            acceptedEmployerIds=setOf("company-1"),
            zoneId=zone
        )
        assertTrue(decoded.reliable)
        assertTrue(impact.requiresPayrollReview)
        assertTrue(impact.hasUnpaidAbsence)
        assertEquals(1,impact.unpaidFullCalendarDays)
    }
}
