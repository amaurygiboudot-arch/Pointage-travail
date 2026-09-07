package com.amaury.pointage.v2.engine

import com.amaury.pointage.v2.model.SessionStatusV2
import com.amaury.pointage.v2.model.WorkSessionV2
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar
import java.util.Locale

class NightPremiumPolicyV2Test {
    @Test
    fun `calcule uniquement la plage officielle qui traverse minuit`() {
        val entry = localMs(2026, Calendar.SEPTEMBER, 7, 20, 0)
        val exit = localMs(2026, Calendar.SEPTEMBER, 8, 7, 0)
        val session = session(entry, exit)
        val rule = NightPremiumRuleV2(21 * 60, 6 * 60, 1.25)

        val overlap = NightPremiumPolicyV2.paidOverlap(session, entry, exit, rule)

        assertEquals(9L * 60L * 60L * 1000L, overlap)
    }

    @Test
    fun `ne transforme pas une session de jour en heures de nuit`() {
        val entry = localMs(2026, Calendar.SEPTEMBER, 7, 9, 0)
        val exit = localMs(2026, Calendar.SEPTEMBER, 7, 17, 0)
        val session = session(entry, exit)
        val rule = NightPremiumRuleV2(21 * 60, 6 * 60, 1.25)

        assertEquals(0L, NightPremiumPolicyV2.paidOverlap(session, entry, exit, rule))
    }

    @Test
    fun `respecte aussi une plage nocturne qui ne traverse pas minuit`() {
        val entry = localMs(2026, Calendar.SEPTEMBER, 7, 18, 0)
        val exit = localMs(2026, Calendar.SEPTEMBER, 7, 23, 0)
        val session = session(entry, exit)
        val rule = NightPremiumRuleV2(19 * 60, 22 * 60, 1.10)

        assertEquals(
            3L * 60L * 60L * 1000L,
            NightPremiumPolicyV2.paidOverlap(session, entry, exit, rule)
        )
    }

    private fun session(entry: Long, exit: Long) = WorkSessionV2(
        id = "night-test",
        employerId = "company-test",
        realArrivalMs = entry,
        countedEntryMs = entry,
        countedExitMs = exit,
        realExitMs = exit,
        status = SessionStatusV2.CLOSED
    )

    private fun localMs(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        Calendar.getInstance(Locale.FRANCE).apply {
            clear()
            set(year, month, day, hour, minute, 0)
        }.timeInMillis
}
