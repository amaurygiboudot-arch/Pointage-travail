package com.amaury.pointage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DevelopmentUpdateReleaseV2Test {
    @Test
    fun `reconnait uniquement une version de developpement HoraTrack`() {
        assertTrue(DevelopmentUpdateReleaseV2.isDevelopmentVersion("1.1440-dev-1788696000"))
        assertFalse(DevelopmentUpdateReleaseV2.isDevelopmentVersion("1.1440"))
        assertFalse(DevelopmentUpdateReleaseV2.isDevelopmentVersion("dev-1788696000"))
        assertEquals("1788696000", DevelopmentUpdateReleaseV2.revision("1.1440-dev-1788696000"))
    }

    @Test
    fun `selectionne une prerelease dev coherente et son apk exact`() {
        val json = """
            [
              {
                "draft": false,
                "prerelease": true,
                "tag_name": "dev-1788696000",
                "body": "DEV_VERSION=1.1440-dev-1788696000",
                "assets": [
                  {
                    "name": "HoraTrack-dev.apk",
                    "browser_download_url": "https://github.com/amaurygiboudot-arch/Pointage-travail/releases/download/dev-1788696000/HoraTrack-dev.apk"
                  }
                ]
              }
            ]
        """.trimIndent()

        val candidate = DevelopmentUpdateReleaseV2.parseLatest(json)
        assertEquals("1.1440-dev-1788696000", candidate?.versionName)
        assertEquals("dev-1788696000", candidate?.tag)
        assertEquals(
            "https://github.com/amaurygiboudot-arch/Pointage-travail/releases/download/dev-1788696000/HoraTrack-dev.apk",
            candidate?.apkUrl
        )
        assertEquals(
            "https://github.com/amaurygiboudot-arch/Pointage-travail/releases/download/dev-1788696000/SHA256-dev.txt",
            DevelopmentUpdateReleaseV2.sha256Url(candidate!!.versionName)
        )
    }

    @Test
    fun `refuse une prerelease dont tag version ou url ne correspondent pas`() {
        val json = """
            [
              {
                "draft": false,
                "prerelease": true,
                "tag_name": "dev-1788696000",
                "body": "DEV_VERSION=1.1440-dev-1788696001",
                "assets": [
                  {
                    "name": "HoraTrack-dev.apk",
                    "browser_download_url": "https://example.org/HoraTrack-dev.apk"
                  }
                ]
              }
            ]
        """.trimIndent()

        assertNull(DevelopmentUpdateReleaseV2.parseLatest(json))
    }
}
