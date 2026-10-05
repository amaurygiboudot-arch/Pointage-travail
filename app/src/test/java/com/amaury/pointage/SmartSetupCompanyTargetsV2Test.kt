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
    fun `une adresse partagee reste distincte entre entreprises pour lapprentissage`() {
        val zones = org.json.JSONArray()
            .put(
                smartCandidateZoneJson(
                    id = "candidate-a",
                    address = "12 rue Partagée",
                    latitude = 46.7,
                    longitude = -1.4,
                    radius = 150,
                    companyId = "Company-A",
                    legacyCompanySlot = null
                )
            )

        assertTrue(
            smartSetupTargetAlreadyRepresentedV2(
                zones, " 12 RUE PARTAGÉE ", "Company-A", null
            )
        )
        assertFalse(
            smartSetupTargetAlreadyRepresentedV2(
                zones, "12 rue Partagée", "Company-B", null
            )
        )
        assertFalse(
            smartSetupTargetAlreadyRepresentedV2(
                zones, "12 rue Partagée", "company-a", null
            )
        )
    }

    @Test
    fun `les slots legacy partages ne sont pas confondus entre eux`() {
        val zones = org.json.JSONArray()
            .put(
                smartCandidateZoneJson(
                    id = "candidate-slot-1",
                    address = "Même adresse",
                    latitude = 46.7,
                    longitude = -1.4,
                    radius = 150,
                    companyId = null,
                    legacyCompanySlot = 1
                )
            )

        assertTrue(smartSetupTargetAlreadyRepresentedV2(zones, "Même adresse", null, 1))
        assertFalse(smartSetupTargetAlreadyRepresentedV2(zones, "Même adresse", null, 2))
    }

    @Test
    fun `lapprentissage intelligent respecte la limite globale de dix zones`() {
        assertTrue(canAppendSmartCandidateZone(0))
        assertTrue(canAppendSmartCandidateZone(9))
        assertFalse(canAppendSmartCandidateZone(10))
        assertFalse(canAppendSmartCandidateZone(11))
        assertFalse(canAppendSmartCandidateZone(-1))
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

    private fun company(id: String, siret: String, address: String) =
        SalaryCompanyStore.Company(
            id = id,
            name = "Entreprise $id",
            siret = siret,
            address = address
        )
}
