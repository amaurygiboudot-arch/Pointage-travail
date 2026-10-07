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

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [28])
class HighContrastTextStyleV2Test {
    private val context get() = RuntimeEnvironment.getApplication()

    @Test fun disabledButtonRemainsDistinctAndReadableEvenWhenPressed() {
        val button = Button(context)
        button.setPadding(12, 13, 14, 15)
        HighContrastTextStyleV2.apply(button)
        val normal = button.background.current
        button.isPressed = true
        val pressed = button.background.current
        assertNotSame(normal, pressed)
        assertEquals(Color.BLACK, button.currentTextColor)
        assertEquals(Color.WHITE, (pressed as GradientDrawable).color!!.defaultColor)
        button.isEnabled = false
        val disabled = button.background.current
        assertNotSame(normal, disabled)
        assertNotSame(pressed, disabled)
        assertEquals(Color.LTGRAY, button.currentTextColor)
        val disabledFill = (disabled as GradientDrawable).color!!.defaultColor
        assertEquals(Color.DKGRAY, disabledFill)
        assertTrue(VisualContrastV2.ratio(button.currentTextColor, disabledFill) >= 4.5)
        assertEquals(listOf(12, 13, 14, 15), listOf(button.paddingLeft, button.paddingTop, button.paddingRight, button.paddingBottom))
        button.isEnabled = true
        button.isPressed = false
        assertSame(normal, button.background.current)
    }

    @Test fun focusedEditorHasDistinctSurfaceAndRepeatedApplyKeepsItsState() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup().visible()
        try {
            val activity = controller.get()
            val other = EditText(activity).apply { isFocusableInTouchMode = true }
            val editor = EditText(activity).apply { isFocusableInTouchMode = true }
            activity.setContentView(LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                addView(other)
                addView(editor)
            })
            assertTrue(editor.isAttachedToWindow)
            assertTrue(other.requestFocus())
            assertFalse(editor.isFocused)
            HighContrastTextStyleV2.apply(editor)
            val background = editor.background
            val normal = background.current

            // Let View propagate actual keyboard focus into its drawable state.
            assertTrue(editor.requestFocus())
            assertTrue(editor.isFocused)
            val focused = background.current
            assertNotSame(normal, focused)
            HighContrastTextStyleV2.apply(editor)
            assertTrue(editor.isFocused)
            assertSame(background, editor.background)
            assertSame(focused, editor.background.current)

            assertTrue(other.requestFocus())
            assertFalse(editor.isFocused)
            assertSame(normal, editor.background.current)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun runtimeRestoresOriginalStatefulColorsAndBackgroundWhenContrastIsDisabled() {
        val button = Button(context)
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

}
