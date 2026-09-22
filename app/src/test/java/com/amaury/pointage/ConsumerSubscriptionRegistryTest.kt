package com.amaury.pointage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ConsumerSubscriptionRegistryTest {
    @Test
    fun `deux consommateurs partagent le proprietaire sans se detacher mutuellement`() {
        val starts = mutableListOf<Any>()
        val stops = mutableListOf<Any>()
        val registry = ConsumerSubscriptionRegistry<String, () -> Unit>(
            start = { _, trackerKey, _ -> starts += trackerKey },
            stop = { stops += it }
        )

        registry.register("activity", "buttons") {}
        registry.register("activity", "celestial") {}
        registry.setVisible("activity", true)

        assertEquals(2, registry.activeCount("activity"))
        assertEquals(2, starts.size)
        assertNotEquals(starts[0], starts[1])

        registry.unregister("activity", "buttons")

        assertEquals(1, registry.activeCount("activity"))
        assertEquals(listOf(starts[0]), stops)
    }

    @Test
    fun `pause suspend tout puis reprise reactive exactement une fois`() {
        var starts = 0
        var stops = 0
        val registry = ConsumerSubscriptionRegistry<String, () -> Unit>(
            start = { _, _, _ -> starts++ },
            stop = { stops++ }
        )
        registry.register("activity", "buttons") {}
        registry.setVisible("activity", true)
        registry.setVisible("activity", false)
        registry.setVisible("activity", false)
        registry.setVisible("activity", true)

        assertEquals(2, starts)
        assertEquals(1, stops)
        assertEquals(1, registry.activeCount("activity"))
    }

    @Test
    fun `destruction libere tous les consommateurs actifs`() {
        var stops = 0
        val registry = ConsumerSubscriptionRegistry<String, () -> Unit>(
            start = { _, _, _ -> Unit },
            stop = { stops++ }
        )
        registry.setVisible("activity", true)
        registry.register("activity", "one") {}
        registry.register("activity", "two") {}

        registry.clear("activity")

        assertEquals(2, stops)
        assertEquals(0, registry.activeCount("activity"))
    }
}
