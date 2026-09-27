package com.amaury.pointage

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsTouchTargetContractV2Test {
    private val root = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
        .first { File(it, "app/src/main/java/com/amaury/pointage/PointageApplication.kt").isFile }

    private fun source(path: String) = File(root, path).readText()

    @Test
    fun `every explicit settings touch target is at least 48dp and conversion rounds up`() {
        val settings = source("app/src/main/java/com/amaury/pointage/PointageApplication.kt")
        val luxury = source("app/src/main/java/com/amaury/pointage/LuxuryUiInstaller.kt")
        val relief = source("app/src/main/java/com/amaury/pointage/ButtonReliefInstaller.kt")

        val requiredTargets = listOf(
            settings to "ViewGroup.LayoutParams.MATCH_PARENT,\n                dp(activity, 48)",
            settings to "ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 48)",
            luxury to "wrapper.addView(button, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 48))",
            luxury to "height = dp(activity, 48)",
            relief to "ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 48)"
        )
        requiredTargets.forEachIndexed { index, (source, expected) ->
            assertTrue("Missing >=48dp target #$index", source.contains(expected))
        }

        val ceilContract = "kotlin.math.ceil(value *"
        listOf(settings, luxury, relief).forEachIndexed { index, source ->
            assertTrue("dp conversion #$index must round upward", source.contains(ceilContract))
        }

        assertTrue(settings.contains("contentDescription = \"Retour à Aujourd’hui\""))
    }
}
