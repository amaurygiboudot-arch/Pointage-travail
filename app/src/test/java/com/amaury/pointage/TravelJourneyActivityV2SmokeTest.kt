package com.amaury.pointage

import android.app.Activity
import android.app.AlertDialog
import android.app.Application
import android.content.Context
import android.content.Intent
import android.location.Location
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.widget.Button
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
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowAlertDialog
import java.util.UUID

/** UI and URL simulations with Robolectric. No physical GPS or Google Maps session. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class,
    shadows = [TravelActivityAuthShadowV2::class],
    instrumentedPackages = ["com.google.firebase.auth"])
class TravelJourneyActivityV2SmokeTest {
    private lateinit var user: FirebaseUser

    @Before
    fun setup() {
        val auth = Mockito.mock(FirebaseAuth::class.java)
        user = Mockito.mock(FirebaseUser::class.java)
        Mockito.`when`(user.uid).thenReturn("travel-ui-" + UUID.randomUUID())
        Mockito.`when`(auth.currentUser).thenReturn(user)
        TravelActivityAuthShadowV2.auth = auth
        RuntimeEnvironment.getApplication().getSharedPreferences(AppThemeCatalog.PREFS, Context.MODE_PRIVATE)
            .edit().putString("mode", "light").commit()
    }

    private fun button(root: View, caption: String): Button? {
        if (root is Button && root.text.toString() == caption) return root
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                val found = button(root.getChildAt(i), caption)
                if (found != null) return found
            }
        }
        return null
    }

    private fun beginActivity() = Robolectric.buildActivity(TravelJourneyActivityV2::class.java).setup()

    @Test
    fun buttonsAreVisibleWithNoActiveTrip() {
        val controller = beginActivity()
        try {
            val root = controller.get().window.decorView
            assertTrue(requireNotNull(button(root, "DÉMARRER UN DÉPLACEMENT")).isEnabled)
            assertFalse(requireNotNull(button(root, "TERMINER LE DÉPLACEMENT")).isEnabled)
            assertTrue(requireNotNull(button(root, "VOIR LE TRAJET DANS MAPS")).isEnabled)
            assertNotNull(button(root, "AUTORISER LA POSITION GPS"))
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun mapsNeedsUserConfirmationBeforeExternalIntent() {
        val controller = beginActivity()
        try {
            val activity = controller.get()
            val open = requireNotNull(button(activity.window.decorView, "VOIR LE TRAJET DANS MAPS"))
            open.performClick()
            val dialog = requireNotNull(ShadowAlertDialog.getLatestAlertDialog())
            assertNull(Shadows.shadowOf(activity).nextStartedActivity)
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
            assertNull(Shadows.shadowOf(activity).nextStartedActivity)
            open.performClick()
            requireNotNull(ShadowAlertDialog.getLatestAlertDialog())
                .getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            val intent = requireNotNull(Shadows.shadowOf(activity).nextStartedActivity)
            assertEquals(Intent.ACTION_VIEW, intent.action)
            assertEquals("www.google.com", intent.data?.host)
            assertEquals("1", intent.data?.getQueryParameter("api"))
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun mapsCoordinatesAreUriEncodedAndStillDecodable() {
        val controller = beginActivity()
        try {
            val atMs = 1_800_000_000_000L
            val trip = TravelJourneyV2("sample", atMs, atMs + 60_000, "employer",
                TravelFixV2(46.78, -1.75, 10.0, atMs),
                TravelFixV2(46.79, -1.74, 10.0, atMs + 60_000))
            val method = Activity::class.java // Find the private real production builder.
            val urlMethod = controller.get().javaClass.getDeclaredMethod("mapsUrl", TravelJourneyV2::class.java)
                .apply { isAccessible = true }
            val raw = urlMethod.invoke(controller.get(), trip) as String
            assertTrue(raw.contains("%2C"))
            val decoded = Uri.parse(raw)
            assertEquals("46.78,-1.75", decoded.getQueryParameter("origin"))
            assertEquals("46.79,-1.74", decoded.getQueryParameter("destination"))
            assertEquals("driving", decoded.getQueryParameter("travelmode"))
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun staleGpsAndOtherAccountCannotChangeRecordedJourney() {
        val controller = beginActivity()
        try {
            val activity = controller.get()
            val atMs = 1_800_000_000_000L
            val id = requireNotNull(TravelJourneyStoreV2.begin(activity, atMs, "employer"))
            val owner = com.amaury.pointage.v2.V2RuntimeAccountScope.currentScope()!!
            val method = activity.javaClass.getDeclaredMethod("recordLocationIfFresh",
                String::class.java, java.lang.Boolean.TYPE, java.lang.Long.TYPE,
                String::class.java, Location::class.java).apply { isAccessible = true }
            fun record(at: Long, claimedOwner: String): Boolean {
                val loc = Location("simulated").apply {
                    latitude = 46.78; longitude = -1.75; accuracy = 12f; time = at
                }
                return method.invoke(activity, id, true, atMs, claimedOwner, loc) as Boolean
            }
            assertFalse(record(atMs + 200_000, owner))
            assertFalse(record(atMs + 1_000, "uid:another-account"))
            assertNull(requireNotNull(TravelJourneyStoreV2.read(activity)).single().departure)
            assertTrue(record(atMs + 1_000, owner))
            assertNotNull(requireNotNull(TravelJourneyStoreV2.read(activity)).single().departure)
        } finally {
            controller.pause().stop().destroy()
        }
    }
}

@Implements(value = FirebaseAuth::class, isInAndroidSdk = false)
class TravelActivityAuthShadowV2 {
    companion object {
        lateinit var auth: FirebaseAuth
        @JvmStatic @Implementation fun getInstance(): FirebaseAuth = auth
    }
}
