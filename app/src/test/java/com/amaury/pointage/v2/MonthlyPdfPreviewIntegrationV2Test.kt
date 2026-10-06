package com.amaury.pointage.v2

import android.app.Application
import android.content.Intent
import android.os.Looper
import android.view.View
import android.widget.Button
import com.amaury.pointage.ConventionCatalog
import com.amaury.pointage.MainActivity
import com.amaury.pointage.PreviewPdfButton
import com.amaury.pointage.R
import com.amaury.pointage.V2MonthlyPdfActivity
import com.amaury.pointage.v2.engine.ConfirmedWorkPdfPolicyV2
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.functions.FirebaseFunctions
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
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.shadows.ShadowToast
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Real XML view, MainActivity listener and runtime-store preflight: the replaced listener bug fails these tests. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class,
    shadows = [PdfIntegrationAuthShadow::class, PdfIntegrationFunctionsShadow::class],
    instrumentedPackages = ["com.google.firebase.auth", "com.google.firebase.functions"])
@LooperMode(LooperMode.Mode.PAUSED)
class MonthlyPdfPreviewIntegrationV2Test {
    @Before fun authenticatedWithoutNetwork() {
        val user = Mockito.mock(FirebaseUser::class.java)
        Mockito.`when`(user.uid).thenReturn("pdf-integration-user")
        val auth = Mockito.mock(FirebaseAuth::class.java)
        Mockito.`when`(auth.currentUser).thenReturn(user)
        PdfIntegrationAuthShadow.auth = auth
        PdfIntegrationFunctionsShadow.calls.set(0)
        ConventionCatalog.initialize(RuntimeEnvironment.getApplication())
    }

    @Test fun actualXmlPreviewButtonHonorsTheSuppliedClickListener() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        try {
            val activity = controller.get()
            val button = activity.findViewById<View>(R.id.generateMonthlyPdfButton)
            assertTrue("The actual activity_main XML must instantiate the custom button", button is PreviewPdfButton)
            var calls = 0
            button.setOnClickListener { calls++ }
            assertTrue(button.performClick())
            assertEquals("A widget must not replace MainActivity's supplied listener", 1, calls)
            assertNull(shadowOf(activity).nextStartedActivity)
            assertEquals(0, PdfIntegrationFunctionsShadow.calls.get())
            assertTrue(activity.cacheDir.walkTopDown().none { it.extension == "pdf" })
        } finally { controller.destroy() }
    }

    @Test fun mainActivityClickUsesTheMonthSelectedInItsRealDialog() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        try {
            val activity = controller.get()
            chooseMonth(activity, 3)
            activity.findViewById<Button>(R.id.generateMonthlyPdfButton).performClick()
            val intent = shadowOf(activity).nextStartedActivity ?: error("The real XML button bypassed secured monthly preflight")
            assertEquals(V2MonthlyPdfActivity::class.java.name, intent.component!!.className)
            val expected = Calendar.getInstance(Locale.FRANCE).apply { add(Calendar.MONTH, -3) }
            assertEquals(expected.get(Calendar.YEAR), intent.getIntExtra("report_year", -1))
            assertEquals(expected.get(Calendar.MONTH), intent.getIntExtra("report_month", -1))
            assertTrue("Preview mode must preserve the user's request", intent.getBooleanExtra("report_preview", false))
            assertEquals(0, PdfIntegrationFunctionsShadow.calls.get())
            assertTrue("Main must not generate a PDF before the monthly guard", activity.cacheDir.walkTopDown().none { it.extension == "pdf" })
        } finally { controller.destroy() }
    }

    @Test fun actualClickRejectsSelectedMonthOpenSessionBeforePdfOrPayment() = rejectUnstableMonth(false)
    @Test fun actualClickRejectsSelectedMonthOpenPauseBeforePdfOrPayment() = rejectUnstableMonth(true)

    private fun rejectUnstableMonth(openPause: Boolean) {
        val mainController = Robolectric.buildActivity(MainActivity::class.java).create()
        var monthlyController: org.robolectric.android.controller.ActivityController<V2MonthlyPdfActivity>? = null
        try {
            val main = mainController.get()
            val arrival = System.currentTimeMillis() - TimeUnit.HOURS.toMillis(2)
            assertTrue(V2RuntimeStore.entry(main, nowMs = arrival))
            if (openPause) assertTrue(V2RuntimeStore.togglePause(main, nowMs = arrival + TimeUnit.MINUTES.toMillis(30), paid = false))
            val read = V2RuntimeReader.allSessions(main)
            assertTrue("Fixture must use a reliable canonical session, not a storage failure", read.reliable)
            assertTrue(read.sessions.isNotEmpty())
            if (openPause) assertTrue(read.sessions.any { session -> session.pauses.any { it.endMs == null } })
            val selected = Calendar.getInstance(Locale.FRANCE).apply { timeInMillis = arrival }
            val now = Calendar.getInstance(Locale.FRANCE)
            val index = (now.get(Calendar.YEAR) - selected.get(Calendar.YEAR)) * 12 + now.get(Calendar.MONTH) - selected.get(Calendar.MONTH)
            chooseMonth(main, index)
            main.findViewById<Button>(R.id.generateMonthlyPdfButton).performClick()
            val route: Intent = shadowOf(main).nextStartedActivity ?: error("XML button bypassed secured monthly routing")
            assertEquals(V2MonthlyPdfActivity::class.java.name, route.component!!.className)
            assertEquals(selected.get(Calendar.YEAR), route.getIntExtra("report_year", -1))
            assertEquals(selected.get(Calendar.MONTH), route.getIntExtra("report_month", -1))
            monthlyController = Robolectric.buildActivity(V2MonthlyPdfActivity::class.java, route).create()
            val monthly = monthlyController.get()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (!monthly.isFinishing && System.nanoTime() < deadline) {
                shadowOf(Looper.getMainLooper()).idle()
                Thread.sleep(10)
            }
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue("Unfinished pointages must terminate before generating or purchasing", monthly.isFinishing)
            assertEquals(ConfirmedWorkPdfPolicyV2.WARNING, ShadowToast.getTextOfLatestToast())
            assertEquals("No backend authorization, offer or payment before preflight", 0, PdfIntegrationFunctionsShadow.calls.get())
            assertTrue("No generated PDF in cache", monthly.cacheDir.walkTopDown().none { it.extension == "pdf" })
            assertTrue("No private draft/archive PDF before closed-session preflight", monthly.filesDir.walkTopDown().none { it.extension == "pdf" })
            assertNull("No preview or destination picker before preflight", shadowOf(monthly).nextStartedActivity)
        } finally { monthlyController?.destroy(); mainController.destroy() }
    }

    private fun chooseMonth(activity: MainActivity, index: Int) {
        activity.findViewById<Button>(R.id.chooseReportMonthButton).performClick()
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        val list = dialog.listView
        list.performItemClick(list.getChildAt(index), index, list.adapter.getItemId(index))
    }
}

/** Robolectric shadows are visible to the real preparation worker, unlike thread-local static mocks. */
@Implements(value = FirebaseAuth::class, isInAndroidSdk = false)
class PdfIntegrationAuthShadow {
    companion object {
        lateinit var auth: FirebaseAuth
        @JvmStatic @Implementation fun getInstance(): FirebaseAuth = auth
        @JvmStatic @Implementation fun getInstance(app: FirebaseApp): FirebaseAuth = auth
    }
}

@Implements(value = FirebaseFunctions::class, isInAndroidSdk = false)
class PdfIntegrationFunctionsShadow {
    companion object {
        val calls = AtomicInteger()
        @JvmStatic @Implementation fun getInstance(region: String): FirebaseFunctions {
            calls.incrementAndGet()
            throw AssertionError("A monthly PDF reached the paid backend before stable-session preflight")
        }
    }
}
