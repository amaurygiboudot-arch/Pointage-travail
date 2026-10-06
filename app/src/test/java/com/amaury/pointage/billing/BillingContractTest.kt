package com.amaury.pointage.billing

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class BillingContractTest {
    @Test fun serverAuthorizationMustMatchExactDocumentAndBooleanGrant() {
        val document = "a".repeat(64)
        assertTrue(BillingContract.authorizedPdf(mapOf("authorized" to true, "documentSha256" to document), document))
        assertFalse(BillingContract.authorizedPdf(mapOf("authorized" to true, "documentSha256" to "b".repeat(64)), document))
        assertFalse(BillingContract.authorizedPdf(mapOf("authorized" to "true", "documentSha256" to document), document))
        assertFalse(BillingContract.authorizedPdf(mapOf("owner" to true, "premium" to true), document))
        assertFalse(BillingContract.authorizedPdf(null, document))
        assertFalse(BillingContract.authorizedPdf(mapOf("authorized" to true, "documentSha256" to "invalid"), "invalid"))
    }

    @Test fun identicalPurchasedBytesReuseIdentityButChangedDocumentDoesNot() {
        val first = File.createTempFile("horatrack-pdf", ".pdf")
        val copy = File.createTempFile("horatrack-pdf-copy", ".pdf")
        try {
            first.writeText("%PDF-1.4\nPaid document")
            first.copyTo(copy, overwrite = true)
            val original = BillingContract.documentId(first)
            assertEquals(original, BillingContract.documentId(copy))
            first.appendText("\nModified hours")
            assertNotEquals(original, BillingContract.documentId(first))
        } finally { first.delete(); copy.delete() }
    }

    @Test fun playAccountBindingIsStableSha256AndIsolatesUsers() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", BillingContract.accountId("abc"))
        assertNotEquals(BillingContract.accountId("user-a"), BillingContract.accountId("user-b"))
    }
}
