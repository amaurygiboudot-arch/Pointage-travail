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
        assertFalse(DevelopmentUpdateReleaseV2.isDevelopmentVersion("dev-latest"))
        assertEquals("1788696000", DevelopmentUpdateReleaseV2.revision("1.1440-dev-1788696000"))
    }

    @Test
    fun `selectionne uniquement la prerelease permanente dev latest`() {
        val json = """
            [
              {
                "draft": false,
                "prerelease": true,
                "tag_name": "dev-1788695000",
                "body": "DEV_VERSION=1.1440-dev-1788695000",
                "assets": [
                  {
                    "name": "HoraTrack-dev.apk",
                    "browser_download_url": "https://github.com/amaurygiboudot-arch/Pointage-travail/releases/download/dev-1788695000/HoraTrack-dev.apk"
                  }
                ]
              },
              {
                "draft": false,
                "prerelease": true,
                "tag_name": "dev-latest",
                "body": "DEV_VERSION=1.1440-dev-1788696000\nCHANNEL=development\nBUILD_KIND=tested",
                "assets": [
                  {
                    "name": "HoraTrack-dev.apk",
                    "browser_download_url": "https://github.com/amaurygiboudot-arch/Pointage-travail/releases/download/dev-latest/HoraTrack-dev.apk"
                  }
                ]
              }
            ]
        """.trimIndent()

        val candidate = DevelopmentUpdateReleaseV2.parseLatest(json)
        assertEquals("1.1440-dev-1788696000", candidate?.versionName)
        assertEquals("1788696000", candidate?.revision)
        assertEquals("dev-latest", candidate?.tag)
        assertEquals(
            "https://github.com/amaurygiboudot-arch/Pointage-travail/releases/download/dev-latest/HoraTrack-dev.apk",
            candidate?.apkUrl
        )
        assertEquals(
            "https://github.com/amaurygiboudot-arch/Pointage-travail/releases/download/dev-latest/SHA256-dev.txt",
            DevelopmentUpdateReleaseV2.sha256Url(candidate!!.versionName)
        )
    }

    @Test
    fun `refuse une source autre que dev latest ou une url externe`() {
        val oldRelease = """
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
        assertNull(DevelopmentUpdateReleaseV2.parseLatest(oldRelease))

        val externalUrl = """
            [
              {
                "draft": false,
                "prerelease": true,
                "tag_name": "dev-latest",
                "body": "DEV_VERSION=1.1440-dev-1788696000",
                "assets": [
                  {
                    "name": "HoraTrack-dev.apk",
                    "browser_download_url": "https://example.org/HoraTrack-dev.apk"
                  }
                ]
              }
            ]
        """.trimIndent()
        assertNull(DevelopmentUpdateReleaseV2.parseLatest(externalUrl))
    }
}
