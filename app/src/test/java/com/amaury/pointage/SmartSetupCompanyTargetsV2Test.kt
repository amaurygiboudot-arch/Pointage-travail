package com.amaury.pointage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartSetupCompanyTargetsV2Test {
    @Test
    fun `les cibles v2 ne sont pas limitees a deux entreprises`() {
        val stored = SalaryCompanyStore.ReadResult(
            companies = listOf(
                company("company-a", "11111111111111", "1 rue A"),
                company("company-b", "22222222222222", "2 rue B"),
                company("company-c", "33333333333333", "3 rue C")
            ),
            reliable = true
        )

        val targets = resolveSmartSetupCompanyTargets(stored)

        assertEquals(listOf("company-a", "company-b", "company-c"), targets?.map { it.companyId })
        assertEquals(listOf("1 rue A", "2 rue B", "3 rue C"), targets?.map { it.address })
    }

    @Test
    fun `un store entreprises non fiable bloque l apprentissage automatique`() {
        val stored = SalaryCompanyStore.ReadResult(
            companies = listOf(company("company-a", "11111111111111", "1 rue A")),
            reliable = false
        )

        assertNull(resolveSmartSetupCompanyTargets(stored))
    }

    @Test
    fun `une entreprise sans siret ou adresse exploitable n est pas inventee`() {
        val stored = SalaryCompanyStore.ReadResult(
            companies = listOf(
                company("company-ok", "11111111111111", "1 rue A"),
                company("company-no-siret", "", "2 rue B"),
                company("company-no-address", "33333333333333", "   ")
            ),
            reliable = true
        )

        val targets = resolveSmartSetupCompanyTargets(stored)

        assertEquals(listOf("company-ok"), targets?.map { it.companyId })
    }

    @Test
    fun `une zone candidate v2 porte le company id sans recreer de company slot`() {
        val zone = smartCandidateZoneJson(
            id = "candidate-a",
            address = "1 rue A",
            latitude = 46.7,
            longitude = -1.4,
            radius = 150,
            companyId = "company-stable-3",
            legacyCompanySlot = null
        )

        assertEquals("company-stable-3", zone.getString("companyId"))
        assertFalse(zone.has("companySlot"))
        assertTrue(zone.getBoolean("smartCandidate"))
    }

    @Test
    fun `la compatibilite legacy conserve le slot uniquement hors v2`() {
        val zone = smartCandidateZoneJson(
            id = "candidate-legacy",
            address = "1 rue A",
            latitude = 46.7,
            longitude = -1.4,
            radius = 150,
            companyId = null,
            legacyCompanySlot = 2
        )

        assertEquals(2, zone.getInt("companySlot"))
        assertFalse(zone.has("companyId"))
    }

    @Test
    fun `deux entreprises peuvent partager une adresse sans partager la meme zone GPS`() {
        val zones = org.json.JSONArray()
            .put(
                org.json.JSONObject()
                    .put("id", "zone-a")
                    .put("address", "10 rue Commune")
                    .put("companyId", "company-a")
            )

        assertTrue(hasGpsZoneForOwnerAtAddress(zones, "10 rue Commune", "company-a", null))
        assertFalse(hasGpsZoneForOwnerAtAddress(zones, "10 rue Commune", "company-b", null))
    }

    @Test
    fun `la compatibilite legacy distingue aussi les slots a une meme adresse`() {
        val zones = org.json.JSONArray()
            .put(
                org.json.JSONObject()
                    .put("id", "zone-legacy-1")
                    .put("address", "10 rue Commune")
                    .put("companySlot", 1)
            )

        assertTrue(hasGpsZoneForOwnerAtAddress(zones, "10 rue Commune", null, 1))
        assertFalse(hasGpsZoneForOwnerAtAddress(zones, "10 rue Commune", null, 2))
    }

    private fun company(id: String, siret: String, address: String) =
        SalaryCompanyStore.Company(
            id = id,
            name = "Entreprise $id",
            siret = siret,
            address = address
        )
}
