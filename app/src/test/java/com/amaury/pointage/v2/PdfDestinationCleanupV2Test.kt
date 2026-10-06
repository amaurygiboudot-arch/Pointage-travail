package com.amaury.pointage.v2

import android.app.Application
import android.content.Context
import android.content.ContentResolver
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import com.amaury.pointage.billing.BillingContract
import com.amaury.pointage.DriveBackupManager
import com.amaury.pointage.V2MonthlyPdfActivity
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class,
    shadows = [DestinationDocumentsShadow::class, DestinationResolverShadow::class, PdfIntegrationAuthShadow::class, PdfSaveGateShadow::class],
    instrumentedPackages = ["com.google.firebase.auth", "com.amaury.pointage.billing"])
class PdfDestinationCleanupV2Test {
    private val parent = Uri.parse("content://pdf-test/tree/root/document/root")
    @Before fun reset() {
        DestinationDocumentsShadow.reset()
        PdfSaveGateShadow.allow = true
        PdfSaveGateShadow.unavailable = false
        PdfSaveGateShadow.calls.set(0)
        PdfSaveGateShadow.interactiveCalls.set(0)
        PdfSaveGateShadow.started = null
        PdfSaveGateShadow.release = null
        PdfSaveGateShadow.completed = null
    }
    private fun source() = File.createTempFile("pdf-test", ".pdf", RuntimeEnvironment.getApplication().cacheDir).apply { writeText("exact purchased bytes") }

    @Test fun nullDestinationStreamDeletesNewDocumentAndThrows() {
        DestinationDocumentsShadow.nullOutput = true
        assertThrows(Exception::class.java) { DriveBackupManager.publishPreparedPdf(RuntimeEnvironment.getApplication(), parent, "report.pdf", source()) { true } }
        assertTrue(DestinationDocumentsShadow.files.isEmpty())
        assertEquals(1, DestinationDocumentsShadow.deleted.size)
    }
    @Test fun interruptedCopyPreservesOldReportAndDeletesOnlyNewVersion() {
        DestinationDocumentsShadow.files["report.pdf"] = "previous good report".toByteArray()
        DestinationDocumentsShadow.failOutput = true
        assertThrows(Exception::class.java) { DriveBackupManager.publishPreparedPdf(RuntimeEnvironment.getApplication(), parent, "report.pdf", source()) { true } }
        assertEquals(setOf("report.pdf"), DestinationDocumentsShadow.files.keys)
        assertEquals("previous good report", String(DestinationDocumentsShadow.files.getValue("report.pdf")))
        assertEquals(1, DestinationDocumentsShadow.deleted.size)
    }
    @Test fun sameBytesAreIdempotentAndDifferentBytesPreserveOriginal() {
        val file = source()
        DriveBackupManager.publishPreparedPdf(RuntimeEnvironment.getApplication(), parent, "report.pdf", file) { true }
        DriveBackupManager.publishPreparedPdf(RuntimeEnvironment.getApplication(), parent, "report.pdf", file) { true }
        assertEquals(1, DestinationDocumentsShadow.files.size)
        file.writeText("updated bytes")
        DriveBackupManager.publishPreparedPdf(RuntimeEnvironment.getApplication(), parent, "report.pdf", file) { true }
        assertEquals(2, DestinationDocumentsShadow.files.size)
        assertEquals("exact purchased bytes", String(DestinationDocumentsShadow.files.getValue("report.pdf")))
    }
    @Test fun accountChangeDuringWriteDeletesNewDocument() {
        var valid = true
        DestinationDocumentsShadow.onWrite = { valid = false }
        assertThrows(Exception::class.java) { DriveBackupManager.publishPreparedPdf(RuntimeEnvironment.getApplication(), parent, "report.pdf", source()) { valid } }
        assertTrue(DestinationDocumentsShadow.files.isEmpty())
    }
    @Test fun actualMonthlyCopyDeletesDestinationWhenAccountChangesOnLastWrite() {
        monthlyLastWriteFailure { _, auth -> Mockito.`when`(auth.currentUser).thenReturn(null) }
    }
    @Test fun actualMonthlyCopyDeletesDestinationWhenSourceChangesOnLastWrite() {
        monthlyLastWriteFailure { file, _ -> file.writeBytes(ByteArray(file.length().toInt()) { 120 }) }
    }
    private fun monthlyLastWriteFailure(change: (File, FirebaseAuth) -> Unit) {
        val uid = "monthly-copy-owner"
        val user = Mockito.mock(FirebaseUser::class.java)
        Mockito.`when`(user.uid).thenReturn(uid)
        val auth = Mockito.mock(FirebaseAuth::class.java)
        Mockito.`when`(auth.currentUser).thenReturn(user)
        PdfIntegrationAuthShadow.auth = auth
        val app = RuntimeEnvironment.getApplication()
        val folder = File(app.filesDir, "monthly_pdf_pending/${BillingContract.accountId(uid)}").apply { mkdirs() }
        val file = File(folder, "last-write.pdf").apply { writeText("original exact bytes") }
        DestinationDocumentsShadow.files["partial.pdf"] = byteArrayOf()
        DestinationDocumentsShadow.onWrite = { change(file, auth) }
        val state = Bundle().apply {
            putString("owner_uid", uid); putString("phase", "COPYING")
            putString("file", file.absolutePath); putString("hash", BillingContract.documentId(file))
            putString("destination", DestinationDocumentsShadow.uri("partial.pdf").toString())
        }
        val controller = Robolectric.buildActivity(V2MonthlyPdfActivity::class.java).create(state)
        try {
            assertTrue("Final verification must remove the last-write failure", DestinationDocumentsShadow.deletedLatch.await(3, TimeUnit.SECONDS))
            assertEquals("The actual copy passed its read-only authorization", 1, PdfSaveGateShadow.calls.get())
            assertFalse(DestinationDocumentsShadow.files.containsKey("partial.pdf"))
        } finally { controller.destroy() }
    }
    @Test fun actualMonthlyRecreationUnderDifferentAccountDeletesSavedDestination() {
        val user = Mockito.mock(FirebaseUser::class.java)
        Mockito.`when`(user.uid).thenReturn("new-account")
        val auth = Mockito.mock(FirebaseAuth::class.java)
        Mockito.`when`(auth.currentUser).thenReturn(user)
        PdfIntegrationAuthShadow.auth = auth
        DestinationDocumentsShadow.files["partial.pdf"] = byteArrayOf(1)
        val state = Bundle().apply {
            putString("owner_uid", "old-account")
            putString("phase", "COPYING")
            putString("destination", DestinationDocumentsShadow.uri("partial.pdf").toString())
        }
        val controller = Robolectric.buildActivity(V2MonthlyPdfActivity::class.java).create(state)
        try {
            assertTrue(controller.get().isFinishing)
            assertTrue("Saved failed destination must be removed even before normal restoration", DestinationDocumentsShadow.deletedLatch.await(3, TimeUnit.SECONDS))
            assertFalse(DestinationDocumentsShadow.files.containsKey("partial.pdf"))
        } finally { controller.destroy() }
    }
}

@Implements(DocumentsContract::class)
class DestinationDocumentsShadow {
    companion object {
        val files = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()
        val deleted = java.util.concurrent.CopyOnWriteArrayList<String>()
        var deletedLatch = CountDownLatch(1)
        var nullOutput = false
        var failOutput = false
        var onWrite: (() -> Unit)? = null
        fun reset() { files.clear(); deleted.clear(); deletedLatch = CountDownLatch(1); nullOutput = false; failOutput = false; onWrite = null }
        fun uri(name: String): Uri = DocumentsContract.buildDocumentUriUsingTree(Uri.parse("content://pdf-test/tree/root"), name)
        @JvmStatic @Implementation fun createDocument(resolver: ContentResolver, parent: Uri, mime: String, name: String): Uri {
            check(!files.containsKey(name)); files[name] = byteArrayOf(); return uri(name)
        }
        @JvmStatic @Implementation fun deleteDocument(resolver: ContentResolver, uri: Uri): Boolean {
            val name = DocumentsContract.getDocumentId(uri); files.remove(name); deleted.add(name); deletedLatch.countDown(); return true
        }
    }
}
@Implements(ContentResolver::class)
class DestinationResolverShadow {
    @Implementation fun query(uri: Uri, projection: Array<String>?, selection: String?, args: Array<String>?, sort: String?): Cursor {
        val columns = projection ?: arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE)
        return MatrixCursor(columns).apply {
            DestinationDocumentsShadow.files.keys.forEach { name -> addRow(columns.map { column -> when(column) {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME -> name
                DocumentsContract.Document.COLUMN_MIME_TYPE -> "application/pdf"
                else -> null
            } }.toTypedArray()) }
        }
    }
    @Implementation fun openInputStream(uri: Uri): InputStream? = DestinationDocumentsShadow.files[DocumentsContract.getDocumentId(uri)]?.let(::ByteArrayInputStream)
    @Implementation fun openOutputStream(uri: Uri, mode: String): OutputStream? {
        if (DestinationDocumentsShadow.nullOutput) return null
        val name = DocumentsContract.getDocumentId(uri)
        return object : ByteArrayOutputStream() {
            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                super.write(bytes, offset, length)
                DestinationDocumentsShadow.onWrite?.invoke()
                if (DestinationDocumentsShadow.failOutput) throw java.io.IOException("simulated interrupted provider")
            }
            override fun close() { DestinationDocumentsShadow.files[name] = toByteArray(); super.close() }
        }
    }
}
