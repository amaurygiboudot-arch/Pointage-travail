package com.amaury.pointage.v2

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class OfficialJorfVerifierV2Test {
    private val candidate = OfficialJorfSourceV2.Candidate(
        textCid = "JORFTEXT000052111111",
        title = "Décret relatif au salaire minimum",
        nature = "DECRET",
        legalState = "INITIALE",
        ministry = "Ministère du travail",
        containerId = "JORFCONT000052345678",
        publicationDate = "2026-09-05T00:00:00Z"
    )

    private val document = OfficialJorfSourceV2.Document(
        textCid = "JORFTEXT000052111111",
        technicalId = "JORFTEXT000052111111",
        title = "Décret relatif au salaire minimum",
        nature = "DECRET",
        legalState = "INITIALE",
        nor = "TRAV2612345D",
        publicationDate = "2026-09-05T00:00:00Z",
        publicationNumber = "0200",
        hasContent = true
    )

    @Test
    fun `valide un JORF publie avant la date de paie`() {
        val referenceAtMs = LocalDate.of(2026, 9, 30)
            .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

        assertNotNull(
            OfficialJorfVerifierV2.validate(
                candidate = candidate,
                document = document,
                referenceAtMs = referenceAtMs,
                checkedAtMs = 10L
            )
        )
    }

    @Test
    fun `rejette un JORF publie apres la date de paie`() {
        val referenceAtMs = LocalDate.of(2026, 9, 1)
            .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

        assertNull(
            OfficialJorfVerifierV2.validate(
                candidate = candidate,
                document = document,
                referenceAtMs = referenceAtMs,
                checkedAtMs = 10L
            )
        )
    }

    @Test
    fun `rejette un JORF dont le CID ne correspond pas`() {
        val referenceAtMs = LocalDate.of(2026, 9, 30)
            .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

        assertNull(
            OfficialJorfVerifierV2.validate(
                candidate = candidate,
                document = document.copy(textCid = "JORFTEXT000052999999", technicalId = "JORFTEXT000052999999"),
                referenceAtMs = referenceAtMs,
                checkedAtMs = 10L
            )
        )
    }

    @Test
    fun `rejette un JORF sans contenu officiel`() {
        val referenceAtMs = LocalDate.of(2026, 9, 30)
            .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

        assertNull(
            OfficialJorfVerifierV2.validate(
                candidate = candidate,
                document = document.copy(hasContent = false),
                referenceAtMs = referenceAtMs,
                checkedAtMs = 10L
            )
        )
    }
}
