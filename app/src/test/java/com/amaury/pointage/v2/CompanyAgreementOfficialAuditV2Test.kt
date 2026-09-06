package com.amaury.pointage.v2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompanyAgreementOfficialAuditV2Test {
    @Test
    fun `recherche ACCO reste strictement liee au SIRET et a la page`() {
        val body = CompanyAgreementOfficialAuditV2.searchBody("123 456 789 01234", 3)
        assertEquals("ACCO", body["fond"])
        val recherche = body["recherche"] as Map<*, *>
        assertEquals(3, recherche["pageNumber"])
        assertEquals(25, recherche["pageSize"])

        val filtres = recherche["filtres"] as List<*>
        val filtre = filtres.single() as Map<*, *>
        assertEquals("SIRET_RAISON_SOCIALE", filtre["facette"])
        assertEquals(listOf("12345678901234"), filtre["valeurs"])

        val champs = recherche["champs"] as List<*>
        val champ = champs.single() as Map<*, *>
        assertEquals("ALL", champ["typeChamp"])
        val criteres = champ["criteres"] as List<*>
        val critere = criteres.single() as Map<*, *>
        assertEquals("EXACTE", critere["typeRecherche"])
        assertEquals("12345678901234", critere["valeur"])
    }

    @Test
    fun `fusion conserve un accord deja verifie et ajoute seulement les nouveaux`() {
        val existing = CompanyAgreementStoreV2.Agreement(
            id = "ACCOTEXT1",
            title = "Accord validé",
            effectiveFrom = "2026-01-01",
            effectiveTo = null,
            sourceLabel = "Légifrance",
            status = CompanyAgreementStoreV2.Status.VERIFIED,
            notes = "validation locale conservée"
        )
        val refreshedSame = existing.copy(
            title = "Titre renvoyé par la nouvelle recherche",
            status = CompanyAgreementStoreV2.Status.UNKNOWN,
            notes = "ne doit pas écraser l'existant"
        )
        val newAgreement = existing.copy(
            id = "ACCOTEXT2",
            title = "Nouvel accord",
            status = CompanyAgreementStoreV2.Status.UNKNOWN
        )

        val merged = CompanyAgreementOfficialAuditV2.mergePreservingExisting(
            listOf(existing),
            listOf(refreshedSame, newAgreement)
        )

        assertEquals(2, merged.size)
        assertEquals(existing, merged.first { it.id == "ACCOTEXT1" })
        assertEquals(newAgreement, merged.first { it.id == "ACCOTEXT2" })
    }

    @Test
    fun `audit complet exige pagination stockage et aucune erreur transitoire`() {
        assertTrue(CompanyAgreementOfficialAuditV2.auditCompleted(true, true, 0, 0, true))
        assertFalse(CompanyAgreementOfficialAuditV2.auditCompleted(false, true, 0, 0, true))
        assertFalse(CompanyAgreementOfficialAuditV2.auditCompleted(true, false, 0, 0, true))
        assertFalse(CompanyAgreementOfficialAuditV2.auditCompleted(true, true, 1, 0, true))
        assertFalse(CompanyAgreementOfficialAuditV2.auditCompleted(true, true, 0, 1, true))
        assertFalse(CompanyAgreementOfficialAuditV2.auditCompleted(true, true, 0, 0, false))
    }

    @Test
    fun `compte les resultats bruts sans confondre avec les candidats ACCOTEXT`() {
        val data = mapOf("results" to listOf(mapOf("id" to "ACCOTEXT1"), mapOf("id" to "AUTRE")))
        assertEquals(2, CompanyAgreementOfficialAuditV2.rawResultCount(data))
        assertEquals(0, CompanyAgreementOfficialAuditV2.rawResultCount(emptyMap<String, Any>()))
    }
}
