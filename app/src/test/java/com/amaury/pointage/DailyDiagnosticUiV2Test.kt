package com.amaury.pointage

import android.app.Activity
import android.app.AlertDialog
import android.app.Application
import android.app.DatePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.Switch
import android.widget.TextView
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowDialog

/** Real dialogs under the native production themes: no AppCompat theme is injected by the test. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class,
    shadows = [DailyDiagnosticAuthShadow::class], instrumentedPackages = ["com.google.firebase.auth"])
@LooperMode(LooperMode.Mode.PAUSED)
class DailyDiagnosticUiV2Test {
    private lateinit var auth: FirebaseAuth
    private val scope = "uid:diagnostic-ui-user"
    private val listeners = mutableListOf<FirebaseAuth.AuthStateListener>()

    @Before fun authenticatedOffline() {
        auth = Mockito.mock(FirebaseAuth::class.java)
        DailyDiagnosticAuthShadow.auth = auth
        account("diagnostic-ui-user")
        Mockito.doAnswer { invocation ->
            listeners.add(invocation.getArgument<FirebaseAuth.AuthStateListener>(0)); null
        }.`when`(auth).addAuthStateListener(Mockito.any(FirebaseAuth.AuthStateListener::class.java))
        Mockito.doAnswer { invocation ->
            listeners.remove(invocation.getArgument<FirebaseAuth.AuthStateListener>(0)); null
        }.`when`(auth).removeAuthStateListener(Mockito.any(FirebaseAuth.AuthStateListener::class.java))
    }

    private fun account(uid: String) {
        val user = Mockito.mock(FirebaseUser::class.java)
        Mockito.`when`(user.uid).thenReturn(uid)
        Mockito.`when`(auth.currentUser).thenReturn(user)
    }

    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()

    private fun button(dialog: AlertDialog, text: String): Button =
        descendants(checkNotNull(dialog.window).decorView).filterIsInstance<Button>().single { it.text.toString() == text }

    private fun latest(): AlertDialog {
        shadowOf(Looper.getMainLooper()).idle()
        return (ShadowDialog.getLatestDialog() as AlertDialog).also { assertTrue(it.isShowing) }
    }

    private fun withNativeHost(block: (Activity) -> Unit) {
        val controller = Robolectric.buildActivity(Activity::class.java)
        val activity = controller.get()
        // MainActivity extends Activity and uses this exact Material theme in AndroidManifest.xml.
        activity.setTheme(R.style.Theme_PointageTravail)
        controller.setup()
        DailyDiagnosticStoreV2.clear(activity, scope)
        try { block(activity) } finally {
            ShadowDialog.getShownDialogs().toList().forEach { it.dismiss() }
            shadowOf(Looper.getMainLooper()).idle()
            controller.pause().stop().destroy()
        }
    }

    private fun exerciseNativeDialogFlow() = withNativeHost { activity ->
        DailyDiagnosticUiV2.show(activity)
        val main = latest()
        val toggle = descendants(checkNotNull(main.window).decorView).filterIsInstance<Switch>().single()
        assertFalse(toggle.isChecked)
        toggle.performClick()
        assertTrue(DailyDiagnosticStoreV2.read(activity, scope).enabled)

        val dateButton = descendants(checkNotNull(main.window).decorView).filterIsInstance<Button>()
            .single { it.text.toString().startsWith("Journée : ") }
        assertTrue(dateButton.performClick())
        val picker = latest() as DatePickerDialog
        picker.datePicker.updateDate(2026, 9, 14)
        picker.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("Journée : 2026-10-14", dateButton.text.toString())

        assertTrue(button(main, "PRÉVISUALISER ET PARTAGER").performClick())
        val preview = latest()
        val report = descendants(checkNotNull(preview.window).decorView).filterIsInstance<TextView>()
            .single { it.text.toString().startsWith("HORATRACK — DIAGNOSTIC DE JOURNÉE") }
        assertTrue(report.text.contains("Journée : 2026-10-14"))
        assertTrue(report.text.contains("RÉCIT À DEMANDER À L'UTILISATEUR"))
        preview.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(preview.isShowing)
        assertTrue(main.isShowing)

        assertTrue(button(main, "EFFACER LE DIAGNOSTIC ET ARRÊTER").performClick())
        val confirmation = latest()
        confirmation.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(main.isShowing)
        val stored = DailyDiagnosticStoreV2.read(activity, scope)
        assertFalse(stored.enabled)
        assertTrue(stored.events.isEmpty())
    }

    @Test @Config(qualifiers = "notnight")
    fun nativeDayThemeSupportsOpeningRecordingDatePreviewAndClear() = exerciseNativeDialogFlow()

    @Test @Config(qualifiers = "night")
    fun nativeNightThemeSupportsOpeningRecordingDatePreviewAndClear() = exerciseNativeDialogFlow()

    /** Runs against AGP's actual merged manifest for testDebugUnitTest AND testPlayUnitTest. */
    @Test fun mergedVariantManifestKeepsDiagnosticProviderAndItsNarrowFileRoot() = withNativeHost { activity ->
        val authority = "${activity.packageName}.daily-diagnostic-files"
        val provider = activity.packageManager.resolveContentProvider(authority, PackageManager.GET_META_DATA)
        assertNotNull("The delivered variant must retain the diagnostic provider", provider)
        requireNotNull(provider)
        assertEquals(DailyDiagnosticFileProvider::class.java.name, provider.name)
        assertFalse(provider.exported)
        assertTrue(provider.grantUriPermissions)
        assertEquals(R.xml.daily_diagnostic_file_paths,
            provider.metaData.getInt("android.support.FILE_PROVIDER_PATHS"))
        if (!BuildConfig.INTERNAL_APK_UPDATES_ENABLED) {
            assertNull("Play must still remove the internal APK update provider",
                activity.packageManager.resolveContentProvider("${activity.packageName}.update-files", 0))
        }
        val unrelated = File(activity.filesDir, "private-not-a-diagnostic.txt").apply { writeText("private") }
        try {
            assertThrows(IllegalArgumentException::class.java) {
                FileProvider.getUriForFile(activity, authority, unrelated)
            }
        } finally { unrelated.delete() }
    }

    @Test fun actualShareButtonProducesReadableContentUriWithTemporaryReadGrant() = withNativeHost { activity ->
        DailyDiagnosticUiV2.show(activity)
        val main = latest()
        button(main, "PRÉVISUALISER ET PARTAGER").performClick()
        val preview = latest()
        val expected = descendants(checkNotNull(preview.window).decorView).filterIsInstance<TextView>()
            .single { it.text.toString().startsWith("HORATRACK — DIAGNOSTIC DE JOURNÉE") }.text.toString()
        preview.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()

        // This checks the real click handler, provider metadata/path resolution, and actual file bytes.
        // A swallowed FileProvider exception leaves no chooser and fails this assertion.
        val chooser = requireNotNull(shadowOf(activity).nextStartedActivity) {
            "Sharing must launch Android's chooser rather than show an error toast"
        }
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        val send = requireNotNull(chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT))
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("text/plain", send.type)
        assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION,
            send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION)
        assertEquals(0, send.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        val uri = requireNotNull(send.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
        assertEquals("content", uri.scheme)
        assertEquals("${activity.packageName}.daily-diagnostic-files", uri.authority)
        assertEquals(uri, requireNotNull(send.clipData).getItemAt(0).uri)
        val contents = requireNotNull(activity.contentResolver.openInputStream(uri)).bufferedReader().use { it.readText() }
        assertEquals(expected, contents)
    }

    @Test fun accountSwitchDismissesNativePreviewAndRemovesSensitiveText() = withNativeHost { activity ->
        DailyDiagnosticUiV2.show(activity)
        val main = latest()
        button(main, "PRÉVISUALISER ET PARTAGER").performClick()
        val preview = latest()
        val report = descendants(checkNotNull(preview.window).decorView).filterIsInstance<TextView>()
            .single { it.text.toString().startsWith("HORATRACK — DIAGNOSTIC DE JOURNÉE") }
        assertTrue(report.text.isNotEmpty())
        account("different-user")
        listeners.toList().forEach { it.onAuthStateChanged(auth) }
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(main.isShowing)
        assertFalse(preview.isShowing)
        assertTrue(report.text.isEmpty())
        assertTrue(listeners.isEmpty())
    }
}
