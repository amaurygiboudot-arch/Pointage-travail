package com.amaury.pointage.v2

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import com.amaury.pointage.PdfPreviewActivity
import com.amaury.pointage.billing.BillingContract
import com.amaury.pointage.billing.BillingPdfGate
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowContentResolver
import org.robolectric.shadows.ShadowToast
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class,
    shadows = [PdfSaveAuthShadow::class, PdfSaveGateShadow::class, PdfSaveResolverShadow::class],
    instrumentedPackages = ["com.amaury.pointage.billing", "com.google.firebase.auth"])
@LooperMode(LooperMode.Mode.PAUSED)
class PdfPreviewSaveIntegrationV2Test {
    private lateinit var source: File
    private lateinit var destination: File
    private val uri = DocumentsContract.buildDocumentUri("pdf.save.test", "created")

    @Before fun setupCreatedDocument() {
        val app = RuntimeEnvironment.getApplication<Application>()
        val user = Mockito.mock(FirebaseUser::class.java)
        Mockito.`when`(user.uid).thenReturn("save-user")
        PdfSaveAuthShadow.auth = Mockito.mock(FirebaseAuth::class.java)
        Mockito.`when`(PdfSaveAuthShadow.auth.currentUser).thenReturn(user)
        source = File(app.filesDir, "source.pdf").apply { writeBytes(ByteArray(20000) { (it % 251).toByte() }) }
        destination = File(app.filesDir, "public_destination.pdf").apply { writeBytes(byteArrayOf()) }
        PdfSaveDocumentsProvider.document = destination
        PdfSaveDocumentsProvider.deletions.set(0)
        val provider = PdfSaveDocumentsProvider()
        provider.attachInfo(app, ProviderInfo().apply {
            authority = "pdf.save.test"; exported = true; grantUriPermissions = true
            readPermission = "android.permission.MANAGE_DOCUMENTS"
            writePermission = "android.permission.MANAGE_DOCUMENTS"
        })
        ShadowContentResolver.registerProviderInternal("pdf.save.test", provider)
        PdfSaveGateShadow.allow = true; PdfSaveGateShadow.unavailable = false
        PdfSaveGateShadow.calls.set(0)
        PdfSaveResolverShadow.mode = "success"
    }

    @Test fun deniedAuthorizationDeletesCreatedDocumentWithoutCopy() = runSave("denied")
    @Test fun unavailableAuthorizationDeletesCreatedDocumentWithoutCopy() = runSave("unavailable")
    @Test fun partialCopyFailureDeletesPartialDocument() = runSave("partial")
    @Test fun nullOutputStreamIsFailureAndDeletesCreatedDocument() = runSave("null")
    @Test fun successfulSaveRetainsExactAuthorizedBytes() = runSave("success")
    @Test fun accountChangeRejectsAndDeletesDestinationBeforeAuthorization() = runSave("account")
    @Test fun changedHashRejectsAndDeletesDestinationBeforeAuthorization() = runSave("hash")

    @Test fun restoredCopyWithChangedAccountQueuesActualDocumentCleanup() {
        val state = Bundle().apply {
            putString("pdf_owner", "former-user"); putString("save_file", source.absolutePath)
            putString("save_hash", BillingContract.documentId(source)); putBoolean("save_picker", true)
            putString("save_uri", uri.toString())
        }
        destination.writeText("interrupted partial export")
        val intent = Intent(RuntimeEnvironment.getApplication(), PdfPreviewActivity::class.java)
            .putExtra("pdf_path", source.absolutePath)
        val controller = Robolectric.buildActivity(PdfPreviewActivity::class.java, intent).create(state)
        try {
            val deadline = System.nanoTime() + 5_000_000_000L
            while (System.nanoTime() < deadline && destination.exists()) { shadowOf(Looper.getMainLooper()).idle(); Thread.yield() }
            assertTrue(controller.get().isFinishing)
            assertFalse(destination.exists())
            assertEquals(1, PdfSaveDocumentsProvider.deletions.get())
            assertEquals(0, PdfSaveGateShadow.calls.get())
        } finally { controller.destroy() }
    }

    private fun runSave(mode: String) {
        // Restore the non-authoritative state saved when the actual picker was launched.
        val state = Bundle().apply {
            putString("pdf_owner", "save-user"); putString("save_file", source.absolutePath)
            putString("save_hash", BillingContract.documentId(source)); putBoolean("save_picker", true)
        }
        val intent = Intent(RuntimeEnvironment.getApplication(), PdfPreviewActivity::class.java)
            .putExtra("pdf_path", source.absolutePath).putExtra("pdf_name", "rapport.pdf")
        val controller = Robolectric.buildActivity(PdfPreviewActivity::class.java, intent).create(state)
        try {
            val activity = controller.get()
            when (mode) {
                "denied" -> PdfSaveGateShadow.allow = false
                "unavailable" -> PdfSaveGateShadow.unavailable = true
                "partial", "null" -> PdfSaveResolverShadow.mode = mode
                "account" -> Mockito.`when`(PdfSaveAuthShadow.auth.currentUser).thenReturn(null)
                "hash" -> source.appendText("changed")
            }
            PdfPreviewActivity::class.java.getDeclaredMethod("onActivityResult", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Intent::class.java)
                .apply { isAccessible = true }.invoke(activity, 4102, android.app.Activity.RESULT_OK, Intent().setData(uri))
            val deadline = System.nanoTime() + 5_000_000_000L
            while (System.nanoTime() < deadline && ShadowToast.getTextOfLatestToast() == null) {
                shadowOf(Looper.getMainLooper()).idle()
                Thread.yield()
            }
            shadowOf(Looper.getMainLooper()).idle()
            if (mode == "success") {
                assertTrue(destination.exists())
                assertArrayEquals(source.readBytes(), destination.readBytes())
                assertEquals("PDF enregistré", ShadowToast.getTextOfLatestToast())
                assertEquals(0, PdfSaveDocumentsProvider.deletions.get())
            } else {
                assertFalse("The actual DocumentsProvider must remove the created/partial document", destination.exists())
                assertEquals(1, PdfSaveDocumentsProvider.deletions.get())
                assertNotEquals("PDF enregistré", ShadowToast.getTextOfLatestToast())
            }
            assertNull("No new purchase or picker after destination creation", shadowOf(activity).nextStartedActivity)
            if (mode in setOf("account", "hash")) assertEquals(0, PdfSaveGateShadow.calls.get())
            else assertEquals(1, PdfSaveGateShadow.calls.get())
        } finally { controller.destroy() }
    }
}

@Implements(value = FirebaseAuth::class, isInAndroidSdk = false)
class PdfSaveAuthShadow {
    companion object {
        lateinit var auth: FirebaseAuth
        @JvmStatic @Implementation fun getInstance(): FirebaseAuth = auth
    }
}

@Implements(value = BillingPdfGate::class, isInAndroidSdk = false)
class PdfSaveGateShadow {
    companion object {
        @Volatile var allow = true
        @Volatile var unavailable = false
        val calls = AtomicInteger()
    }
    @Implementation fun authorizeBackgroundBlocking(context: Context, file: File): Boolean {
        calls.incrementAndGet()
        if (unavailable) throw IOException("Backend unavailable")
        return allow
    }
}

@Implements(android.content.ContentResolver::class)
class PdfSaveResolverShadow : ShadowContentResolver() {
    companion object { @Volatile var mode = "success" }
    @Implementation override fun openOutputStream(uri: Uri, modeString: String): OutputStream? {
        if (mode == "null") return null
        val delegate = FileOutputStream(PdfSaveDocumentsProvider.document)
        if (mode != "partial") return delegate
        return object : OutputStream() {
            override fun write(value: Int) { delegate.write(value); throw IOException("disk full") }
            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                delegate.write(bytes, offset, minOf(length, 5)); throw IOException("disk full after partial write")
            }
            override fun close() = delegate.close()
        }
    }
}

/** Real deleteDocument implementation reached through DocumentsContract and ContentResolver. */
class PdfSaveDocumentsProvider : DocumentsProvider() {
    companion object { lateinit var document: File; val deletions = AtomicInteger() }
    override fun onCreate() = true
    override fun queryRoots(projection: Array<out String>?): Cursor = MatrixCursor(projection ?: arrayOf(DocumentsContract.Root.COLUMN_ROOT_ID))
    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor = MatrixCursor(projection ?: arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID)).apply { addRow(arrayOf(documentId)) }
    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor = MatrixCursor(projection ?: arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID))
    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor = ParcelFileDescriptor.open(document, ParcelFileDescriptor.parseMode(mode))
    override fun deleteDocument(documentId: String) { deletions.incrementAndGet(); check(document.delete()) }
}
