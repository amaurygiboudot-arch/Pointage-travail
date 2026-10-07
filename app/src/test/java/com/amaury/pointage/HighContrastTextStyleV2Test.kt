package com.amaury.pointage

import android.app.Application
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.widget.Button
import android.widget.EditText
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
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
        val editor = EditText(context)
        HighContrastTextStyleV2.apply(editor)
        val background = editor.background
        background.state = intArrayOf(android.R.attr.state_enabled)
        val normal = background.current
        background.state = intArrayOf(android.R.attr.state_enabled, android.R.attr.state_focused)
        val focused = background.current
        assertNotSame(normal, focused)
        HighContrastTextStyleV2.apply(editor)
        assertSame(background, editor.background)
        assertSame(focused, editor.background.current)
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
}
