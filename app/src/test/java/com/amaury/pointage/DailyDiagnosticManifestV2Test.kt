package com.amaury.pointage

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.*
import org.junit.Test
import org.w3c.dom.Element

/** Covers the Play overlay even when the standard CI runs the debug unit-test task. */
class DailyDiagnosticManifestV2Test {
    private val root = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
        .first { File(it, "app/build.gradle.kts").isFile }
    private val android = "http://schemas.android.com/apk/res/android"
    private val tools = "http://schemas.android.com/tools"

    private fun elements(path: String, tag: String): List<Element> {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }
        val document = factory.newDocumentBuilder().parse(File(root, path))
        val nodes = document.getElementsByTagName(tag)
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }
    private fun className(element: Element): String = element.getAttributeNS(android, "name").let {
        if (it.startsWith(".")) "com.amaury.pointage$it" else it
    }

    @Test fun diagnosticProviderHasIndependentMergeIdentityAndOnlyItsPrivateExportRoot() {
        val providers = elements("app/src/main/AndroidManifest.xml", "provider")
        val diagnostic = providers.single {
            it.getAttributeNS(android, "authorities") == "\${applicationId}.daily-diagnostic-files"
        }
        assertEquals("com.amaury.pointage.DailyDiagnosticFileProvider", className(diagnostic))
        assertEquals(1, providers.count { className(it) == className(diagnostic) })
        assertEquals("false", diagnostic.getAttributeNS(android, "exported"))
        assertEquals("true", diagnostic.getAttributeNS(android, "grantUriPermissions"))
        val metadata = diagnostic.getElementsByTagName("meta-data")
        assertEquals(1, metadata.length)
        assertEquals("@xml/daily_diagnostic_file_paths", (metadata.item(0) as Element).getAttributeNS(android, "resource"))

        // ManifestMerger matches provider nodes by android:name, NOT by authorities.
        val removed = elements("app/src/play/AndroidManifest.xml", "provider")
            .filter { it.getAttributeNS(tools, "node") in setOf("remove", "removeAll") }
        assertTrue("The internal APK provider must remain removed in Play", removed.any {
            it.getAttributeNS(android, "authorities") == "\${applicationId}.update-files"
        })
        assertFalse("Play removal must never match the diagnostic provider class", removed.any {
            it.getAttributeNS(tools, "node") == "removeAll" || className(it) == className(diagnostic)
        })
        val paths = elements("app/src/main/res/xml/daily_diagnostic_file_paths.xml", "cache-path")
        assertEquals(1, paths.size)
        assertEquals("daily_diagnostic_exports/", paths.single().getAttribute("path"))
        for (broadRoot in listOf("root-path", "files-path", "external-path", "external-files-path")) {
            assertTrue(elements("app/src/main/res/xml/daily_diagnostic_file_paths.xml", broadRoot).isEmpty())
        }
    }
}
