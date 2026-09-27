package com.amaury.pointage

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudAccountBindingV2Test {
    private val root = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
        .first { File(it, "app/src/main/java/com/amaury/pointage/CloudAccountBindingV2.kt").isFile }

    private fun source(path: String) = File(root, path).readText()

    @Test
    fun `cloud backup is fail closed unless current uid is locally associated`() {
        val cloud = source("app/src/main/java/com/amaury/pointage/CloudPointageBackup.kt")
        assertTrue(cloud.contains("CloudAccountBindingV2.isBoundTo(context, user.uid)"))
        assertTrue(cloud.contains("Compte cloud non associé aux données locales de cet appareil"))
    }

    @Test
    fun `account screen requires explicit confirmation before changing association`() {
        val account = source("app/src/main/java/com/amaury/pointage/FirebaseAccountActivity.kt")
        assertTrue(account.contains("Changer le compte cloud associé ?"))
        assertTrue(account.contains("Associer ce compte cloud ?"))
        assertTrue(account.contains("CloudAccountBindingV2.bind(this, user.uid)"))
        assertFalse(account.contains("signOut()\n                CloudAccountBindingV2"))
    }
}
