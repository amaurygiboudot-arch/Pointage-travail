package com.amaury.pointage

import android.app.Activity
import android.app.AlertDialog
import android.app.Application
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import com.amaury.pointage.v2.V2RuntimeReader
import com.amaury.pointage.v2.V2RuntimeStore
import com.amaury.pointage.v2.model.EventSourceV2
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class,
    shadows = [DailyDiagnosticAuthShadow::class], instrumentedPackages = ["com.google.firebase.auth"])
@LooperMode(LooperMode.Mode.PAUSED)
class GpsPointageUiV2Test {
    private lateinit var auth: FirebaseAuth
    private val listeners = mutableListOf<FirebaseAuth.AuthStateListener>()

    @Before fun setUp() {
        auth = Mockito.mock(FirebaseAuth::class.java)
        DailyDiagnosticAuthShadow.auth = auth
        account("gps-pointage-ui")
        Mockito.doAnswer {
            listeners.add(it.getArgument(0)); null
        }.`when`(auth).addAuthStateListener(Mockito.any(FirebaseAuth.AuthStateListener::class.java))
        Mockito.doAnswer {
            listeners.remove(it.getArgument<FirebaseAuth.AuthStateListener>(0)); null
        }.`when`(auth).removeAuthStateListener(Mockito.any(FirebaseAuth.AuthStateListener::class.java))
    }

    private fun account(uid: String) {
        val user = Mockito.mock(FirebaseUser::class.java)
        Mockito.`when`(user.uid).thenReturn(uid)
        Mockito.`when`(auth.currentUser).thenReturn(user)
    }

    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()

    @Test fun deliveredNativeThemeOpensJourneysAndAccountChangeClosesThem() {
        val controller = Robolectric.buildActivity(GpsSiteJourneyActivityV2::class.java)
        val activity = controller.get()
        activity.setTheme(R.style.Theme_PointageTravail)
        try {
            controller.setup()
            assertFalse(activity.isFinishing)
            assertTrue(descendants(activity.window.decorView).filterIsInstance<Button>()
                .any { it.text.toString() == "Retour" })
            assertEquals(1, listeners.size)
            account("gps-pointage-other")
            listeners.toList().forEach { it.onAuthStateChanged(auth) }
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(activity.isFinishing)
        } finally { controller.pause().stop().destroy() }
        assertTrue(listeners.isEmpty())
    }

    @Test fun accountChangeDismissesReviewAndConfirmationWithoutQualifyingSession() {
        val controller = Robolectric.buildActivity(Activity::class.java)
        val activity = controller.get()
        activity.setTheme(R.style.Theme_PointageTravail)
        controller.setup()
        try {
            val entry = System.currentTimeMillis() - 3_600_000L
            assertTrue(V2RuntimeStore.entry(activity, entry, source = EventSourceV2.GPS))
            assertTrue(V2RuntimeStore.recordObservedDeparture(activity, entry + 1_800_000L))
            val original = V2RuntimeReader.allSessions(activity).sessions.single()
            GpsPointageReviewDialogV2.show(activity)
            shadowOf(Looper.getMainLooper()).idle()
            val review = ShadowDialog.getLatestDialog() as AlertDialog
            descendants(review.window!!.decorView).filterIsInstance<Button>()
                .single { it.text.toString() == "Qualifier cet intervalle" }.performClick()
            val confirmation = ShadowDialog.getLatestDialog() as AlertDialog
            assertNotSame(review, confirmation)
            assertTrue(confirmation.isShowing)
            account("gps-pointage-other")
            listeners.toList().forEach { it.onAuthStateChanged(auth) }
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(review.isShowing)
            assertFalse(confirmation.isShowing)
            assertTrue(listeners.isEmpty())
            account("gps-pointage-ui")
            assertEquals(original, V2RuntimeReader.allSessions(activity).sessions.single())
        } finally {
            ShadowDialog.getShownDialogs().toList().forEach { it.dismiss() }
            controller.pause().stop().destroy()
        }
    }
}
