package com.amaury.pointage

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.junit.Assert.*
import org.junit.Test

class DriveBackupWorkPolicyTest {
    @Test fun `une deconnexion avant execution ne touche pas au fournisseur`() {
        assertEquals(DriveBackupWorkPolicy.Outcome.SUCCESS,
            DriveBackupWorkPolicy.run({ false }, { false }, 0) { error("I/O interdite") })
    }

    @Test fun `un worker deja arrete ne lance aucune sauvegarde`() {
        assertEquals(DriveBackupWorkPolicy.Outcome.RETRY,
            DriveBackupWorkPolicy.run({ true }, { true }, 0) { error("I/O interdite") })
    }

    @Test fun `le resultat attend la sauvegarde reelle sur le meme thread`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val completed = CountDownLatch(1)
        val worker = thread {
            val owner = Thread.currentThread()
            val result = DriveBackupWorkPolicy.run({ true }, { false }, 0) {
                assertSame(owner, Thread.currentThread())
                entered.countDown()
                check(release.await(2, TimeUnit.SECONDS))
                Result.success("copie verifiee")
            }
            assertEquals(DriveBackupWorkPolicy.Outcome.SUCCESS, result)
            completed.countDown()
        }
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertFalse(completed.await(100, TimeUnit.MILLISECONDS))
        } finally { release.countDown() }
        assertTrue(completed.await(2, TimeUnit.SECONDS))
        worker.join(2_000)
        assertFalse(worker.isAlive)
    }

    @Test fun `une erreur IO nest reessayee que cinq fois sans boucle locale`() {
        var calls = 0
        for (attempt in 0 until DriveBackupWorkPolicy.MAX_ATTEMPTS) {
            val result = DriveBackupWorkPolicy.run({ true }, { false }, attempt) {
                calls++
                Result.failure(IOException("provider unavailable"))
            }
            assertEquals(if (attempt < 4) DriveBackupWorkPolicy.Outcome.RETRY
                else DriveBackupWorkPolicy.Outcome.FAILURE, result)
            assertEquals(attempt + 1, calls)
        }
    }

    @Test fun `permission retiree et snapshot invalide ne bouclent pas`() {
        for (error in listOf(SecurityException("permission"), IllegalStateException("snapshot"))) {
            assertEquals(DriveBackupWorkPolicy.Outcome.FAILURE,
                DriveBackupWorkPolicy.run({ true }, { false }, 0) { Result.failure(error) })
        }
    }

    @Test fun `un arret pendant la sauvegarde ne confirme pas le succes`() {
        var stopped = false
        assertEquals(DriveBackupWorkPolicy.Outcome.RETRY,
            DriveBackupWorkPolicy.run({ true }, { stopped }, 0) {
                stopped = true
                Result.success("ecrit")
            })
    }
}
