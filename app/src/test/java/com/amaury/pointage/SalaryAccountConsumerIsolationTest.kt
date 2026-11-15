package com.amaury.pointage

import android.app.AlarmManager
import android.app.Application
import android.content.Context
import android.content.Intent
import com.amaury.pointage.v2.SalaryAccountScope
import com.amaury.pointage.v2.V2RuntimeAccountScope
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class,
    shadows = [GpsDeliveryAuthShadow::class], instrumentedPackages = ["com.google.firebase.auth"])
class SalaryAccountConsumerIsolationTest {
    private lateinit var context: Context
    private lateinit var auth: FirebaseAuth

    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        auth = Mockito.mock(FirebaseAuth::class.java)
        GpsDeliveryAuthShadow.auth = auth
        account("A")
    }

    private fun account(uid: String?) {
        val user = uid?.let { id -> Mockito.mock(FirebaseUser::class.java).also {
            Mockito.`when`(it.uid).thenReturn(id)
        } }
        Mockito.`when`(auth.currentUser).thenReturn(user)
        SalaryAccountScope.capture()
        V2RuntimeAccountScope.monitoringEpoch(context)
    }

    private fun company(name: String) = SalaryCompanyStore.Company("company_1", name, "")

    @Test fun companyLabelsAndLegacyPauseSettingsStayWithTheirOwner() {
        assertTrue(SalaryCompanyStore.createOrUpdate(context, company("Entreprise de A")))
        CompanyBasePauseSettings.savePause(context, 1, 1, 720, 750)
        assertEquals("Entreprise de A", CompanyNameUiBinder.name(context, 1))
        assertEquals(30, CompanyBasePauseSettings.baseMinutes(context, 1))

        account("B")
        assertEquals("", CompanyNameUiBinder.name(context, 1))
        assertEquals(0, CompanyBasePauseSettings.baseMinutes(context, 1))
        assertTrue(SalaryCompanyStore.createOrUpdate(context, company("Entreprise de B")))
        CompanyBasePauseSettings.savePause(context, 1, 1, 720, 780)
        assertEquals("Entreprise de B", CompanyNameUiBinder.name(context, 1))

        account("A")
        assertEquals("Entreprise de A", CompanyNameUiBinder.name(context, 1))
        assertEquals(30, CompanyBasePauseSettings.baseMinutes(context, 1))
    }

    @Test fun delayedPauseAlarmCannotTargetSameCompanyIdInAnotherAccountOrLaterSession() {
        assertTrue(SalaryCompanyStore.createOrUpdate(context, company("A")))
        CompanyPauseSettingsV2.savePause(context, "company_1", 1, 720, 750)
        CompanyPauseAlarmManager.scheduleAll(context)
        val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val deliveries = shadowOf(alarm).scheduledAlarms.map { shadowOf(it.operation).savedIntent }
        val delivery = Intent(deliveries.first { it.getStringExtra("companyId") == "company_1" })
        assertTrue(CompanyPauseAlarmManager.ownsDelivery(context, delivery))
        assertFalse(CompanyPauseAlarmManager.ownsDelivery(context, Intent(CompanyPauseAlarmManager.ACTION)
            .putExtra("companyId", "company_1")))

        account("B")
        assertTrue(SalaryCompanyStore.createOrUpdate(context, company("B")))
        CompanyPauseSettingsV2.savePause(context, "company_1", 1, 720, 780)
        assertFalse(CompanyPauseAlarmManager.ownsDelivery(context, delivery))
        account("A")
        assertFalse(CompanyPauseAlarmManager.ownsDelivery(context, delivery))
        CompanyPauseAlarmManager.scheduleAll(context)
        val replacement = shadowOf(alarm).scheduledAlarms.map { shadowOf(it.operation).savedIntent }
            .first { it.getStringExtra("companyId") == "company_1" }
        assertTrue(CompanyPauseAlarmManager.ownsDelivery(context, replacement))
    }

    @Test fun boundLegacyConsumerCannotReadOrWriteAfterAtoBtoA() {
        CompanyBasePauseSettings.savePause(context, 1, 1, 720, 750)
        val oldFormContext = SalaryAccountScope.bindContext(context)
        account("B")
        CompanyBasePauseSettings.savePause(oldFormContext, 1, 1, 720, 780)
        assertEquals(0, CompanyBasePauseSettings.baseMinutes(context, 1))
        account("A")
        CompanyBasePauseSettings.savePause(oldFormContext, 1, 1, 720, 780)
        assertEquals(0, CompanyBasePauseSettings.baseMinutes(oldFormContext, 1))
        assertEquals(30, CompanyBasePauseSettings.baseMinutes(context, 1))
    }
    private fun document(text: String): com.amaury.pointage.v2.CompanyAgreementDocumentReaderV2.Result {
        val file = java.io.File.createTempFile("agreement-test-", ".txt", context.cacheDir)
        file.writeText(text)
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(file.readBytes())
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        return com.amaury.pointage.v2.CompanyAgreementDocumentReaderV2.Result(file, "accord.txt", "text/plain", hash,
            com.amaury.pointage.v2.CompanyAgreementImportPolicyV2.Kind.TEXT, text, null, false)
    }

    @Test fun rollbackOfAnInterruptedImportCannotDeleteAnotherAccountsIdenticalDocument() {
        val ownerA = SalaryAccountScope.bindContext(context)
        val a = com.amaury.pointage.v2.CompanyAgreementDocumentStoreV2.persist(ownerA, "same", document("accord identique"))
        assertNotNull(com.amaury.pointage.v2.CompanyAgreementDocumentStoreV2.resolveOwnedDocument(ownerA, a.relativePath))
        account("B")
        val ownerB = SalaryAccountScope.bindContext(context)
        val b = com.amaury.pointage.v2.CompanyAgreementDocumentStoreV2.persist(ownerB, "same", document("accord identique"))
        assertNotEquals(a.relativePath, b.relativePath)
        assertNull(com.amaury.pointage.v2.CompanyAgreementDocumentStoreV2.resolveOwnedDocument(ownerB, a.relativePath))
        assertNull(com.amaury.pointage.v2.CompanyAgreementDocumentStoreV2.resolveOwnedDocument(ownerA, a.relativePath))
        com.amaury.pointage.v2.CompanyAgreementDocumentStoreV2.rollback(ownerA, a)
        // The old account's metadata commit may have succeeded before logout: retain its original.
        assertTrue(java.io.File(context.filesDir, a.relativePath).exists())
        assertEquals("accord identique", com.amaury.pointage.v2.CompanyAgreementDocumentStoreV2.resolveOwnedDocument(ownerB, b.relativePath)?.readText())
        java.io.File(context.filesDir, b.relativePath).writeText("document modifié")
        assertNull(com.amaury.pointage.v2.CompanyAgreementDocumentStoreV2.resolveOwnedDocument(ownerB, b.relativePath))
    }

    @Test fun originalDocumentRequiresExplicitLegacyAttributionAndMatchingMetadata() {
        val source = document("ancien accord conservé")
        val relative = "company_agreements/company_1/${source.sha256}.txt"
        val original = java.io.File(context.filesDir, relative)
        original.parentFile!!.mkdirs()
        source.temporaryFile.copyTo(original)
        val record = com.amaury.pointage.v2.CompanyAgreementStoreV2.Agreement("legacy", "Accord", null, null, "Import local",
            com.amaury.pointage.v2.CompanyAgreementStoreV2.Status.IMPORTED, documentSha256 = source.sha256, documentPath = relative)
        val entries = com.amaury.pointage.v2.CompanyAgreementStoreV2.snapshotEntries(listOf(record))!!
        val oldCompany = context.getSharedPreferences("salary_company_company_1", Context.MODE_PRIVATE).edit()
        entries.forEach { (key, value) -> oldCompany.putString(key, value) }
        assertTrue(oldCompany.commit())
        assertTrue(context.getSharedPreferences("salary_companies_v2", Context.MODE_PRIVATE).edit()
            .putString("companies", """[{"id":"company_1","name":"A","siret":"","address":"","conventionName":"","idcc":""}]""").commit())
        assertNull(com.amaury.pointage.v2.CompanyAgreementDocumentStoreV2.resolveOwnedDocument(context, relative))
        val preview = com.amaury.pointage.v2.SalaryLegacyRecovery.preview(context)!!
        assertTrue(com.amaury.pointage.v2.SalaryLegacyRecovery.claim(context, preview.digest, true))
        assertEquals(original.canonicalFile, com.amaury.pointage.v2.CompanyAgreementDocumentStoreV2.resolveOwnedDocument(context, relative))
        assertTrue(original.exists())
        account("B")
        assertTrue(SalaryCompanyStore.createOrUpdate(context, company("B")))
        assertTrue(com.amaury.pointage.v2.CompanyAgreementStoreV2.save(context, "company_1", listOf(record)))
        assertNull(com.amaury.pointage.v2.CompanyAgreementDocumentStoreV2.resolveOwnedDocument(context, relative))
        assertTrue(original.exists())
    }

    @Test fun documentCleanupPreservesCommittedReferencesAndRemovesOnlyKnownUnreferencedImports() {
        assertTrue(SalaryCompanyStore.createOrUpdate(context, company("A")))
        val owner = SalaryAccountScope.bindContext(context)
        val orphan = com.amaury.pointage.v2.CompanyAgreementDocumentStoreV2.persist(owner, "company_1", document("copie sans référence"))
        com.amaury.pointage.v2.CompanyAgreementDocumentStoreV2.rollback(owner, orphan)
        assertFalse(java.io.File(context.filesDir, orphan.relativePath).exists())

        val source = document("copie déjà enregistrée")
        val saved = com.amaury.pointage.v2.CompanyAgreementDocumentStoreV2.persist(owner, "company_1", source)
        val agreement = com.amaury.pointage.v2.CompanyAgreementStoreV2.Agreement("saved", "Accord", null, null, "Import local",
            com.amaury.pointage.v2.CompanyAgreementStoreV2.Status.IMPORTED, documentSha256 = source.sha256, documentPath = saved.relativePath)
        assertTrue(com.amaury.pointage.v2.CompanyAgreementStoreV2.save(owner, "company_1", listOf(agreement)))
        com.amaury.pointage.v2.CompanyAgreementDocumentStoreV2.rollback(owner, saved)
        assertTrue(java.io.File(context.filesDir, saved.relativePath).exists())
        account("B")
        com.amaury.pointage.v2.CompanyAgreementDocumentStoreV2.rollback(owner, saved)
        assertTrue(java.io.File(context.filesDir, saved.relativePath).exists())
        account("A")
        assertNotNull(com.amaury.pointage.v2.CompanyAgreementDocumentStoreV2.resolveOwnedDocument(context, saved.relativePath))
    }

}
