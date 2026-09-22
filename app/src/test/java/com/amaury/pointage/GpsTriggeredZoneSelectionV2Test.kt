package com.amaury.pointage

import com.amaury.pointage.v2.engine.GpsPointTypeV2
import com.amaury.pointage.v2.engine.GpsTransitionV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class GpsTriggeredZoneSelectionV2Test {
    @Test
    fun `une zone historique uuid sans type reste un poste de travail`() {
        val legacy = StoredGpsZone(
            id = "ae250835-5652-4f1b-ad5f-2cad92d80c09",
            latitude = 46.7,
            longitude = -1.4,
            radius = 150f,
            address = "12 rue de l'Atelier",
            companyId = null,
            companySlot = null,
            pointTypeToken = null,
            label = null,
            sourceJson = "{}"
        )

        assertEquals(GpsPointTypeV2.POSTE, GpsTriggeredZoneSelectionV2.pointType(legacy))
    }

    @Test
    fun `une zone historique sans type ne deduit rien de son identifiant`() {
        val legacy = StoredGpsZone(
            id = "parking-other-legacy",
            latitude = 46.7,
            longitude = -1.4,
            radius = 150f,
            address = "Ancien atelier",
            companyId = null,
            companySlot = null,
            pointTypeToken = null,
            label = null,
            sourceJson = "{}"
        )

        assertEquals(GpsPointTypeV2.POSTE, GpsTriggeredZoneSelectionV2.pointType(legacy))
    }

    @Test
    fun `un type explicite inconnu reste ambigu`() {
        val unknown = zone(
            id = "ae250835-5652-4f1b-ad5f-2cad92d80c09",
            address = "12 rue de l'Atelier",
            label = null,
            pointType = "VISITE_CLIENT"
        )

        assertEquals(GpsPointTypeV2.OTHER, GpsTriggeredZoneSelectionV2.pointType(unknown))
    }

    @Test
    fun `une seule zone est selectionnee`() {
        val result = GpsTriggeredZoneSelectionV2.select(
            listOf(candidate("zone-a"))
        )

        assertEquals(
            GpsTriggeredZoneSelectionV2.Result.Selected("zone-a"),
            result
        )
    }

    @Test
    fun `zones equivalentes utilisent un choix deterministe`() {
        val result = GpsTriggeredZoneSelectionV2.select(
            listOf(
                candidate("zone-z"),
                candidate("zone-a")
            )
        )

        assertEquals(
            GpsTriggeredZoneSelectionV2.Result.Selected("zone-a"),
            result
        )
    }

    @Test
    fun `reentree equivalente prefere le lieu de sortie en attente`() {
        val result = GpsTriggeredZoneSelectionV2.select(
            candidates = listOf(
                candidate("zone-a"),
                candidate("zone-b")
            ),
            preferredZoneId = "zone-b"
        )

        assertEquals(GpsTriggeredZoneSelectionV2.Result.Selected("zone-b"), result)
    }

    @Test
    fun `employeurs differents bloquent le pointage automatique`() {
        val result = GpsTriggeredZoneSelectionV2.select(
            listOf(
                candidate("zone-a", employerKey = "company:a"),
                candidate("zone-b", employerKey = "company:b")
            )
        )

        assertTrue(result is GpsTriggeredZoneSelectionV2.Result.Blocked)
    }

    @Test
    fun `types de point differents bloquent le pointage automatique`() {
        val result = GpsTriggeredZoneSelectionV2.select(
            listOf(
                candidate("zone-a", pointType = GpsPointTypeV2.POSTE),
                candidate("zone-b", pointType = GpsPointTypeV2.PARKING)
            )
        )

        assertTrue(result is GpsTriggeredZoneSelectionV2.Result.Blocked)
    }

    @Test
    fun `lieux differents bloquent le pointage automatique`() {
        val result = GpsTriggeredZoneSelectionV2.select(
            listOf(
                candidate("zone-a", placeKey = "address:a"),
                candidate("zone-b", placeKey = "address:b")
            )
        )

        assertTrue(result is GpsTriggeredZoneSelectionV2.Result.Blocked)
    }

    @Test
    fun `doublon du meme request id ne cree pas de fausse ambiguite`() {
        val result = GpsTriggeredZoneSelectionV2.select(
            listOf(
                candidate("zone-a"),
                candidate("zone-a")
            )
        )

        assertEquals(
            GpsTriggeredZoneSelectionV2.Result.Selected("zone-a"),
            result
        )
    }

    @Test
    fun `sortie groupee choisit le lieu prouve par la session ouverte quel que soit l ordre`() {
        val first = GpsTriggeredZoneSelectionV2.selectForExit(
            candidates = listOf(
                exitCandidate("zone-b", placeKey = "address:b"),
                exitCandidate("zone-a", placeKey = "address:a"),
                exitCandidate("zone-b", placeKey = "address:b")
            ),
            openSessionPlaceId = "zone-a"
        )
        val reversed = GpsTriggeredZoneSelectionV2.selectForExit(
            candidates = listOf(
                exitCandidate("zone-a", placeKey = "address:a"),
                exitCandidate("zone-b", placeKey = "address:b")
            ),
            openSessionPlaceId = "zone-a"
        )

        assertEquals(GpsTriggeredZoneSelectionV2.Result.Selected("zone-a"), first)
        assertEquals(first, reversed)
    }

    @Test
    fun `sortie groupee sans session ouverte reste bloquee`() {
        val result = GpsTriggeredZoneSelectionV2.selectForExit(
            candidates = listOf(
                exitCandidate("zone-a", placeKey = "address:a"),
                exitCandidate("zone-b", placeKey = "address:b")
            ),
            openSessionPlaceId = null,
            openSessionAvailable = false
        )

        assertTrue(result is GpsTriggeredZoneSelectionV2.Result.Blocked)
    }

    @Test
    fun `sortie simple ne depend pas du registre employeur`() {
        val result = GpsTriggeredZoneSelectionV2.selectForExit(
            candidates = listOf(exitCandidate("zone-a")),
            openSessionPlaceId = null
        )

        assertEquals(GpsTriggeredZoneSelectionV2.Result.Selected("zone-a"), result)
    }

    @Test
    fun `keep current utilise l employeur actif confirme pour la comparaison`() {
        assertEquals(
            "company:company-a",
            GpsTriggeredZoneSelectionV2.employerKey(
                GpsZoneEmployerResolutionV2.KeepCurrent,
                confirmedActiveCompanyId = "company-a"
            )
        )
        assertEquals(
            "company:company-a",
            GpsTriggeredZoneSelectionV2.employerKey(
                GpsZoneEmployerResolutionV2.UseCompany("company-a"),
                confirmedActiveCompanyId = "company-b"
            )
        )
    }

    @Test
    fun `keep current sans employeur confirme conserve une cle distincte`() {
        assertEquals(
            "keep-current",
            GpsTriggeredZoneSelectionV2.employerKey(
                GpsZoneEmployerResolutionV2.KeepCurrent,
                confirmedActiveCompanyId = null
            )
        )
    }

    @Test
    fun `keep current bloque si le registre employeur est non fiable`() {
        assertEquals(
            null,
            GpsTriggeredZoneSelectionV2.employerKey(
                GpsZoneEmployerResolutionV2.KeepCurrent,
                confirmedActiveCompanyId = "company-a",
                companiesReliable = false
            )
        )
    }

    @Test
    fun `sortie groupee equivalente reste deterministe sans lieu de session`() {
        val result = GpsTriggeredZoneSelectionV2.selectForExit(
            candidates = listOf(
                exitCandidate("zone-z"),
                exitCandidate("zone-a")
            ),
            openSessionPlaceId = null
        )

        assertEquals(GpsTriggeredZoneSelectionV2.Result.Selected("zone-a"), result)
    }

    @Test
    fun `lieu de session perime ne departage pas des sorties incompatibles`() {
        val result = GpsTriggeredZoneSelectionV2.selectForExit(
            candidates = listOf(
                exitCandidate("zone-a", placeKey = "address:a"),
                exitCandidate("zone-b", placeKey = "address:b")
            ),
            openSessionPlaceId = "zone-old"
        )

        assertTrue(result is GpsTriggeredZoneSelectionV2.Result.Blocked)
    }

    @Test
    fun `sortie groupee de types differents reste bloquee`() {
        val result = GpsTriggeredZoneSelectionV2.selectForExit(
            candidates = listOf(
                exitCandidate("zone-a", pointType = GpsPointTypeV2.POSTE),
                exitCandidate("zone-b", pointType = GpsPointTypeV2.PARKING)
            ),
            openSessionPlaceId = null
        )

        assertTrue(result is GpsTriggeredZoneSelectionV2.Result.Blocked)
    }

    @Test
    fun `runtime corrompu bloque meme une sortie simple`() {
        val result = GpsTriggeredZoneSelectionV2.selectForExit(
            candidates = listOf(exitCandidate("zone-a")),
            openSessionPlaceId = null,
            runtimeReliable = false
        )

        assertTrue(result is GpsTriggeredZoneSelectionV2.Result.Blocked)
    }

    @Test
    fun `session fermee bloque meme une sortie simple`() {
        val result = GpsTriggeredZoneSelectionV2.selectForExit(
            candidates = listOf(exitCandidate("zone-a", pointType = GpsPointTypeV2.OTHER)),
            openSessionPlaceId = null,
            runtimeReliable = true,
            openSessionAvailable = false
        )

        assertTrue(result is GpsTriggeredZoneSelectionV2.Result.Blocked)
    }

    @Test
    fun `keep current confirme ne masque pas un employeur different`() {
        val result = GpsTriggeredZoneSelectionV2.select(
            listOf(
                candidate("zone-a", employerKey = GpsTriggeredZoneSelectionV2.employerKey(
                    GpsZoneEmployerResolutionV2.KeepCurrent,
                    confirmedActiveCompanyId = "company-a"
                )!!),
                candidate("zone-b", employerKey = GpsTriggeredZoneSelectionV2.employerKey(
                    GpsZoneEmployerResolutionV2.UseCompany("company-b"),
                    confirmedActiveCompanyId = "company-a"
                )!!)
            )
        )

        assertTrue(result is GpsTriggeredZoneSelectionV2.Result.Blocked)
    }

    @Test
    fun `adresses differentes restent incompatibles meme avec le meme libelle`() {
        val first = zone(id = "zone-a", address = "10 rue A", label = "Bureau")
        val second = zone(id = "zone-b", address = "20 rue B", label = "Bureau")

        assertTrue(
            GpsTriggeredZoneSelectionV2.placeKey(first) !=
                GpsTriggeredZoneSelectionV2.placeKey(second)
        )
    }

    @Test
    fun `entree synthetique utilise l heure de resolution fournie`() {
        val event = GpsTriggeredZoneSelectionV2.event(
            zoneId = "zone-a",
            pointType = GpsPointTypeV2.POSTE,
            transition = GpsTransitionV2.ENTER,
            atMs = 2_000L
        )

        assertEquals(2_000L, event.atMs)
        assertEquals("gps-enter-zone-a-2000", event.id)
    }

    @Test
    fun `l adresse canonique prime sur les libelles differents`() {
        val first = zone(id = "zone-a", address = " 10 Rue de l'Île ", label = "Entrée Nord")
        val second = zone(id = "zone-b", address = "10 RUE DE L'ÎLE", label = "Accueil")

        assertEquals(
            GpsTriggeredZoneSelectionV2.placeKey(first),
            GpsTriggeredZoneSelectionV2.placeKey(second)
        )
    }

    @Test
    fun `le libelle sert de repli avec une casse independante de la langue du telephone`() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            assertEquals(
                "label:site i",
                GpsTriggeredZoneSelectionV2.placeKey(
                    zone(id = "zone-a", address = null, label = "SITE I")
                )
            )
            assertEquals(
                GpsPointTypeV2.PARKING,
                GpsTriggeredZoneSelectionV2.pointType(
                    zone(id = "zone-b", address = null, label = null, pointType = "parking")
                )
            )
        } finally {
            Locale.setDefault(previous)
        }
    }

    private fun candidate(
        id: String,
        employerKey: String = "company:a",
        pointType: GpsPointTypeV2 = GpsPointTypeV2.POSTE,
        placeKey: String = "address:site-a"
    ) = GpsTriggeredZoneSelectionV2.Candidate(
        zoneId = id,
        employerKey = employerKey,
        pointType = pointType,
        placeKey = placeKey
    )

    private fun exitCandidate(
        id: String,
        pointType: GpsPointTypeV2 = GpsPointTypeV2.POSTE,
        placeKey: String = "address:same"
    ) = GpsTriggeredZoneSelectionV2.ExitCandidate(
        zoneId = id,
        pointType = pointType,
        placeKey = placeKey
    )

    private fun zone(
        id: String,
        address: String?,
        label: String?,
        pointType: String = "POSTE"
    ) = StoredGpsZone(
        id = id,
        latitude = 48.0,
        longitude = 2.0,
        radius = 150f,
        address = address,
        companyId = null,
        companySlot = null,
        pointTypeToken = pointType,
        label = label,
        sourceJson = "{}"
    )
}
