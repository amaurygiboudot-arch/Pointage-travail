package com.amaury.pointage.v2

import com.amaury.pointage.v2.model.SessionStatusV2
import com.amaury.pointage.v2.model.WorkSessionV2
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class V2RuntimeTransactionTest {
    @Test fun `manual caller and runtime closure both survive interleaved requests`() {
        val executor = Executors.newFixedThreadPool(2)
        val manualRead = CountDownLatch(1)
        val exitAttempted = CountDownLatch(1)
        var history = JSONArray()
        val open = WorkSessionV2("runtime", null, 5_000L, 5_000L, null, null, status = SessionStatusV2.OPEN)
        try {
            val manual = executor.submit<Boolean> {
                V2ManualSessionWriter.appendToHistory(1_000L, 2_000L, 1_000L, 2_000L, null, null, null,
                    readHistory = {
                        // Detects removing the transaction from the actual manual writer, not just the lock helper.
                        assertTrue(Thread.holdsLock(V2RuntimeStore))
                        val read = V2RuntimeHistoryGuardV2.inspect(JSONArray(history.toString()))
                        manualRead.countDown()
                        assertTrue(exitAttempted.await(5, TimeUnit.SECONDS))
                        read
                    }, readCurrent = { open }, currentReliable = { true },
                    saveHistory = { history = JSONArray(it.toString()); true })
            }
            assertTrue(manualRead.await(5, TimeUnit.SECONDS))
            val exit = executor.submit<Boolean> {
                exitAttempted.countDown()
                // Same monitor and history closure operation used by V2RuntimeStore.exit.
                synchronized(V2RuntimeStore) {
                    val closed = open.copy(realExitMs = 6_000L, countedExitMs = 6_000L, status = SessionStatusV2.CLOSED)
                    history = V2RuntimeStore.historyWithClosedSession(history, closed, null) ?: return@submit false
                    true
                }
            }
            assertTrue(manual.get(5, TimeUnit.SECONDS))
            assertTrue(exit.get(5, TimeUnit.SECONDS))
            assertEquals(2, history.length())
            assertEquals("runtime", history.getJSONObject(1).getString("id"))
            assertTrue(V2RuntimeHistoryGuardV2.inspect(history).reliable)
        } finally { executor.shutdownNow() }
    }

    @Test fun `two manual callers reread history and reject overlap after first commit`() {
        for (secondStart in listOf(1_500L, 3_000L)) {
            val executor = Executors.newFixedThreadPool(2)
            val firstRead = CountDownLatch(1)
            val secondAttempted = CountDownLatch(1)
            var history = JSONArray()
            fun add(start: Long, first: Boolean): Boolean = V2ManualSessionWriter.appendToHistory(
                start, start + 1_000L, start, start + 1_000L, null, null, null,
                readHistory = {
                    assertTrue(Thread.holdsLock(V2RuntimeStore))
                    val read = V2RuntimeHistoryGuardV2.inspect(JSONArray(history.toString()))
                    if (first) {
                        firstRead.countDown()
                        assertTrue(secondAttempted.await(5, TimeUnit.SECONDS))
                    }
                    read
                }, readCurrent = { null }, currentReliable = { true },
                saveHistory = { history = JSONArray(it.toString()); true })
            try {
                val first = executor.submit<Boolean> { add(1_000L, true) }
                assertTrue(firstRead.await(5, TimeUnit.SECONDS))
                val second = executor.submit<Boolean> { secondAttempted.countDown(); add(secondStart, false) }
                assertTrue(first.get(5, TimeUnit.SECONDS))
                assertEquals(secondStart == 3_000L, second.get(5, TimeUnit.SECONDS))
                assertEquals(if (secondStart == 3_000L) 2 else 1, history.length())
            } finally { executor.shutdownNow() }
        }
    }

    @Test fun `migration called inside runtime transaction is reentrant and failures release lock`() {
        V2RuntimeStore.withTransaction {
            V2RuntimeStore.withTransaction { assertTrue(Thread.holdsLock(V2RuntimeStore)) }
        }
        val executor = Executors.newSingleThreadExecutor()
        try {
            runCatching { V2RuntimeStore.withTransaction { error("commit failed") } }
            assertEquals("released", executor.submit<String> {
                V2RuntimeStore.withTransaction { "released" }
            }.get(5, TimeUnit.SECONDS))
        } finally { executor.shutdownNow() }
    }
}
