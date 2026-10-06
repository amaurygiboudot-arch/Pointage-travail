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
        assertEquals(snapshot, PdfPendingVault.capture(root, account, source, "Rapport.pdf"))
        assertArrayEquals(source.readBytes(), snapshot.readBytes())
    }

    @Test fun repeatedCaptureKeepsSameHashAndFirstDocumentName() = withFiles { root ->
        val account = BillingContract.accountId("user-A")
        val source = File(root, "source.pdf").apply { writeText("original PDF") }
        val snapshot = PdfPendingVault.capture(root, account, source, "Original.pdf")
        assertEquals(snapshot, PdfPendingVault.capture(root, account, source, "Renamed.pdf"))
        assertEquals(1, PdfPendingVault.documents(root, account).size)
        assertEquals("Original.pdf", PdfPendingVault.name(snapshot))
    }
    @Test fun interruptedArchiveCannotHideIntactPendingAndIsRepairedWithoutChangingIdentity() = withFiles { root ->
        val account = BillingContract.accountId("user-A")
        val source = File(root, "source.pdf").apply { writeText("complete purchased PDF bytes") }
        val pending = PdfPendingVault.capture(root, account, source, "Rapport.pdf")
        val hash = pending.nameWithoutExtension
        val archive = File(root, "billing_pdf_archive/$account/$hash.pdf").apply {
            parentFile.mkdirs(); writeText("complete purchased") // Old direct copy interrupted here.
        }
        assertEquals(listOf(pending), PdfPendingVault.availableDocuments(root, account))
        val repaired = PdfPendingVault.publish(root, account, pending, hash, "billing_pdf_archive")
        assertEquals(archive, repaired)
        assertArrayEquals(pending.readBytes(), repaired.readBytes())
        assertEquals(listOf(repaired), PdfPendingVault.availableDocuments(root, account))
    }

    @Test fun interruptedTemporaryPublicationCanBeRetriedForArchiveAndService() = withFiles { root ->
        val account = BillingContract.accountId("user-A")
        val source = File(root, "source.pdf").apply { writeText("complete immutable PDF") }
        val hash = BillingContract.documentId(source)
        listOf("billing_pdf_archive", "billing_service_pending").forEach { storage ->
            val folder = File(root, "$storage/$account").apply { mkdirs() }
            File(folder, "capture_interrupted.tmp").writeText("complete")
            assertFalse(File(folder, "$hash.pdf").exists())
            val published = PdfPendingVault.publish(root, account, source, hash, storage)
            assertArrayEquals(source.readBytes(), published.readBytes())
            // A legacy partial final file is repaired on the next service preparation too.
            published.writeText("partial")
            assertArrayEquals(source.readBytes(), PdfPendingVault.publish(root, account, source, hash, storage).readBytes())
        }
    }

    @Test fun wrongSourceCannotReplaceAValidPublishedDocument() = withFiles { root ->
        val account = BillingContract.accountId("user-A")
        val source = File(root, "source.pdf").apply { writeText("paid bytes") }
        val hash = BillingContract.documentId(source)
        val archive = PdfPendingVault.publish(root, account, source, hash, "billing_pdf_archive")
        source.writeText("different bytes")
        assertTrue(runCatching { PdfPendingVault.publish(root, account, source, hash, "billing_pdf_archive") }.isFailure)
        assertEquals(hash, BillingContract.documentId(archive))
    }

    @Test fun copyFailureNeverPublishesPartialBytesAndRetryRecovers() = withFiles { root ->
        val account = BillingContract.accountId("user-A")
        val source = File(root, "source.pdf").apply { writeText("complete purchased PDF") }
        val hash = BillingContract.documentId(source)
        listOf("billing_pdf_archive", "billing_service_pending").forEach { storage ->
            val destination = File(root, "$storage/$account/$hash.pdf")
            assertTrue(runCatching {
                PdfPendingVault.publish(root, account, source, hash, storage) { _, output ->
                    output.write("complete".toByteArray())
                    throw java.io.IOException("interrupted copy")
                }
            }.isFailure)
            assertFalse(destination.exists())
            assertTrue(destination.parentFile.listFiles().orEmpty().isEmpty())
            assertArrayEquals(source.readBytes(), PdfPendingVault.publish(root, account, source, hash, storage).readBytes())
        }
    }

    @Test fun changedBytesDuringCopyAreRejectedBeforePublication() = withFiles { root ->
        val account = BillingContract.accountId("user-A")
        val source = File(root, "source.pdf").apply { writeText("expected bytes") }
        val hash = BillingContract.documentId(source)
        assertTrue(runCatching {
            PdfPendingVault.publish(root, account, source, hash, "billing_pdf_archive") { _, output ->
                output.write("changed bytes".toByteArray())
            }
        }.isFailure)
        assertFalse(File(root, "billing_pdf_archive/$account/$hash.pdf").exists())
    }

    @Test fun publicationCannotRepairFromAnotherAccountsPrivateBytes() = withFiles { root ->
        val first = BillingContract.accountId("user-A")
        val second = BillingContract.accountId("user-B")
        val source = File(root, "source.pdf").apply { writeText("private bytes") }
        val pending = PdfPendingVault.capture(root, first, source, "Privé.pdf")
        assertTrue(runCatching {
            PdfPendingVault.publish(root, second, pending, pending.nameWithoutExtension, "billing_pdf_archive")
        }.isFailure)
        assertTrue(PdfPendingVault.availableDocuments(root, second).isEmpty())
    }

}
