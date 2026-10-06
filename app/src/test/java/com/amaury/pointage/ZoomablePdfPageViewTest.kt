package com.amaury.pointage

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Matrix
import android.view.MotionEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [28])
class ZoomablePdfPageViewTest {
    @Test fun doubleTapZoomsAndNextDoubleTapReturnsToFit() {
        val page = ZoomablePdfPageView(RuntimeEnvironment.getApplication())
        page.setImageBitmap(Bitmap.createBitmap(400, 600, Bitmap.Config.ARGB_8888))
        page.layout(0, 0, 400, 600)
        fun tap(time: Long) {
            listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP).forEachIndexed { index, action ->
                val event = MotionEvent.obtain(time, time + index * 20, action, 200f, 300f, 0)
                page.onTouchEvent(event)
                event.recycle()
            }
        }
        fun scale(): Float {
            val values = FloatArray(9)
            page.imageMatrix.getValues(values)
            return values[Matrix.MSCALE_X]
        }
        tap(1000); tap(1120)
        assertTrue(scale() > 1f)
        tap(2000); tap(2120)
        assertEquals(1f, scale(), 0.001f)
    }

    @Test fun panningCannotExposeBlankSpaceBeyondEitherPageEdge() {
        assertEquals(0f, ZoomablePdfPageView.constrainOffset(50f, 400f, 1000f), 0f)
        assertEquals(-600f, ZoomablePdfPageView.constrainOffset(-800f, 400f, 1000f), 0f)
        assertEquals(-300f, ZoomablePdfPageView.constrainOffset(-300f, 400f, 1000f), 0f)
        assertEquals(50f, ZoomablePdfPageView.constrainOffset(-50f, 400f, 300f), 0f)
    }
}
