package com.amaury.pointage

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DriveBackupSerializationTest {
    @Test
    fun `deux chemins Drive ne peuvent pas ecrire en meme temps`() {
        val firstEntered = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val secondAttempted = CountDownLatch(1)
        val secondEntered = CountDownLatch(1)

        val first = thread {
            DriveBackupManager.withStorageAccess {
                firstEntered.countDown()
                releaseFirst.await(2, TimeUnit.SECONDS)
            }
        }
        assertTrue(firstEntered.await(2, TimeUnit.SECONDS))

        val second = thread {
            secondAttempted.countDown()
            DriveBackupManager.withStorageAccess { secondEntered.countDown() }
        }
        assertTrue(secondAttempted.await(2, TimeUnit.SECONDS))

        try {
            assertFalse(secondEntered.await(100, TimeUnit.MILLISECONDS))
        } finally {
            releaseFirst.countDown()
        }

        assertTrue(secondEntered.await(2, TimeUnit.SECONDS))
        first.join(2_000L)
        second.join(2_000L)
        assertFalse(first.isAlive)
        assertFalse(second.isAlive)
    }
}
