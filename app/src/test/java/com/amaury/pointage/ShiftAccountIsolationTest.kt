package com.amaury.pointage

import android.app.Activity
import android.app.AlertDialog
import android.app.Application
import android.content.Context
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import com.amaury.pointage.v2.SalaryAccountScope
import com.amaury.pointage.v2.SalaryLegacyRecovery
import com.amaury.pointage.v2.SalaryPreferenceRegistry
import com.amaury.pointage.v2.V2BackupManager
import com.amaury.pointage.v2.V2PreferenceValues
import com.amaury.pointage.v2.V2ProfileStore
import com.amaury.pointage.v2.V2ScheduleStore
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog
import java.io.File

/** Real schedule stores, dialogs and backup paths; only Firebase identity is simulated. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], shadows = [GpsDeliveryAuthShadow::class],
    instrumentedPackages = ["com.google.firebase.auth"])
class ShiftAccountIsolationTest {
    private lateinit var app: Context
    private lateinit var auth: FirebaseAuth
    private val users = mutableMapOf<String, FirebaseUser>()
    private val company = SalaryCompanyStore.Company("siret_12345678901234", "Synthetic shared employer", "12345678901234")
    private val ownerA = "shift-owner-A"
    private val ownerB = "shift-owner-B"

    @Before fun setup() {
        app = RuntimeEnvironment.getApplication()
        auth = Mockito.mock(FirebaseAuth::class.java)
        GpsDeliveryAuthShadow.auth = auth
        account(ownerA)
    }

    private fun account(uid: String?) {
        val user = uid?.let { id -> users.getOrPut(id) {
            Mockito.mock(FirebaseUser::class.java).also { Mockito.`when`(it.uid).thenReturn(id) }
        } }
        Mockito.`when`(auth.currentUser).thenReturn(user)
        SalaryAccountScope.capture()
    }

    private fun seed(start: String, end: String, mode: String = "day") {
        assertTrue(SalaryCompanyStore.createOrUpdate(app, company))
        assertTrue(V2ProfileStore.setActiveCompanyId(app, company.id))
        assertTrue(V2ScheduleStore.save(app, company.id, "day", start, end))
        assertTrue(V2ScheduleStore.setSelectedMode(app, company.id, mode))
    }

    private fun assertDay(start: String?, end: String?) {
        val schedule = V2ScheduleStore.schedule(app, company.id, "day")
        assertEquals(start?.let(V2ScheduleStore::parseMinute), schedule.startMinute)
        assertEquals(end?.let(V2ScheduleStore::parseMinute), schedule.endMinute)
    }

    private fun clearPersonal() {
        val scope = requireNotNull(SalaryAccountScope.capture().scope)
        SalaryPreferenceRegistry.personalNames(app, scope).forEach { name ->
            assertTrue(SalaryAccountScope.preferences(app, name).edit().clear().commit())
        }
    }

    private fun children(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { children(view.getChildAt(it)) } else emptyList()

    private fun button(view: View, label: String): Button = children(view).filterIsInstance<Button>()
        .single { it.text.toString() == label }

    @Test fun sameEmployerSchedulesAndSnapshotsStayWithTheirAccount() {
        seed("06:13", "14:17")
        account(ownerB)
        assertTrue(SalaryCompanyStore.createOrUpdate(app, company))
        assertDay(null, null)
        assertEquals("auto", V2ScheduleStore.selectedMode(app, company.id))
        seed("10:23", "18:29", "night")
        val snapshotB = V2BackupManager.snapshot(app)
        val shifts = V2PreferenceValues.decode(snapshotB.getJSONObject("preferences").getJSONObject("shift_profiles"))
        assertEquals("10:23", shifts[V2ScheduleStore.companyPreferenceKey(company.id, "expected_start_day")])
        assertEquals("18:29", shifts[V2ScheduleStore.companyPreferenceKey(company.id, "expected_end_day")])
        assertFalse(shifts.values.contains("06:13"))
        assertFalse(shifts.values.contains("14:17"))
        account(ownerA)
        assertDay("06:13", "14:17")
        assertEquals("day", V2ScheduleStore.selectedMode(app, company.id))
    }

    @Test fun capturedScheduleContextAndEditorNeverReviveAfterAccountRoundTrip() {
        seed("07:00", "15:00")
        val captured = SalaryAccountScope.bindContext(app)
        val editor = SalaryAccountScope.preferences(captured, "shift_profiles").edit()
            .putString(V2ScheduleStore.companyPreferenceKey(company.id, "selected_shift"), "night")
        account(ownerB)
        seed("09:00", "17:00")
        assertFalse(V2ScheduleStore.save(captured, company.id, "day", "01:00", "23:00"))
        assertFalse(V2ScheduleStore.setSelectedMode(captured, company.id, "night"))
        assertFalse(editor.commit())
        assertDay("09:00", "17:00")
        account(ownerA)
        assertFalse(editor.commit())
        assertFalse(V2ScheduleStore.setSelectedMode(captured, company.id, "night"))
        assertNull(V2ScheduleStore.schedule(captured, company.id, "day").startMinute)
        assertDay("07:00", "15:00")
        assertEquals("day", V2ScheduleStore.selectedMode(app, company.id))
        account(null)
        assertNull(V2ScheduleStore.schedule(app, company.id, "day").startMinute)
        assertEquals("auto", ShiftProfileManager.selectedMode(app))
    }

    @Test fun globalHistoricalSchedulesNeedExplicitOwnershipAndKeepTheirOriginals() {
        val global = app.getSharedPreferences("shift_profiles", 0)
        assertTrue(global.edit().putString("selected_shift", "day")
            .putString("expected_start_day", "05:11").putString("expected_end_day", "13:19").commit())
        val original = global.all.toMap()
        assertNull(V2ScheduleStore.legacySchedule(app, "day").startMinute)
        assertEquals("auto", ShiftProfileManager.selectedMode(app))
        val preview = requireNotNull(SalaryLegacyRecovery.preview(app))
        assertFalse(SalaryLegacyRecovery.claim(app, preview.digest, false))
        assertTrue(SalaryLegacyRecovery.claim(app, preview.digest, true))
        assertEquals(original, global.all)
        assertEquals(311, V2ScheduleStore.legacySchedule(app, "day").startMinute)
        assertTrue(SalaryCompanyStore.createOrUpdate(app, company))
        assertDay("05:11", "13:19")
        account(ownerB)
        assertNull(V2ScheduleStore.legacySchedule(app, "day").startMinute)
        assertFalse(SalaryLegacyRecovery.claim(app, preview.digest, true))
        assertEquals(original, global.all)
    }

    @Test fun currentBackupRestoresSchedulesOnlyToItsOriginalOwner() {
        seed("08:07", "16:09", "night")
        val snapshot = V2BackupManager.snapshot(app).toString()
        clearPersonal()
        assertDay(null, null)
        val result = V2BackupManager.restoreFromJson(app, snapshot)
        assertTrue(result.exceptionOrNull()?.stackTraceToString(), result.isSuccess)
        assertDay("08:07", "16:09")
        assertEquals("night", V2ScheduleStore.selectedMode(app, company.id))
        account(ownerB)
        seed("11:00", "19:00")
        assertTrue(V2BackupManager.restoreFromJson(app, snapshot).isFailure)
        assertDay("11:00", "19:00")
    }

    @Test fun historicalBackupSchedulesAreQuarantinedUntilExplicitAttribution() {
        val legacy = JSONObject().put("formatVersion", 5).put("accountScope", "uid:$ownerA")
            .put("preferences", JSONObject().put("shift_profiles", V2PreferenceValues.encode(mapOf(
                "selected_shift" to "day", "expected_start_day" to "04:31", "expected_end_day" to "12:37"
            )))).toString(2)
        val result = V2BackupManager.restoreFromJson(app, legacy).getOrThrow()
        assertEquals(0, result.restoredFiles)
        assertEquals(1, result.quarantinedSalaryFiles)
        assertNull(V2ScheduleStore.legacySchedule(app, "day").startMinute)
        val preview = V2BackupManager.legacySalaryRecoveries(app).single()
        val preserved = File(app.noBackupFilesDir, "agkgmg_salary_recovery/${preview.id}.json")
        assertEquals(legacy, preserved.readText())
        assertTrue(V2BackupManager.confirmLegacySalaryRestore(app, preview.id, preview.fingerprint, false).isFailure)
        assertTrue(V2BackupManager.confirmLegacySalaryRestore(app, preview.id, preview.fingerprint, true).isSuccess)
        assertEquals(271, V2ScheduleStore.legacySchedule(app, "day").startMinute)
        assertEquals(legacy, preserved.readText())
        account(ownerB)
        assertTrue(V2BackupManager.confirmLegacySalaryRestore(app, preview.id, preview.fingerprint, true).isFailure)
        assertNull(V2ScheduleStore.legacySchedule(app, "day").startMinute)
    }

    @Test fun modeDialogCannotSaveAfterAccountRoundTripAndFreshDialogStillWorks() {
        seed("07:00", "15:00")
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val screen = ShiftControlView(controller.get())
            controller.get().setContentView(screen)
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            button(screen, "Poste : Journée").performClick()
            val oldDialog = ShadowAlertDialog.getLatestAlertDialog()
            assertTrue(oldDialog.isShowing)
            account(ownerB)
            seed("09:00", "17:00")
            account(ownerA)
            oldDialog.listView.performItemClick(null, 4, 4L)
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            assertFalse(oldDialog.isShowing)
            assertEquals("day", V2ScheduleStore.selectedMode(app, company.id))
            screen.refresh()
            button(screen, "Poste : Journée").performClick()
            val freshDialog = ShadowAlertDialog.getLatestAlertDialog()
            assertNotSame(oldDialog, freshDialog)
            freshDialog.listView.performItemClick(null, 1, 1L)
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            assertEquals("morning", V2ScheduleStore.selectedMode(app, company.id))
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun scheduleDialogCannotApplyValuesToANewAccountOrReviveOnReturn() {
        seed("07:00", "15:00")
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val screen = ShiftControlView(controller.get())
            controller.get().setContentView(screen)
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            button(screen, "Horaires").performClick()
            val dialog = ShadowAlertDialog.getLatestAlertDialog()
            assertTrue(dialog.isShowing)
            val fields = children(dialog.window!!.decorView).filterIsInstance<EditText>()
            assertEquals(8, fields.size)
            fields[2].setText("01:11")
            fields[3].setText("23:47")
            val save = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            account(ownerB)
            seed("09:00", "17:00")
            save.performClick()
            assertDay("09:00", "17:00")
            account(ownerA)
            save.performClick()
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            assertFalse(dialog.isShowing)
            assertDay("07:00", "15:00")
        } finally {
            controller.pause().stop().destroy()
        }
    }
}
