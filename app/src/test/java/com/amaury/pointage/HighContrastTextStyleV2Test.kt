package com.amaury.pointage

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [28])
class HighContrastTextStyleV2Test {
    private val context get() = RuntimeEnvironment.getApplication()

    @Test fun disabledButtonRemainsDistinctAndReadableEvenWhenPressed() {
        val button = Button(context).apply {
            background = GradientDrawable().apply { cornerRadius = 24f; setColor(Color.TRANSPARENT) }
        }
        button.setPadding(12, 13, 14, 15)
        HighContrastTextStyleV2.apply(button)
        val background = button.background as GradientDrawable
        assertEquals(Color.BLACK, background.color!!.getColorForState(button.drawableState, 0))
        button.isPressed = true
        assertEquals(Color.BLACK, button.currentTextColor)
        assertEquals(Color.WHITE, background.color!!.getColorForState(button.drawableState, 0))
        button.isEnabled = false
        assertEquals(Color.LTGRAY, button.currentTextColor)
        val disabledFill = background.color!!.getColorForState(button.drawableState, 0)
        assertEquals(Color.DKGRAY, disabledFill)
        assertTrue(VisualContrastV2.ratio(button.currentTextColor, disabledFill) >= 4.5)
        assertEquals(24f, background.cornerRadius, 0f)
        assertEquals(listOf(12, 13, 14, 15), listOf(button.paddingLeft, button.paddingTop, button.paddingRight, button.paddingBottom))
        button.isEnabled = true
        button.isPressed = false
        assertSame(background, button.background)
        assertEquals(Color.BLACK, background.color!!.getColorForState(button.drawableState, 0))
    }

    @Test fun focusedEditorHasDistinctSurfaceAndRepeatedApplyKeepsItsState() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup().visible()
        try {
            val activity = controller.get()
            val other = EditText(activity).apply { isFocusableInTouchMode = true }
            val editor = EditText(activity).apply {
                isFocusableInTouchMode = true
                background = GradientDrawable().apply { cornerRadius = 18f; setColor(Color.TRANSPARENT) }
            }
            activity.setContentView(LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                addView(other)
                addView(editor)
            })
            assertTrue(editor.isAttachedToWindow)
            assertTrue(other.requestFocus())
            assertFalse(editor.isFocused)
            HighContrastTextStyleV2.apply(editor)
            val background = editor.background as GradientDrawable
            val normal = background.color!!.getColorForState(editor.drawableState, 0)

            // Let View propagate actual keyboard focus into its drawable state.
            assertTrue(editor.requestFocus())
            assertTrue(editor.isFocused)
            val focused = background.color!!.getColorForState(editor.drawableState, 0)
            assertNotEquals(normal, focused)
            assertTrue(VisualContrastV2.ratio(editor.currentTextColor, focused) >= 4.5)
            HighContrastTextStyleV2.apply(editor)
            assertTrue(editor.isFocused)
            assertSame(background, editor.background)
            assertEquals(focused, background.color!!.getColorForState(editor.drawableState, 0))

            assertTrue(other.requestFocus())
            assertFalse(editor.isFocused)
            assertEquals(normal, background.color!!.getColorForState(editor.drawableState, 0))
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun runtimeRestoresOriginalStatefulColorsAndBackgroundWhenContrastIsDisabled() {
        val button = Button(context).apply {
            background = GradientDrawable().apply { cornerRadius = 24f; setColor(Color.TRANSPARENT) }
        }
        val originalBackground = button.background
        val originalColors = button.textColors
        val originalHints = button.hintTextColors
        val originalTint = button.backgroundTintList
        try {
            assertTrue(PersonalizationStoreV2.save(context, PersonalizationProfileV2(highContrast = true)))
            PersonalizationRuntimeV2.apply(button)
            assertNotSame(originalBackground, button.background)
            assertTrue(PersonalizationStoreV2.save(context, PersonalizationProfileV2()))
            PersonalizationRuntimeV2.apply(button)
            assertSame(originalBackground, button.background)
            assertSame(originalColors, button.textColors)
            assertSame(originalHints, button.hintTextColors)
            assertEquals(originalTint, button.backgroundTintList)
        } finally {
            PersonalizationStoreV2.save(context, PersonalizationProfileV2())
        }
    }

    @Test fun imagePreservesTabGeometryTintAndSelectedPressedDisabledColors() {
        val appearance = context.getSharedPreferences(AppThemeCatalog.PREFS, Context.MODE_PRIVATE)
        val tab = TextView(context).apply {
            isClickable = true
            isFocusable = true
            background = GradientDrawable().apply { cornerRadius = 24f; setColor(Color.DKGRAY) }
            backgroundTintList = ColorStateList.valueOf(Color.BLUE)
            setPadding(11, 12, 13, 14)
            setTextColor(ColorStateList(
                arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf(android.R.attr.state_pressed),
                    intArrayOf(android.R.attr.state_selected), intArrayOf()),
                intArrayOf(Color.GRAY, Color.CYAN, Color.YELLOW, Color.WHITE)))
        }
        val background = tab.background
        val tint = tab.backgroundTintList
        val colors = tab.textColors
        try {
            PersonalizationStoreV2.save(context, PersonalizationProfileV2())
            appearance.edit().putBoolean("custom_image_bg", true).commit()
            repeat(2) { PersonalizationRuntimeV2.apply(tab) }
            assertSame(background, tab.background)
            assertSame(tint, tab.backgroundTintList)
            assertSame(colors, tab.textColors)
            assertEquals(24f, (tab.background as GradientDrawable).cornerRadius, 0f)
            assertEquals(listOf(11, 12, 13, 14), listOf(tab.paddingLeft, tab.paddingTop, tab.paddingRight, tab.paddingBottom))
            tab.isSelected = true
            assertEquals(Color.YELLOW, tab.currentTextColor)
            tab.isPressed = true
            assertEquals(Color.CYAN, tab.currentTextColor)
            tab.isEnabled = false
            assertEquals(Color.GRAY, tab.currentTextColor)

            // Explicit high contrast still works over an image, and switching it off
            // restores the tab even while the image remains enabled.
            PersonalizationStoreV2.save(context, PersonalizationProfileV2(highContrast = true))
            PersonalizationRuntimeV2.apply(tab)
            assertNotSame(background, tab.background)
            PersonalizationStoreV2.save(context, PersonalizationProfileV2())
            PersonalizationRuntimeV2.apply(tab)
            assertSame(background, tab.background)
            assertSame(colors, tab.textColors)
            assertSame(tint, tab.backgroundTintList)
        } finally {
            appearance.edit().remove("custom_image_bg").commit()
            PersonalizationStoreV2.save(context, PersonalizationProfileV2())
        }
    }

    @Test fun imageProtectsBareLabelsButPreservesThemedLabelsAndTransparentClickableTabs() {
        val appearance = context.getSharedPreferences(AppThemeCatalog.PREFS, Context.MODE_PRIVATE)
        val bare = TextView(context).apply { setTextColor(Color.BLUE) }
        val originalColors = bare.textColors
        val themed = TextView(context).apply {
            background = GradientDrawable().apply { cornerRadius = 18f; setColor(Color.DKGRAY) }
            setTextColor(Color.YELLOW)
        }
        val themedBackground = themed.background
        // Navigation XML uses clickable TextViews without individual backgrounds.
        val tab = TextView(context).apply { isClickable = true; setTextColor(Color.YELLOW) }
        val root = LinearLayout(context).apply { addView(bare); addView(themed); addView(tab) }
        try {
            PersonalizationStoreV2.save(context, PersonalizationProfileV2())
            appearance.edit().putBoolean("custom_image_bg", true).commit()
            repeat(2) { PersonalizationRuntimeV2.apply(root) }
            assertEquals(Color.BLACK, (bare.background as android.graphics.drawable.ColorDrawable).color)
            assertEquals(Color.WHITE, bare.currentTextColor)
            assertSame(themedBackground, themed.background)
            assertEquals(Color.YELLOW, themed.currentTextColor)
            assertNull(tab.background)
            assertEquals(Color.YELLOW, tab.currentTextColor)
            appearance.edit().putBoolean("custom_image_bg", false).commit()
            PersonalizationRuntimeV2.apply(root)
            assertNull(bare.background)
            assertSame(originalColors, bare.textColors)
        } finally {
            appearance.edit().remove("custom_image_bg").commit()
            PersonalizationStoreV2.save(context, PersonalizationProfileV2())
        }
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun highContrastKeepsRoundedSurfaceAndTransparentNavigationShape() {
        val original = GradientDrawable().apply { cornerRadius = 24f; setColor(Color.TRANSPARENT) }
        val button = Button(context).apply { background = original }
        HighContrastTextStyleV2.apply(button)
        val bitmap = android.graphics.Bitmap.createBitmap(100, 60, android.graphics.Bitmap.Config.ARGB_8888)
        button.background.setBounds(0, 0, 100, 60)
        button.background.draw(android.graphics.Canvas(bitmap))
        assertEquals("Rounded corner must not become an opaque rectangle", 0, Color.alpha(bitmap.getPixel(0, 0)))
        assertEquals("Transparent themed center becomes readable", Color.BLACK, bitmap.getPixel(50, 30))
        assertEquals("Restoration baseline must remain untouched", Color.TRANSPARENT, original.color!!.defaultColor)
        val tab = TextView(context).apply { isClickable = true; isFocusable = true }
        HighContrastTextStyleV2.apply(tab)
        assertNull(tab.background)
        val normal = tab.currentTextColor
        tab.isSelected = true
        assertNotEquals(normal, tab.currentTextColor)
        assertTrue(VisualContrastV2.ratio(tab.currentTextColor, Color.BLACK) >= 4.5)
        tab.isPressed = true
        assertTrue(VisualContrastV2.ratio(tab.currentTextColor, Color.BLACK) >= 4.5)
        tab.isEnabled = false
        assertEquals(Color.LTGRAY, tab.currentTextColor)
    }

    @Test fun imageGivesTransparentPanelButtonAReadableFillWithoutLosingItsCorners() {
        val appearance = context.getSharedPreferences(AppThemeCatalog.PREFS, Context.MODE_PRIVATE)
        val original = GradientDrawable().apply { cornerRadius = 24f; setColor(Color.TRANSPARENT) }
        val button = Button(context).apply { background = original; text = "Valider" }
        try {
            PersonalizationStoreV2.save(context, PersonalizationProfileV2())
            appearance.edit().putBoolean("custom_image_bg", true).commit()
            repeat(2) { PersonalizationRuntimeV2.apply(button) }
            val filled = button.background as GradientDrawable
            assertEquals(24f, filled.cornerRadius, 0f)
            val fill = filled.color!!.getColorForState(button.drawableState, 0)
            assertEquals(255, Color.alpha(fill))
            assertTrue(VisualContrastV2.ratio(button.currentTextColor, fill) >= 4.5)
            assertEquals(Color.TRANSPARENT, original.color!!.defaultColor)
            appearance.edit().putBoolean("custom_image_bg", false).commit()
            PersonalizationRuntimeV2.apply(button)
            assertSame(original, button.background)
        } finally {
            appearance.edit().remove("custom_image_bg").commit()
            PersonalizationStoreV2.save(context, PersonalizationProfileV2())
        }
    }

    @Test fun photoDoesNotAddBlackStripsInsideOpaqueDialog() {
        val appearance = context.getSharedPreferences(AppThemeCatalog.PREFS, Context.MODE_PRIVATE)
        val label = TextView(context).apply { text = "Explication" }
        val toggle = android.widget.Switch(context).apply { text = "Contraste" }
        val root = LinearLayout(context).apply { addView(label); addView(toggle) }
        val originalToggle = toggle.background
        try {
            appearance.edit().putBoolean("custom_image_bg", true).commit()
            PersonalizationStoreV2.save(context, PersonalizationProfileV2())
            PersonalizationRuntimeV2.apply(root, opaqueSurface = true)
            assertNull(label.background)
            assertSame(originalToggle, toggle.background)
            PersonalizationStoreV2.save(context, PersonalizationProfileV2(highContrast = true))
            PersonalizationRuntimeV2.apply(root, opaqueSurface = true)
            assertNull(label.background)
            assertSame(originalToggle, toggle.background)
            assertTrue(VisualContrastV2.ratio(label.currentTextColor, Color.BLACK) >= 7.0)
        } finally {
            appearance.edit().remove("custom_image_bg").commit()
            PersonalizationStoreV2.save(context, PersonalizationProfileV2())
        }
    }

    @Test fun rippleContrastFillsContentWithoutTintingBorderOrLosingRounding() {
        val shape = GradientDrawable().apply {
            cornerRadius = 24f
            setColor(Color.TRANSPARENT)
            setStroke(2, Color.YELLOW)
        }
        val button = Button(context).apply {
            background = android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(Color.WHITE), shape, null)
        }
        HighContrastTextStyleV2.apply(button)
        assertNull(button.backgroundTintList)
        val ripple = button.background as android.graphics.drawable.RippleDrawable
        val fill = ripple.getDrawable(0) as GradientDrawable
        assertEquals(24f, fill.cornerRadius, 0f)
        assertEquals(Color.BLACK, fill.color!!.defaultColor)
        HighContrastTextStyleV2.apply(button)
        assertSame(ripple, button.background)
        assertEquals(Color.TRANSPARENT, shape.color!!.defaultColor)
    }

}
