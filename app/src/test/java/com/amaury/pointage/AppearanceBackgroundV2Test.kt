package com.amaury.pointage

import android.app.Activity
import android.app.AlertDialog
import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [28])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AppearanceBackgroundV2Test {
    @Before fun resetProfileCache() {
        PersonalizationStoreV2::class.java.getDeclaredField("cachedOwner").apply { isAccessible = true }.set(null, null)
    }

    @Test fun selectedColorIsActuallyDrawnByScrollingCanvasAndChangesImmediately() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val scroll = ThemedBackgroundScrollView(activity)
        activity.setContentView(scroll)
        scroll.measure(View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY))
        scroll.layout(0, 0, 100, 100)
        val prefs = activity.getSharedPreferences(AppThemeCatalog.PREFS, 0)
        for (color in listOf("#101B35", "#E9DCC4")) {
            prefs.edit().putBoolean("custom_bg", true).putString("app_bg", color).putString("mode", "dark").commit()
            AppearanceManager.apply(activity)
            val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
            scroll.draw(Canvas(bitmap))
            assertEquals("The scrolling renderer must not cover the selected color with the theme", Color.parseColor(color), bitmap.getPixel(50, 50))
            assertEquals(Color.parseColor(color), activity.window.statusBarColor)
            bitmap.recycle()
        }
        controller.pause().stop().destroy()
    }

    @Test fun dialogUsesSelectedPaletteWithReadableRoundedControls() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val prefs = activity.getSharedPreferences(AppThemeCatalog.PREFS, 0)
        prefs.edit().putBoolean("custom_bg", true).putString("app_bg", "#101B35").commit()
        val label = TextView(activity).apply { text = "Réglages" }
        val button = Button(activity).apply { text = "Sauvegarder" }
        val dialog = AlertDialog.Builder(activity).setView(LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(label); addView(button)
        }).setPositiveButton("Fermer", null).show()
        AppearanceManager.applyDialog(dialog)
        val fill = ((button.background as android.graphics.drawable.RippleDrawable).getDrawable(0) as GradientDrawable).color!!.defaultColor
        assertTrue(VisualContrastV2.ratio(label.currentTextColor, fill) >= 4.5)
        assertTrue(VisualContrastV2.ratio(button.currentTextColor, fill) >= 4.5)
        assertNotEquals(Color.WHITE, fill)
        prefs.edit().putString("app_bg", "#E9DCC4").commit()
        AppearanceManager.applyDialog(dialog)
        val lightFill = ((button.background as android.graphics.drawable.RippleDrawable).getDrawable(0) as GradientDrawable).color!!.defaultColor
        assertNotEquals(fill, lightFill)
        assertTrue(VisualContrastV2.ratio(button.currentTextColor, lightFill) >= 4.5)
        dialog.dismiss()
        controller.pause().stop().destroy()
    }

    @Test fun frameStylingRespectsCustomLightColorEvenInDarkMode() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        activity.getSharedPreferences(AppThemeCatalog.PREFS, 0).edit()
            .putBoolean("custom_bg", true).putString("app_bg", "#E9DCC4").putString("mode", "dark").commit()
        val label = TextView(activity).apply { text = "Libellé" }
        activity.setContentView(label)
        ThemeFrameStyler.apply(label)
        assertTrue(VisualContrastV2.ratio(label.currentTextColor, AppearanceManager.backgroundColor(activity)) >= 4.5)
        controller.pause().stop().destroy()
    }
}
