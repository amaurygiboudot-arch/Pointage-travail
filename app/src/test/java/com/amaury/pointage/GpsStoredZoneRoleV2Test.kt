package com.amaury.pointage

import com.amaury.pointage.v2.engine.GpsPointTypeV2
import org.junit.Assert.*
import org.junit.Test

class GpsStoredZoneRoleV2Test {
    private fun zone(id: String, type: String?) = StoredGpsZone(
        id, 46.7, -1.4, 150f, "Même adresse", "company-a", null, type, id, "{}"
    )

    @Test fun `un mot ressemblant au travail ou au parking ne devient jamais un role confirme`() {
        for (token in listOf("NOT_WORK", "WORK_UNCONFIRMED", "POSTE_INCONNU", "NO_PARKING", "PARKING_OTHER")) {
            val value = zone(token, token)
            assertEquals(GpsZoneRoleV2.OTHER, value.roleForContextV2())
            assertEquals(GpsPointTypeV2.OTHER, GpsTriggeredZoneSelectionV2.pointType(value))
        }
    }

    @Test fun `les roles et alias explicites restent reconnus exactement`() {
        for (token in listOf("POSTE", "WORK", "WORKSITE", "WORKPLACE", " poste ")) {
            val value = zone("work", token)
            assertEquals(GpsZoneRoleV2.WORK, value.roleForContextV2())
            assertEquals(GpsPointTypeV2.POSTE, GpsTriggeredZoneSelectionV2.pointType(value))
        }
        assertEquals(GpsPointTypeV2.PARKING, GpsTriggeredZoneSelectionV2.pointType(zone("park", " parking ")))
    }

    @Test fun `pause explicite reste visible mais exige sa qualification avant remuneration`() {
        for (token in listOf("PAUSE", "BREAK")) {
            val value = zone("pause", token)
            assertEquals(GpsZoneRoleV2.BREAK, value.roleForContextV2())
            assertEquals(GpsPointTypeV2.OTHER, GpsTriggeredZoneSelectionV2.pointType(value))
        }
    }

    @Test fun `compatibilite des postes historiques distincte du brouillon nouveau`() {
        for (token in listOf(null, "", " ")) {
            val value = zone("parking-other-legacy", token)
            assertEquals(GpsZoneRoleV2.WORK, value.roleForContextV2())
            assertEquals(GpsPointTypeV2.POSTE, GpsTriggeredZoneSelectionV2.pointType(value))
            assertEquals(GpsZoneRoleV2.OTHER, GpsZoneRoleV2.fromToken(token))
        }
    }

    @Test fun `travail et role inconnu a la meme adresse ne sont plus faussement equivalents`() {
        val work = zone("work", "POSTE")
        val unconfirmed = zone("unknown", "NOT_WORK")
        val candidates = listOf(work, unconfirmed).map {
            GpsTriggeredZoneSelectionV2.Candidate(it.id, "company:company-a",
                GpsTriggeredZoneSelectionV2.pointType(it), GpsTriggeredZoneSelectionV2.placeKey(it))
        }
        assertTrue(GpsTriggeredZoneSelectionV2.select(candidates) is GpsTriggeredZoneSelectionV2.Result.Blocked)
    }

    @Test fun `le resume du lieu et le moteur partagent la meme politique historique`() {
        val zones = listOf(zone("legacy", null), zone("pause", "PAUSE"), zone("unknown", "NO_PARKING"))
        val summary = summarizeGpsPlaceTypes(GpsPlaceGroup("Même adresse", "company-a", null, zones, false))
        assertEquals(1, summary.workZones)
        assertEquals(0, summary.parkingZones)
        assertEquals(1, summary.pauseZones)
        assertEquals(1, summary.otherZones)
    }
}
