package com.amaury.pointage.v2

import com.amaury.pointage.v2.engine.ConventionClassificationV2
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class V2CompanyMealBasketAuditStateStoreTest {
    private fun classificationJson(coefficient: Any? = 700): JSONObject = JSONObject()
        .put("coefficient", coefficient ?: JSONObject.NULL)
        .put("level", JSONObject.NULL)
        .put("echelon", JSONObject.NULL)
        .put("position", JSONObject.NULL)
        .put("group", JSONObject.NULL)
        .put("category", JSONObject.NULL)
        .put("employment", JSONObject.NULL)

    private fun recordJson(
        companyId: String = "company-a",
        agreementId: String = "ACCOTEXT000000001",
        siret: String = "12345678901234",
        state: String = V2CompanyMealBasketAuditStateStore.State.COMPLETE.name,
        subjects: JSONArray = JSONArray().put("MEAL_NIGHT"),
        checkedAtMs: Any = 1_800_000_000_000L,
        classification: JSONObject = classificationJson(),
        professionalStatus: String = "NON_CADRE"
    ): JSONObject = JSONObject()
        .put("companyId", companyId)
        .put("agreementId", agreementId)
        .put("siret", siret)
        .put("classification", classification)
        .put("professionalStatus", professionalStatus)
        .put("state", state)
        .put("subjects", subjects)
        .put("checkedAtMs", checkedAtMs)

    @Test
    fun `cap accepte 300 et refuse 301 sans troncature`() {
        assertTrue(V2CompanyMealBasketAuditStateStore.canPersistCompleteRecordSet(300))
        assertFalse(V2CompanyMealBasketAuditStateStore.canPersistCompleteRecordSet(301))
    }

    @Test
    fun `historique vide explicite est fiable`() {
        val result = V2CompanyMealBasketAuditStateStore.decodeRecords("[]")

        assertTrue(result.reliable)
        assertTrue(result.records.isEmpty())
        assertTrue(result.warnings.isEmpty())
    }

    @Test
    fun `json vide illisible ou mauvais type ne devient pas historique vide fiable`() {
        val blank = V2CompanyMealBasketAuditStateStore.decodeRecords("   ")
        val malformed = V2CompanyMealBasketAuditStateStore.decodeRecords("not-json")
        val wrongRoot = V2CompanyMealBasketAuditStateStore.decodeRecords("{}")

        assertFalse(blank.reliable)
        assertFalse(malformed.reliable)
        assertFalse(wrongRoot.reliable)
        assertTrue(blank.records.isEmpty())
        assertTrue(malformed.records.isEmpty())
        assertTrue(wrongRoot.records.isEmpty())
    }

    @Test
    fun `une entree invalide contamine tout le stockage et aucun marqueur partiel nest exploitable`() {
        val raw = JSONArray()
            .put(recordJson())
            .put(JSONObject().put("agreementId", "ACCOTEXT000000002"))
            .toString()

        val stored = V2CompanyMealBasketAuditStateStore.decodeRecords(raw)
        val matching = V2CompanyMealBasketAuditStateStore.matchingFrom(
            stored = stored,
            companyId = "company-a",
            expectedSiret = "12345678901234",
            classification = ConventionClassificationV2(coefficient = 700),
            professionalStatus = "NON_CADRE"
        )

        assertFalse(stored.reliable)
        assertEquals(1, stored.records.size)
        assertFalse(matching.reliable)
        assertTrue(matching.records.isEmpty())
    }

    @Test
    fun `deux etats pour la meme identite rendent lhistorique ambigu`() {
        val raw = JSONArray()
            .put(recordJson(state = V2CompanyMealBasketAuditStateStore.State.UNRESOLVED.name))
            .put(recordJson(state = V2CompanyMealBasketAuditStateStore.State.COMPLETE.name, checkedAtMs = 1_800_000_000_100L))
            .toString()

        val result = V2CompanyMealBasketAuditStateStore.decodeRecords(raw)

        assertFalse(result.reliable)
        assertEquals(2, result.records.size)
    }

    @Test
    fun `etat inconnu ou statut invalide est refuse`() {
        val unknownState = JSONArray().put(recordJson(state = "UNKNOWN")).toString()
        val badStatus = JSONArray().put(recordJson(professionalStatus = "OUVRIER")).toString()

        assertFalse(V2CompanyMealBasketAuditStateStore.decodeRecords(unknownState).reliable)
        assertFalse(V2CompanyMealBasketAuditStateStore.decodeRecords(badStatus).reliable)
    }

    @Test
    fun `sujet non normalise ou duplique rend le stockage non fiable`() {
        val nonNormalized = JSONArray().put(
            recordJson(subjects = JSONArray().put("MEAL_NIGHT_1"))
        ).toString()
        val duplicate = JSONArray().put(
            recordJson(subjects = JSONArray().put("MEAL_NIGHT").put("MEAL_NIGHT"))
        ).toString()

        assertFalse(V2CompanyMealBasketAuditStateStore.decodeRecords(nonNormalized).reliable)
        assertFalse(V2CompanyMealBasketAuditStateStore.decodeRecords(duplicate).reliable)
    }

    @Test
    fun `coefficient texte ou horodatage texte nest pas converti silencieusement`() {
        val stringCoefficient = JSONArray().put(
            recordJson(classification = classificationJson("700"))
        ).toString()
        val stringTimestamp = JSONArray().put(
            recordJson(checkedAtMs = "1800000000000")
        ).toString()

        assertFalse(V2CompanyMealBasketAuditStateStore.decodeRecords(stringCoefficient).reliable)
        assertFalse(V2CompanyMealBasketAuditStateStore.decodeRecords(stringTimestamp).reliable)
    }

    @Test
    fun `matching fiable filtre exactement entreprise siret classification et statut`() {
        val raw = JSONArray()
            .put(recordJson(agreementId = "ACCOTEXT000000001"))
            .put(recordJson(agreementId = "ACCOTEXT000000002", companyId = "company-b"))
            .put(recordJson(agreementId = "ACCOTEXT000000003", siret = "99999999999999"))
            .toString()
        val stored = V2CompanyMealBasketAuditStateStore.decodeRecords(raw)
        val matching = V2CompanyMealBasketAuditStateStore.matchingFrom(
            stored = stored,
            companyId = "company-a",
            expectedSiret = "12345678901234",
            classification = ConventionClassificationV2(coefficient = 700),
            professionalStatus = "NON_CADRE"
        )

        assertTrue(stored.reliable)
        assertTrue(matching.reliable)
        assertEquals(listOf("ACCOTEXT000000001"), matching.records.map { it.agreementId })
    }

    @Test
    fun `parametres de matching invalides ne deviennent pas absence fiable de marqueur`() {
        val stored = V2CompanyMealBasketAuditStateStore.decodeRecords(
            JSONArray().put(recordJson()).toString()
        )
        val matching = V2CompanyMealBasketAuditStateStore.matchingFrom(
            stored = stored,
            companyId = "company-a",
            expectedSiret = "123",
            classification = ConventionClassificationV2(coefficient = 700),
            professionalStatus = "NON_CADRE"
        )

        assertFalse(matching.reliable)
        assertTrue(matching.records.isEmpty())
    }
}
