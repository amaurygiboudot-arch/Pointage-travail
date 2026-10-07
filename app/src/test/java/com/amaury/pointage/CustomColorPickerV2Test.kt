package com.amaury.pointage

import android.app.Activity
import android.app.AlertDialog
import android.app.Application
import android.graphics.Color
import android.widget.EditText
import android.widget.SeekBar
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [28])
class CustomColorPickerV2Test {
    @Test fun validatesOpaqueRgbWithoutSilentlyAcceptingAlphaOrInvalidCodes() {
        assertEquals(Color.rgb(42, 128, 212), CustomColorPickerV2.parseHex(" #2a80d4 "))
        assertEquals("#2A80D4", CustomColorPickerV2.hex(CustomColorPickerV2.parseHex("2a80d4")!!))
        listOf("", "#123", "#00112233", "#GG1122", "red", "##112233").forEach {
            assertNull(CustomColorPickerV2.parseHex(it))
        }
    }

    @Test fun slidersSynchronizeHexButCancelDoesNotSave() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup().visible()
        try {
            var saved: String? = null
            val dialog = CustomColorPickerV2.show(controller.get(), "Couleur", "#123456") { saved = it }
            val root = dialog.window!!.decorView
            root.findViewWithTag<SeekBar>("custom_color_Rouge").progress = 255
            assertEquals("#FF3456", root.findViewWithTag<EditText>("custom_color_hex").text.toString())
            assertNull(saved)
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
            assertNull(saved)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun invalidInputKeepsDialogOpenThenValidInputAppliesExactlyOnce() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup().visible()
        try {
            val saved = mutableListOf<String>()
            val dialog = CustomColorPickerV2.show(controller.get(), "Couleur") { saved.add(it) }
            val root = dialog.window!!.decorView
            val input = root.findViewWithTag<EditText>("custom_color_hex")
            input.setText("#12")
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            assertTrue(dialog.isShowing)
            assertNotNull(input.error)
            assertTrue(saved.isEmpty())
            input.setText("#00aaff")
            assertEquals(170, root.findViewWithTag<SeekBar>("custom_color_Vert").progress)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            assertEquals(listOf("#00AAFF"), saved)
            assertFalse(dialog.isShowing)
        } finally { controller.pause().stop().destroy() }
    }
}
