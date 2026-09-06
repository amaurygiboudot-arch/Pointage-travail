package com.amaury.pointage

import org.junit.Assert.assertEquals
import org.junit.Test

class ApkUpdateVerifierChannelTest {
    @Test
    fun `la release publique conserve son SHA public`() {
        assertEquals(
            "https://github.com/amaurygiboudot-arch/Pointage-travail/releases/download/v1.1440/SHA256.txt",
            ApkUpdateVerifier.expectedSha256Url("1.1440")
        )
    }

    @Test
    fun `le canal de developpement utilise toujours le SHA de dev latest`() {
        assertEquals(
            "https://github.com/amaurygiboudot-arch/Pointage-travail/releases/download/dev-latest/SHA256-dev.txt",
            ApkUpdateVerifier.expectedSha256Url("1.1440-dev-1788696000")
        )
    }
}
