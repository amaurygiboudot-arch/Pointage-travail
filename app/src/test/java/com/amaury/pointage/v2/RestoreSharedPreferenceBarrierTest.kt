package com.amaury.pointage.v2

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.amaury.pointage.GpsDeliveryAuthShadow
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class,
    shadows = [GpsDeliveryAuthShadow::class], instrumentedPackages = ["com.google.firebase.auth"])
class RestoreSharedPreferenceBarrierTest {
    private lateinit var context: Context
    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        val auth = Mockito.mock(FirebaseAuth::class.java)
        val user = Mockito.mock(FirebaseUser::class.java)
        Mockito.`when`(user.uid).thenReturn("A")
        Mockito.`when`(auth.currentUser).thenReturn(user)
        GpsDeliveryAuthShadow.auth = auth
        SalaryAccountScope.capture()
    }

    @Test fun editorCapturedBeforeRestoreCannotInterleaveWithItsPublication() {
        val publishing = CountDownLatch(1)
        val release = CountDownLatch(1)
        val first = AtomicBoolean(true)
        val app = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                val actual = super.getSharedPreferences(name, mode)
                if (name != "appearance_settings") return actual
                return object : SharedPreferences by actual {
                    override fun edit(): SharedPreferences.Editor {
                        val editor = actual.edit()
                        return object : SharedPreferences.Editor by editor {
                            override fun commit(): Boolean {
                                if (Thread.currentThread().name == "restoration-test" && first.compareAndSet(true, false)) {
                                    publishing.countDown()
                                    check(release.await(10, TimeUnit.SECONDS))
                                }
                                return editor.commit()
                            }
                            override fun clear(): SharedPreferences.Editor { editor.clear(); return this }
                            override fun putString(key: String?, value: String?): SharedPreferences.Editor { editor.putString(key, value); return this }
                        }
                    }
                }
            }
        }
        val gps = RestoreGuardedPreferences.open(app, "gps_settings")
        assertTrue(gps.edit().putInt("radius", 100).commit())
        val heldEditor = gps.edit().putInt("radius", 250)
        val lease = SalaryAccountScope.capture()
        val restoreExecutor = Executors.newSingleThreadExecutor { Thread(it, "restoration-test") }
        val otherExecutor = Executors.newFixedThreadPool(2)
        try {
            val restore = restoreExecutor.submit<SalaryAccountScope.Lease> {
                V2RestoreTransaction.execute(app, "uid:A", { SalaryAccountScope.isCurrent(lease) }) {
                    linkedMapOf("gps_settings" to mapOf("radius" to 150),
                        "appearance_settings" to mapOf("theme" to "restored"))
                }
            }
            assertTrue(publishing.await(10, TimeUnit.SECONDS))
            val writerStarted = CountDownLatch(1)
            val writer = otherExecutor.submit<Boolean> { writerStarted.countDown(); heldEditor.commit() }
            val readerStarted = CountDownLatch(1)
            val reader = otherExecutor.submit<String?> {
                readerStarted.countDown()
                RestoreGuardedPreferences.open(app, "appearance_settings").getString("theme", null)
            }
            assertTrue(writerStarted.await(5, TimeUnit.SECONDS))
            assertTrue(readerStarted.await(5, TimeUnit.SECONDS))
            assertThrows(TimeoutException::class.java) { writer.get(150, TimeUnit.MILLISECONDS) }
            assertThrows(TimeoutException::class.java) { reader.get(150, TimeUnit.MILLISECONDS) }
            release.countDown()
            assertEquals("uid:A", restore.get(10, TimeUnit.SECONDS).scope)
            assertTrue(writer.get(10, TimeUnit.SECONDS))
            assertEquals("restored", reader.get(10, TimeUnit.SECONDS))
            assertEquals(250, gps.getInt("radius", 0))
        } finally {
            release.countDown()
            restoreExecutor.shutdownNow()
            otherExecutor.shutdownNow()
        }
    }

    @Test fun corruptPendingRestoreBlocksCachedSharedEditorsAndKeepsOriginalValues() {
        val prefs = RestoreGuardedPreferences.open(context, "gps_settings")
        assertTrue(prefs.edit().putInt("radius", 100).commit())
        val editor = prefs.edit().putInt("radius", 250)
        val journal = java.io.File(context.noBackupFilesDir, "agkgmg_restore_transaction_v1.json")
        journal.writeText("broken")
        assertFalse(editor.commit())
        assertTrue(prefs.all.isEmpty())
        assertEquals(100, context.getSharedPreferences("gps_settings", Context.MODE_PRIVATE).getInt("radius", 0))
        assertTrue(journal.exists())
    }
    @Test fun sharedAppearancePreferencesRemainUsableWithoutFirebaseAuthentication() {
        Mockito.doThrow(IllegalStateException("Synthetic authentication unavailable"))
            .`when`(GpsDeliveryAuthShadow.auth).currentUser
        assertNull(SalaryAccountScope.capture().scope)
        val prefs = RestoreGuardedPreferences.open(context, "appearance_settings")
        assertTrue(prefs.edit().putString("theme", "clair").commit())
        assertEquals("clair", prefs.getString("theme", null))
    }

}
