package com.amaury.pointage.v2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MayFirstLegalAuditV2Test {
    @Test
    fun `recherche 1er mai reste bornee au Code du travail et a la date`() {
        val atMs = 1_788_134_400_000L
        val body = MayFirstLegalAuditV2.searchBody(atMs)
        assertEquals("CODE_DATE", body["fond"])
        val search = body["recherche"] as Map<*, *>
        val filters = search["filtres"] as List<*>
        assertTrue(filters.any { (it as? Map<*, *>)?.get("facette") == "NOM_CODE" })
        assertTrue(filters.any { (it as? Map<*, *>)?.get("singleDate") == atMs })
        val fields = search["champs"] as List<*>
        assertTrue(fields.toString().contains("1er mai indemnité salaire"))
    }
}
