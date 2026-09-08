package com.amaury.pointage.v2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException

class OfficialApecDecisionClientV2Test {
    @Test
    fun `seul le domaine APEC HTTPS exact est accepté`() {
        assertTrue(OfficialApecDecisionClientV2.isAllowedOfficialUrl("https://commission-paritaire.apec.fr/", false))
        assertFalse(OfficialApecDecisionClientV2.isAllowedOfficialUrl("http://commission-paritaire.apec.fr/", false))
        assertFalse(OfficialApecDecisionClientV2.isAllowedOfficialUrl("https://commission-paritaire.apec.fr.evil.example/", false))
        assertFalse(OfficialApecDecisionClientV2.isAllowedOfficialUrl("https://evil.example/", false))
    }

    @Test
    fun `un PDF doit rester dans assets files`() {
        assertTrue(
            OfficialApecDecisionClientV2.isAllowedOfficialUrl(
                "https://commission-paritaire.apec.fr/assets/files/decision.pdf?download=1",
                true
            )
        )
        assertFalse(
            OfficialApecDecisionClientV2.isAllowedOfficialUrl(
                "https://commission-paritaire.apec.fr/autre/decision.pdf",
                true
            )
        )
        assertFalse(
            OfficialApecDecisionClientV2.isAllowedOfficialUrl(
                "https://commission-paritaire.apec.fr/assets/files/decision.html",
                true
            )
        )
    }

    @Test
    fun `redirection relative officielle est acceptée`() {
        val redirected = OfficialApecDecisionClientV2.resolveAllowedRedirect(
            "https://commission-paritaire.apec.fr/assets/files/old.pdf",
            "/assets/files/new.pdf",
            true
        )
        assertEquals("https://commission-paritaire.apec.fr/assets/files/new.pdf", redirected)
    }

    @Test
    fun `redirection externe est bloquée`() {
        val redirected = OfficialApecDecisionClientV2.resolveAllowedRedirect(
            "https://commission-paritaire.apec.fr/assets/files/old.pdf",
            "https://evil.example/new.pdf",
            true
        )
        assertNull(redirected)
    }

    @Test
    fun `lecture bornee accepte exactement la limite`() {
        val bytes = ByteArray(32) { it.toByte() }
        val read = OfficialApecDecisionClientV2.readBounded(ByteArrayInputStream(bytes), 32)
        assertTrue(read.contentEquals(bytes))
    }

    @Test(expected = IOException::class)
    fun `lecture bornee refuse un octet de trop`() {
        OfficialApecDecisionClientV2.readBounded(ByteArrayInputStream(ByteArray(33)), 32)
    }
}
