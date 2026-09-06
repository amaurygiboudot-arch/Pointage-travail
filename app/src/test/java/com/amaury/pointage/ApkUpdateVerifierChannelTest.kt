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
    fun `le canal de developpement lie le SHA de dev latest a la revision`() {
        assertEquals(
            "https://github.com/amaurygiboudot-arch/Pointage-travail/releases/download/dev-latest/SHA256-dev.txt?rev=1788696000",
            ApkUpdateVerifier.expectedSha256Url("1.1440-dev-1788696000")
        )
    }
}
