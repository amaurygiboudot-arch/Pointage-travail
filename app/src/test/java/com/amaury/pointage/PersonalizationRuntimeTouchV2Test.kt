package com.amaury.pointage

import android.app.Activity
import android.app.AlertDialog
import android.app.Application
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [28])
class PersonalizationRuntimeTouchV2Test {
    private fun tap(target: View, x: Float, y: Float) {
        val now = SystemClock.uptimeMillis()
        for ((action, time) in listOf(MotionEvent.ACTION_DOWN to now, MotionEvent.ACTION_UP to now + 40)) {
            val event = MotionEvent.obtain(now, time, action, x, y, 0)
            try { target.dispatchTouchEvent(event) } finally { event.recycle() }
        }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
    }

    @Test fun trackedSizeAndModeDialogsAcceptShortRowTapsAndDismiss() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup().visible()
        try {
            val activity = controller.get()
            PersonalizationStoreV2.save(activity, PersonalizationProfileV2())
            for (labels in listOf(arrayOf("100 %", "125 %", "150 %"), arrayOf("Automatique", "Jour", "Nuit"))) {
                var selected = -1
                val dialog = AlertDialog.Builder(activity).setItems(labels) { _, index -> selected = index }.create()
                dialog.show()
                PersonalizationRuntimeV2.track(dialog)
                val list = dialog.listView
                list.measure(View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.AT_MOST))
                list.layout(0, 0, list.measuredWidth, list.measuredHeight)
                PersonalizationRuntimeV2.apply(dialog.window!!.decorView)
                val secondRow = list.getChildAt(1)
                assertNotNull("The actual dialog must have laid out its rows", secondRow)
                assertFalse("A reader shortcut must not take ownership of the row gesture", secondRow.isLongClickable)
                // Deliberately dispatch touch events through ListView: performItemClick
                // would bypass the child interception that broke these dialogs.
                tap(list, secondRow.width / 2f, secondRow.top + secondRow.height / 2f)
                assertEquals(labels.joinToString(), 1, selected)
                assertFalse("setItems must dismiss after choosing a row", dialog.isShowing)
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
}
