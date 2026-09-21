package com.amaury.pointage.v2

import com.amaury.pointage.v2.engine.EmploymentContractHistoryV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class V2EmploymentContractHistoryStoreTest {
    @Test
    fun `liste vide explicite reste fiable`() {
        val result = V2EmploymentContractHistoryStore.decodeConfirmed("[]")

        assertTrue(result.reliable)
        assertTrue(result.snapshots.isEmpty())
        assertNotNull(V2EmploymentContractHistoryStore.historyFrom(result))
    }

    @Test
    fun `snapshot horaire valide est relu et resolu par periode`() {
        val result = V2EmploymentContractHistoryStore.decodeConfirmed(validSnapshotJson())

        assertTrue(result.reliable)
        assertEquals(1, result.snapshots.size)
        val history = V2EmploymentContractHistoryStore.historyFrom(result)
        assertNotNull(history)
        assertEquals("v1", history?.applicable("company-a", 120)?.versionId)
        assertNull(history?.applicable("company-a", 99))
        assertTrue(history?.coverage("company-a", 100, 199)?.fullyCovered == true)
    }

    @Test
    fun `stockage malforme reste non fiable et ne devient pas vide`() {
        val result = V2EmploymentContractHistoryStore.decodeConfirmed("{not-json}")

        assertFalse(result.reliable)
        assertNull(V2EmploymentContractHistoryStore.historyFrom(result))
        assertTrue(result.warnings.isNotEmpty())
    }

    @Test
    fun `chevauchement de versions rend tout historique non fiable`() {
        val first = validSnapshotJson().removePrefix("[").removeSuffix("]")
        val second = first
            .replace("\"versionId\":\"v1\"", "\"versionId\":\"v2\"")
            .replace("\"effectiveFromEpochDay\":100", "\"effectiveFromEpochDay\":150")
            .replace("\"effectiveToEpochDay\":199", "\"effectiveToEpochDay\":250")
        val result = V2EmploymentContractHistoryStore.decodeConfirmed("[$first,$second]")

        assertFalse(result.reliable)
        assertNull(V2EmploymentContractHistoryStore.historyFrom(result))
    }

    @Test
    fun `type legacy forfait ne peut pas etre restaure comme verite historique`() {
        val result = V2EmploymentContractHistoryStore.decodeConfirmed(
            validSnapshotJson().replace("\"FULL_TIME\"", "\"FORFAIT\"")
        )

        assertFalse(result.reliable)
        assertTrue(result.snapshots.isEmpty())
    }

    @Test
    fun `booleen a la place dun entier est refuse`() {
        val result = V2EmploymentContractHistoryStore.decodeConfirmed(
            validSnapshotJson().replace("\"contractualWeeklyMinutes\":2100", "\"contractualWeeklyMinutes\":true")
        )

        assertFalse(result.reliable)
    }

    private fun validSnapshotJson(): String = """
        [
          {
            "versionId":"v1",
            "sourceId":"user-confirmed",
            "effectiveFromEpochDay":100,
            "effectiveToEpochDay":199,
            "checkedAtMs":1,
            "note":null,
            "contract":{
              "id":"contract-a",
              "employerId":"company-a",
              "type":"FULL_TIME",
              "contractualWeeklyMinutes":2100,
              "grossHourlyRate":13.5,
              "hireDateEpochDay":50,
              "payrollCutoffDay":31,
              "forfaitHoursPeriod":null,
              "forfaitHours":null,
              "forfaitAnnualDays":null,
              "monthlyGrossSalary":null
            }
          }
        ]
    """.trimIndent()
}
