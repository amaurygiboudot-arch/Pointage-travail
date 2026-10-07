package com.amaury.pointage

import android.app.Activity
import android.app.AlertDialog
import android.app.Application
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.InputDevice
import android.view.View
import android.widget.LinearLayout
import android.widget.Button
import android.content.Context
import android.graphics.Color
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowViewRootImpl
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(manifest = Config.NONE, application = Application::class, sdk = [28])
class PersonalizationRuntimeTouchV2Test {
    private fun tap(target: View, x: Float, y: Float) {
        val now = SystemClock.uptimeMillis()
        fun dispatch(action: Int) {
            val event = MotionEvent.obtain(now, SystemClock.uptimeMillis(), action, x, y, 0).apply {
                source = InputDevice.SOURCE_TOUCHSCREEN
            }
            try { target.dispatchTouchEvent(event) } finally { event.recycle() }
        }
        dispatch(MotionEvent.ACTION_DOWN)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(40))
        dispatch(MotionEvent.ACTION_UP)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
    }

    @Test fun trackedSizeAndModeDialogsAcceptShortRowTapsAndDismiss() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup().visible()
        try {
            val activity = controller.get()
            PersonalizationStoreV2.save(activity, PersonalizationProfileV2())
            for (tracked in listOf(false, true)) {
            for (labels in listOf(arrayOf("100 %", "125 %", "150 %"), arrayOf("Automatique", "Jour", "Nuit"))) {
                var selected = -1
                val dialog = AlertDialog.Builder(activity).setItems(labels) { _, index -> selected = index }.create()
                dialog.show()
                if (tracked) PersonalizationRuntimeV2.track(dialog)
                // PAUSED mode queues the initial traversal: show() alone has not
                // attached the decor yet. Flush it before asking for its ViewRoot.
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200))
                val decor = dialog.window!!.decorView
                assertTrue("Dialog decor must attach after its first traversal", decor.isAttachedToWindow)
                val viewRoot = View::class.java.getDeclaredMethod("getViewRootImpl").invoke(decor)
                assertNotNull("An attached dialog must have its own ViewRoot", viewRoot)
                // Use the same public Robolectric hooks as ActivityController.visible()
                // and windowFocusChanged(), instead of an Android-version-specific call.
                val shadowRoot = Shadow.extract<ShadowViewRootImpl>(viewRoot)
                shadowRoot.callDispatchResized()
                shadowOf(Looper.getMainLooper()).idle()
                shadowRoot.callWindowFocusChanged(true)
                shadowOf(Looper.getMainLooper()).idle()
                val list = dialog.listView
                list.measure(View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.AT_MOST))
                list.layout(0, 0, list.measuredWidth, list.measuredHeight)
                if (tracked) PersonalizationRuntimeV2.apply(decor)
                assertTrue("List must be attached", list.isAttachedToWindow)
                assertTrue("List must own window focus before dispatching touch", list.hasWindowFocus())
                val secondRow = list.getChildAt(1)
                assertNotNull("The actual dialog must have laid out its rows", secondRow)
                assertFalse("A reader shortcut must not take ownership of the row gesture", secondRow.isLongClickable)
                // Deliberately dispatch touch events through ListView: performItemClick
                // would bypass the child interception that broke these dialogs.
                tap(list, secondRow.width / 2f, secondRow.top + secondRow.height / 2f)
                assertEquals("tracked=$tracked: ${labels.joinToString()}", 1, selected)
                assertFalse("setItems must dismiss after choosing a row", dialog.isShowing)
            }
            }
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun readerDoesNotStealTapFromClickableParent() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup().visible()
        try {
            val activity = controller.get()
            var clicks = 0
            val label = TextView(activity).apply { text = "Choisir une option" }
            val parent = LinearLayout(activity).apply {
                addView(label, LinearLayout.LayoutParams(300, 100))
                setOnClickListener { clicks++ }
            }
            activity.setContentView(parent)
            parent.measure(View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY))
            parent.layout(0, 0, 300, 100)
            PersonalizationRuntimeV2.apply(parent)
            assertFalse(label.isLongClickable)
            tap(parent, 50f, 50f)
            assertEquals(1, clicks)
        } finally {
            controller.pause().stop().destroy()
        }
    }
    @Test fun repeatedDialogLayoutsPreserveProtectedDrawablesAndStyleLateRows() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup().visible()
        val activity = controller.get()
        val appearance = activity.getSharedPreferences(AppThemeCatalog.PREFS, Context.MODE_PRIVATE)
        try {
            for (highContrast in listOf(false, true)) {
                PersonalizationStoreV2.save(activity, PersonalizationProfileV2(highContrast = highContrast))
                appearance.edit().putBoolean("custom_image_bg", true).commit()
                val label = TextView(activity).apply { text = "Texte à lire" }
                val button = Button(activity).apply { text = "Action" }
                val body = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(label); addView(button)
                }
                val dialog = AlertDialog.Builder(activity).setView(body).create()
                dialog.show()
                PersonalizationRuntimeV2.track(dialog)
                val root = dialog.window!!.decorView
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200))
                val labelBackground = label.background
                val labelColors = label.textColors
                val buttonBackground = button.background
                val windowBackground = root.background
                repeat(3) {
                    root.viewTreeObserver.dispatchOnGlobalLayout()
                    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(160))
                    assertSame("No restore/re-clone cycle for a protected label", labelBackground, label.background)
                    assertSame("Dialog palette must not overwrite protected colors", labelColors, label.textColors)
                    assertSame("No restore/re-clone cycle for a button", buttonBackground, button.background)
                    assertSame(windowBackground, root.background)
                }
                val late = TextView(activity).apply { text = "Nouvelle ligne" }
                body.addView(late)
                root.viewTreeObserver.dispatchOnGlobalLayout()
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(160))
                assertEquals(highContrast, PersonalizationRuntimeV2.isProtectionApplied(late))
                assertNull("Opaque dialogs must not add photo strips", late.background)
                assertEquals(Color.WHITE, late.currentTextColor)
                dialog.dismiss()
            }
        } finally {
            appearance.edit().remove("custom_image_bg").commit()
            PersonalizationStoreV2.save(activity, PersonalizationProfileV2())
            controller.pause().stop().destroy()
        }
    }

}
