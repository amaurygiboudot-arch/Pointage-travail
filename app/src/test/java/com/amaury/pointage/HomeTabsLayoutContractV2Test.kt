package com.amaury.pointage

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Source-boundary regressions; these do not replace UI/frame tests on devices. */
class HomeTabsLayoutContractV2Test {
    private val root = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
        .first { File(it, "app/src/main/java/com/amaury/pointage/MainActivity.kt").isFile }
    private fun source(path: String) = File(root, path).readText()
    private val main get() = source("app/src/main/java/com/amaury/pointage/MainActivity.kt")
    private val home get() = source("ios/HPTravail/HPTravail/CelestialV2/CelestialHomeView.swift")
    private val fade get() = source("ios/HPTravail/HPTravail/CelestialV2/Visual/HomeTabBarFadeV2.swift")

    @Test fun androidKeepsTheNavigationSlotDuringTheFade() {
        val hidden = main.substringAfter("private val hideHomeTabsRunnable = Runnable {")
            .substringBefore("private val selectedReportMonth")
        assertTrue(hidden.contains(".alpha(0f)"))
        assertTrue(hidden.contains("navigationTabs.visibility = View.INVISIBLE"))
        assertFalse(main.contains("navigationTabs.visibility = View.GONE"))
    }

    @Test fun androidRetainsRevealAndExitCancellation() {
        for (name in listOf("revealHomeTabsAndScheduleHide", "cancelHomeTabAutoHideAndShowTabs")) {
            val block = main.substringAfter("private fun $name() {").substringBefore("\n    private fun ")
            assertTrue(block.contains("removeCallbacks(hideHomeTabsRunnable)"))
            assertTrue(block.contains("navigationTabs.animate().cancel()"))
            assertTrue(block.contains("navigationTabs.visibility = View.VISIBLE"))
        }
    }

    @Test fun iosFadesInsteadOfRemovingTheNativeBar() {
        assertTrue(home.contains(".toolbar(.visible, for: .tabBar)"))
        assertTrue(home.contains(".background(HomeTabBarFadeV2(isVisible: tabBarVisible))"))
        assertFalse(home.contains(".toolbar(tabBarVisible ? .visible : .hidden"))
        assertTrue(fade.contains("bar.alpha = targetAlpha"))
        for (mutation in listOf("bar.isHidden =", "bar.frame =", "bar.bounds =", "additionalSafeAreaInsets =")) {
            assertFalse("Layout mutation: $mutation", fade.contains(mutation))
        }
    }

    @Test fun iosRestoresNavigationAndDisablesInvisibleControls() {
        assertTrue(fade.contains("bar.isUserInteractionEnabled = requestedVisible && originalInteraction"))
        assertTrue(fade.contains("bar.accessibilityElementsHidden = !requestedVisible || originalAccessibilityHidden"))
        assertTrue(fade.contains("UIAccessibility.isReduceMotionEnabled"))
        assertTrue(fade.contains("override func viewWillDisappear"))
        assertTrue(fade.contains("static func dismantleUIViewController"))
        assertTrue(fade.contains("controller.stopAndRestore()"))
        assertTrue(fade.contains("bar.alpha = originalAlpha"))
    }
}
