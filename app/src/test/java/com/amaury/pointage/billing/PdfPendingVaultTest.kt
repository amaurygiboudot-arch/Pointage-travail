package com.amaury.pointage.billing

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class PdfPendingVaultTest {
    private fun withFiles(test: (File) -> Unit) {
        val root = Files.createTempDirectory("pdf-pending-test").toFile()
        try { test(root) } finally { root.deleteRecursively() }
    }

    @Test fun originalEditAndDeletionCannotChangePendingPurchaseBytes() = withFiles { root ->
        val account = BillingContract.accountId("user-A")
        val source = File(root, "cache/report.pdf").apply { parentFile.mkdirs(); writeText("exact original PDF bytes") }
        val expected = source.readBytes()
        val snapshot = PdfPendingVault.capture(root, account, source, "Rapport octobre.pdf")
        source.writeText("changed pointages and a new document")
        source.delete()
        assertArrayEquals(expected, snapshot.readBytes())
        assertEquals(snapshot, PdfPendingVault.documents(root, account).single())
        assertEquals("Rapport octobre.pdf", PdfPendingVault.name(snapshot))
        assertEquals(snapshot.nameWithoutExtension, BillingContract.documentId(snapshot))
    }

    @Test fun anotherAccountCannotDiscoverOrRecapturePendingBytes() = withFiles { root ->
        val first = BillingContract.accountId("user-A")
        val second = BillingContract.accountId("user-B")
        val source = File(root, "source.pdf").apply { writeText("private PDF") }
        val snapshot = PdfPendingVault.capture(root, first, source, "Privé.pdf")
        assertTrue(PdfPendingVault.documents(root, second).isEmpty())
        assertTrue(runCatching { PdfPendingVault.capture(root, second, snapshot, "Volé.pdf") }.isFailure)
    }

    @Test fun corruptedSnapshotIsRejectedInsteadOfChargingForItsChangedHash() = withFiles { root ->
        val account = BillingContract.accountId("user-A")
        val source = File(root, "source.pdf").apply { writeText("original PDF") }
        val snapshot = PdfPendingVault.capture(root, account, source, "Rapport.pdf")
        snapshot.writeText("corrupt bytes")
        assertTrue(PdfPendingVault.documents(root, account).isEmpty())
        assertTrue(runCatching { PdfPendingVault.validate(root, account, snapshot) }.isFailure)
        assertTrue(runCatching { PdfPendingVault.capture(root, account, snapshot, "Rapport.pdf") }.isFailure)
        assertTrue(runCatching { PdfPendingVault.capture(root, account, source, "Rapport.pdf") }.isFailure)
    }

    @Test fun repeatedCaptureKeepsSameHashAndFirstDocumentName() = withFiles { root ->
        val account = BillingContract.accountId("user-A")
        val source = File(root, "source.pdf").apply { writeText("original PDF") }
        val snapshot = PdfPendingVault.capture(root, account, source, "Original.pdf")
        assertEquals(snapshot, PdfPendingVault.capture(root, account, source, "Renamed.pdf"))
        assertEquals(1, PdfPendingVault.documents(root, account).size)
        assertEquals("Original.pdf", PdfPendingVault.name(snapshot))
    }
}
