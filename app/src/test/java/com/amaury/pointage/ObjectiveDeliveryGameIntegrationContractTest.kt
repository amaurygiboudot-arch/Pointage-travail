package com.amaury.pointage

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class ObjectiveDeliveryGameIntegrationContractTest {
    private val root = generateSequence(File(System.getProperty("user.dir") ?: ".")) { it.parentFile }
        .first { File(it, "app/src/main/AndroidManifest.xml").isFile }

    private fun source(path: String): String = File(root, path).readText()

    @Test
    fun `game entry stays in Aide extras and activity is private`() {
        val layout = source("app/src/main/res/layout/activity_main.xml")
        val organizer = source(
            "app/src/main/java/com/amaury/pointage/SettingsV2SectionOrganizer.kt"
        )
        val manifest = source("app/src/main/AndroidManifest.xml")

        assertTrue(layout.contains("ObjectiveDeliveryGameButtonView"))
        assertTrue(organizer.contains("view is ObjectiveDeliveryGameButtonView"))
        assertTrue(
            manifest.contains(
                "<activity android:name=\".ObjectiveDeliveryGameActivity\" android:exported=\"false\" />"
            )
        )
    }

    @Test
    fun `game cloud saves are private user data`() {
        val rules = source("firestore.rules")

        assertTrue(rules.contains("match /objective_delivery/{campaignId}"))
        assertTrue(rules.contains("request.auth.uid == userId"))
        assertTrue(rules.contains("'schemaVersion', 'campaignId', 'companyType'"))
    }

    @Test
    fun `anonymous game auth stays isolated from main AGKGMG auth`() {
        val store = source(
            "app/src/main/java/com/amaury/pointage/ObjectiveDeliveryGameStore.kt"
        )

        assertTrue(store.contains("GAME_FIREBASE_APP"))
        assertTrue(store.contains("FirebaseApp.initializeApp("))
        assertTrue(store.contains("FirebaseAuth.getInstance(gameApp)"))
        assertTrue(store.contains("FirebaseFirestore.getInstance(gameApp)"))
        assertTrue(store.contains("defaultUser != null && !defaultUser.isAnonymous"))
    }

    @Test
    fun `game touch target never truncates below 48dp`() {
        val activity = source(
            "app/src/main/java/com/amaury/pointage/ObjectiveDeliveryGameActivity.kt"
        )

        assertTrue(
            activity.contains(
                "ceil(48.0 * resources.displayMetrics.density).toInt()"
            )
        )
    }
}
