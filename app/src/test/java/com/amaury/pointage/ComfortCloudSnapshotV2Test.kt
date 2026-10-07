package com.amaury.pointage

import org.junit.Assert.*
import org.junit.Test

class ComfortCloudSnapshotV2Test {
    private fun data(revision: Any = 4L, deleted: Boolean = false, payload: String = ComfortTransferV2(true, false, 2f).encode()) =
        mapOf("schemaVersion" to 1L, "revision" to revision, "payload" to payload, "deleted" to deleted, "updatedAt" to "server timestamp")
    @Test fun `missing document can be created only against expected zero`() {
        val missing = ComfortCloudSnapshotV2.decode(null)
        missing.requireExpected(0)
        assertEquals(1L, missing.nextRevision)
        assertTrue(runCatching { missing.requireExpected(1) }.isFailure)
    }
    @Test fun `concurrent snapshots reject stale writes and deletion retains monotonic revision`() {
        val live = ComfortCloudSnapshotV2.decode(data())
        assertTrue(runCatching { live.requireExpected(3) }.isFailure)
        live.requireExpected(4)
        val deleted = ComfortCloudSnapshotV2.decode(data(live.nextRevision, true, ""))
        assertNull(deleted.comfort)
        assertEquals(6L, deleted.nextRevision)
        assertTrue(runCatching { deleted.requireExpected(4) }.isFailure)
    }
    @Test fun `malformed and future records never silently become missing`() {
        val invalid = listOf(data(4.5), data(-1), data(Double.NaN), data(true), data(deleted = true),
            data() + ("schemaVersion" to 2), data() + ("unknown" to false), data() - "payload")
        invalid.forEach { assertTrue(runCatching { ComfortCloudSnapshotV2.decode(it) }.isFailure) }
        assertTrue(runCatching { ComfortCloudSnapshotV2(ComfortCloudSnapshotV2.MAX_REVISION, null).nextRevision }.isFailure)
    }
    @Test fun `existing cloud envelope carries new shared schedule without losing fields`() {
        val transfer = ComfortTransferV2.from(PersonalizationProfileV2(highContrast = true,
            nightScheduleEnabled = true, nightStartMinute = 1320, nightEndMinute = 420))
        val snapshot = ComfortCloudSnapshotV2.decode(data(payload = transfer.encode()))
        assertEquals(4L, snapshot.revision)
        assertEquals(transfer, snapshot.comfort)
        assertEquals(transfer, ComfortCloudSnapshotV2.decode(data(payload = snapshot.comfort!!.encode())).comfort)
    }

}
