package com.amaury.pointage.v2

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class V2MigrationManagerTest {
    @Test
    fun `legacy migration is required before current version`() {
        assertTrue(V2MigrationManager.migrationRequired(0))
        assertTrue(V2MigrationManager.migrationRequired(4))
    }

    @Test
    fun `legacy migration is not replayed once v5 is already complete`() {
        assertFalse(V2MigrationManager.migrationRequired(5))
        assertFalse(V2MigrationManager.migrationRequired(6))
    }
}
