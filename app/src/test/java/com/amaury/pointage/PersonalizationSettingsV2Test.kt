package com.amaury.pointage

import android.app.Activity
import android.app.AlertDialog
import android.app.Application
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.Switch
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [28])
class PersonalizationSettingsV2Test {
    @Before fun bindStoreToThisTestApplication() {
        // Robolectric replaces Application/storage between tests but can reuse the Kotlin
        // singleton. Invalidate its account cache so it obtains this test's preferences.
        PersonalizationStoreV2::class.java.getDeclaredField("cachedOwner").apply {
            isAccessible = true
        }.set(null, null)
    }

    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()

    @Test fun readerZoomSurvivesChangeFromAlreadyOpenSettings() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        assertTrue(PersonalizationStoreV2.save(activity, PersonalizationProfileV2(), replaceUnreadable = true))
        PersonalizationSettingsV2.open(activity)
        val settings = ShadowAlertDialog.getLatestAlertDialog()
        val views = descendants(settings.window!!.decorView)
        val explanation = views.filterIsInstance<TextView>().single { it.text.startsWith("Confort visuel de ce compte") }
        assertTrue(explanation.performLongClick())
        val reader = ShadowAlertDialog.getLatestAlertDialog()
        assertNotSame(settings, reader)
        val enlarge = descendants(reader.window!!.decorView).filterIsInstance<Button>().single { it.text == "Agrandir" }
        enlarge.performClick()
        enlarge.performClick()
        reader.getButton(AlertDialog.BUTTON_NEUTRAL).performClick()
        // AlertDialog dispatches the button callback and dismissal through its Handler.
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(reader.isShowing)
        assertEquals(2f, PersonalizationStoreV2.read(activity).readerScale)
        views.filterIsInstance<Switch>().single { it.text == "Contraste renforcé des textes" }.isChecked = true
        val saved = PersonalizationStoreV2.read(activity)
        assertTrue(saved.highContrast)
        assertEquals(2f, saved.readerScale)
        settings.dismiss()
        controller.pause().stop().destroy()
    }

    @Test fun legacyEconomyCanBeDisabledUsingTheSingleMotionControl() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        assertTrue(PersonalizationStoreV2.save(activity, PersonalizationProfileV2(context = "economy", readerScale = 3f)))
        PersonalizationSettingsV2.open(activity)
        val settings = ShadowAlertDialog.getLatestAlertDialog()
        val views = descendants(settings.window!!.decorView)
        assertFalse(views.filterIsInstance<Button>().any { it.text == "Contexte visuel" })
        assertFalse(views.filterIsInstance<Switch>().any { it.text.startsWith("Nuit automatique") })
        val motion = views.filterIsInstance<Switch>().single { it.text == "Réduire les mouvements du ciel" }
        assertTrue(motion.isChecked)
        motion.isChecked = false
        val profile = PersonalizationStoreV2.read(activity)
        assertFalse(profile.effectiveReduceMotion)
        assertEquals("normal", profile.context)
        assertEquals(3f, profile.readerScale)
        settings.dismiss()
        controller.pause().stop().destroy()
    }

    @Test fun explicitDisplayModeOverridesOldNightWithoutLosingOtherPreferences() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val owner = PersonalizationStoreV2.accountScope()
        val before = PersonalizationProfileV2(context = "night", nightScheduleEnabled = true, highContrast = true, readerScale = 3f)
        assertTrue(PersonalizationStoreV2.save(activity, before))
        assertTrue(DisplayModeSettingsV2.selectMode(activity, owner, "light"))
        assertEquals(before.copy(context = "normal", nightScheduleEnabled = false), PersonalizationStoreV2.read(activity))
        assertFalse(AppThemeCatalog.useDarkPalette(activity))
        assertEquals("MODE : CLAIR", DisplayModeSettingsV2.label(activity))
        assertFalse(DisplayModeSettingsV2.selectMode(activity, "different-owner", "dark"))
        assertFalse(AppThemeCatalog.useDarkPalette(activity))
        controller.pause().stop().destroy()
    }

    @Test fun scheduledDisplayClearsOldContextButPreservesReducedMotion() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        assertTrue(PersonalizationStoreV2.save(activity, PersonalizationProfileV2(context = "economy", readerScale = 3f)))
        assertTrue(DisplayModeSettingsV2.selectSchedule(activity, PersonalizationStoreV2.accountScope(), 1200, 420))
        val profile = PersonalizationStoreV2.read(activity)
        assertTrue(profile.reduceMotion)
        assertEquals(3f, profile.readerScale)
        assertEquals("night", profile.effectiveContextAt(1260))
        assertEquals("normal", profile.effectiveContextAt(600))
        assertEquals("light", activity.getSharedPreferences(AppThemeCatalog.PREFS, 0).getString("mode", null))
        controller.pause().stop().destroy()
    }

    @Test fun corruptProfileSurvivesOrdinaryUpdatesAndCanBeExplicitlyReplaced() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val owner = PersonalizationStoreV2.accountScope()
        val scope = MessageDigest.getInstance("SHA-256").digest(owner.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        val prefs = activity.applicationContext.getSharedPreferences("personalization_private_v2_$scope", 0)
        val known = PersonalizationProfileV2(readerScale = 3f)
        assertTrue(prefs.edit().putString("profile", known.encode()).commit())
        // Prove the fixture and store address the same backing preferences before corruption.
        assertEquals(known, PersonalizationStoreV2.read(activity))
        val corrupt = "{unreadable-profile"
        assertTrue(prefs.edit().putString("profile", corrupt).commit())
        assertEquals(PersonalizationProfileV2(), PersonalizationStoreV2.read(activity))
        assertFalse(PersonalizationStoreV2.update(activity, owner) { it.copy(highContrast = true) })
        assertFalse(PersonalizationStoreV2.save(activity, PersonalizationProfileV2(readerScale = 2f)))
        assertEquals(corrupt, prefs.getString("profile", null))
        activity.getSharedPreferences(AppThemeCatalog.PREFS, 0).edit().putString("mode", "dark").commit()
        assertFalse(DisplayModeSettingsV2.selectMode(activity, owner, "light"))
        assertFalse(DisplayModeSettingsV2.selectSchedule(activity, owner, 1200, 420))
        assertEquals("dark", activity.getSharedPreferences(AppThemeCatalog.PREFS, 0).getString("mode", null))
        assertTrue(PersonalizationStoreV2.save(activity, PersonalizationProfileV2(readerScale = 2f), replaceUnreadable = true))
        assertEquals(2f, PersonalizationStoreV2.read(activity).readerScale)
        assertFalse(PersonalizationStoreV2.update(activity, "another-owner") { it.copy(readerScale = 4f) })
        assertEquals(2f, PersonalizationStoreV2.read(activity).readerScale)
        controller.pause().stop().destroy()
    }
}
