package com.amaury.pointage

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OwnerFeedbackTouchTargetsV2Test {
    private val root = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
        .first { File(it, "app/src/main/java/com/amaury/pointage/OwnerFeedbackActivity.kt").isFile }

    @Test
    fun `owner feedback actions stay at least 48dp on fractional densities`() {
        val source = File(root, "app/src/main/java/com/amaury/pointage/OwnerFeedbackActivity.kt").readText()
        assertFalse(source.contains("dp(46)"))
        assertTrue(source.contains("LinearLayout.LayoutParams(0, dp(48), 1f)"))
        assertTrue(source.contains("ViewGroup.LayoutParams.MATCH_PARENT, dp(48)"))
        assertTrue(source.contains("kotlin.math.ceil(value * resources.displayMetrics.density.toDouble()).toInt()"))
    }
}
