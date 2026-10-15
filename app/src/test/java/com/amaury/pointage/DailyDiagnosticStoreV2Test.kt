package com.amaury.pointage

import android.app.Application
import android.content.Context
import com.amaury.pointage.v2.V2RuntimeAccountScope
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, shadows = [DailyDiagnosticAuthShadow::class], instrumentedPackages = ["com.google.firebase.auth"])
class DailyDiagnosticStoreV2Test {
    private lateinit var context: Context
    private lateinit var auth: FirebaseAuth
    private val scope = "uid:diagnostic-a"
    private val worker: ThreadPoolExecutor get() = DailyDiagnosticStoreV2::class.java.getDeclaredField("worker").let {
        it.isAccessible = true; it.get(null) as ThreadPoolExecutor
    }
    private fun drain() { worker.submit {}.get(5, TimeUnit.SECONDS) }
    private fun account(uid: String) {
        val user = Mockito.mock(FirebaseUser::class.java)
        Mockito.`when`(user.uid).thenReturn(uid)
        Mockito.`when`(auth.currentUser).thenReturn(user)
    }
    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        auth = Mockito.mock(FirebaseAuth::class.java)
        DailyDiagnosticAuthShadow.auth = auth
        account("diagnostic-a")
        drain()
        DailyDiagnosticStoreV2.clear(context, scope)
    }
    @Test fun disabledByDefaultAndNoRetroactiveCollection() {
        DailyDiagnosticStoreV2.record(context, DailyDiagnosticKindV2.GPS_EVENT)
        drain()
        assertFalse(DailyDiagnosticStoreV2.read(context, scope).enabled)
        assertTrue(DailyDiagnosticStoreV2.read(context, scope).events.isEmpty())
        assertTrue(DailyDiagnosticStoreV2.setEnabled(context, scope, true))
        assertEquals(listOf(DailyDiagnosticKindV2.RECORDING_ENABLED), DailyDiagnosticStoreV2.read(context, scope).events.map { it.kind })
    }
    @Test fun optInPersistsPseudonymsButNeverRawPlaceOrUntrustedCode() {
        DailyDiagnosticStoreV2.setEnabled(context, scope, true)
        repeat(2) { DailyDiagnosticStoreV2.record(context, DailyDiagnosticKindV2.GPS_EVENT, code = "secret@example.fr", zoneId = "private-address-123") }
        drain()
        val events = DailyDiagnosticStoreV2.read(context, scope).events.filter { it.kind == DailyDiagnosticKindV2.GPS_EVENT }
        assertEquals(2, events.size)
        assertEquals(events[0].zoneToken, events[1].zoneToken)
        assertTrue(events[0].zoneToken!!.matches(Regex("[a-f0-9]{16}")))
        val storage = File(context.noBackupFilesDir, V2RuntimeAccountScope.fileName("daily_diagnostic_v2", scope) + ".json").readText()
        assertFalse(storage.contains("private-address")); assertFalse(storage.contains("secret@example")); assertFalse(storage.contains("diagnostic-a"))
    }
    @Test fun anotherAccountCannotReadEnableOrClearOriginalTrace() {
        DailyDiagnosticStoreV2.setEnabled(context, scope, true)
        DailyDiagnosticStoreV2.record(context, DailyDiagnosticKindV2.ENTRY_REQUEST)
        drain()
        account("diagnostic-b")
        assertFalse(DailyDiagnosticStoreV2.read(context, scope).readable)
        assertFalse(DailyDiagnosticStoreV2.setEnabled(context, scope, false))
        assertFalse(DailyDiagnosticStoreV2.clear(context, scope))
        assertTrue(DailyDiagnosticStoreV2.read(context, "uid:diagnostic-b").events.isEmpty())
        account("diagnostic-a")
        assertTrue(DailyDiagnosticStoreV2.read(context, scope).events.any { it.kind == DailyDiagnosticKindV2.ENTRY_REQUEST })
    }
    @Test fun clearingAndReenablingDoesNotResurrectQueuedEvents() {
        DailyDiagnosticStoreV2.setEnabled(context, scope, true)
        val waiting = CountDownLatch(1)
        val release = CountDownLatch(1)
        worker.execute { waiting.countDown(); release.await(5, TimeUnit.SECONDS) }
        assertTrue(waiting.await(5, TimeUnit.SECONDS))
        try {
            DailyDiagnosticStoreV2.record(context, DailyDiagnosticKindV2.EXIT_REQUEST)
            assertTrue(DailyDiagnosticStoreV2.clear(context, scope))
            assertTrue(DailyDiagnosticStoreV2.setEnabled(context, scope, true))
        } finally { release.countDown() }
        drain()
        assertFalse(DailyDiagnosticStoreV2.read(context, scope).events.any { it.kind == DailyDiagnosticKindV2.EXIT_REQUEST })
    }
    @Test fun corruptDiagnosticIsExplicitAndDoesNotThrowIntoPointageHook() {
        DailyDiagnosticStoreV2.setEnabled(context, scope, true)
        File(context.noBackupFilesDir, V2RuntimeAccountScope.fileName("daily_diagnostic_v2", scope) + ".json").writeText("broken")
        DailyDiagnosticStoreV2.record(context, DailyDiagnosticKindV2.ENTRY_REQUEST)
        drain()
        assertFalse(DailyDiagnosticStoreV2.read(context, scope).readable)
    }
}

@Implements(value = FirebaseAuth::class, isInAndroidSdk = false)
class DailyDiagnosticAuthShadow {
    companion object {
        lateinit var auth: FirebaseAuth
        @JvmStatic @Implementation fun getInstance(): FirebaseAuth = auth
    }
}
