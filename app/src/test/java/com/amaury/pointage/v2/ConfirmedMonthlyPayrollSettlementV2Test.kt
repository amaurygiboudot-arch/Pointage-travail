package com.amaury.pointage.v2

import android.app.Application
import android.content.Context
import com.amaury.pointage.GpsDeliveryAuthShadow
import com.amaury.pointage.SalaryCompanyStore
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application=Application::class,sdk=[28],shadows=[GpsDeliveryAuthShadow::class],instrumentedPackages=["com.google.firebase.auth"])
class ConfirmedMonthlyPayrollSettlementV2Test {
    private val context get()=RuntimeEnvironment.getApplication() as Context
    private lateinit var auth: FirebaseAuth
    private val company=SalaryCompanyStore.Company("fixture","Synthetic company","")
    private val record=ConfirmedMonthlyPayrollSettlementV2.Record("fixture","2026-04",31.25,24.0,12.5,3.0,1600.0,1717.0,"Synthetic source",100L,true)
    private fun account(uid:String) { val user=Mockito.mock(FirebaseUser::class.java);Mockito.`when`(user.uid).thenReturn(uid);Mockito.`when`(auth.currentUser).thenReturn(user);SalaryAccountScope.capture() }
    private fun prefs()=SalaryAccountScope.preferences(context,ConfirmedMonthlyPayrollSettlementV2.PREFS)
    @Before fun setup() {
        auth=Mockito.mock(FirebaseAuth::class.java);GpsDeliveryAuthShadow.auth=auth;account("settlement-A")
        prefs().edit().clear().commit()
        assertTrue(SalaryCompanyStore.createOrUpdate(context,company))
    }
    private fun encoded(value:ConfirmedMonthlyPayrollSettlementV2.Record=record)=ConfirmedMonthlyPayrollSettlementV2.encode(value)
    private fun decoded(raw:String)=ConfirmedMonthlyPayrollSettlementV2.decode(raw,record.companyId,record.period)

    @Test fun roundTripKeepsUnknownDistinctFromConfirmedZeroAndRequiresConsent() {
        val partial=record.copy(transportReimbursement=null,netSocialObserved=0.0,adjustmentsComplete=false)
        assertFalse(ConfirmedMonthlyPayrollSettlementV2.save(context,partial,false))
        assertTrue(ConfirmedMonthlyPayrollSettlementV2.save(context,partial,true))
        assertEquals(partial,ConfirmedMonthlyPayrollSettlementV2.read(context,"fixture","2026-04").record)
        assertNull(ConfirmedMonthlyPayrollSettlementV2.read(context,"fixture","2026-05").record)
    }
    @Test fun exactDecimalTokensRejectFractionalCentsOverflowAndBinaryAlias() {
        for(token in listOf("0.005","1.001","90071992547409.91","90071992547409.92","1e400","-0.01","1.00000000000000000000000000001")) {
            assertNull(token,ConfirmedMonthlyPayrollSettlementV2.parseAmount(token))
            assertFalse(token,decoded(encoded().replace("31.25",token)).reliable)
        }
        assertEquals(0.01,ConfirmedMonthlyPayrollSettlementV2.parseAmount("0.0100")!!,0.0)
        assertEquals(1.0,ConfirmedMonthlyPayrollSettlementV2.parseAmount("1e0")!!,0.0)
        assertEquals(0.0,ConfirmedMonthlyPayrollSettlementV2.parseAmount("0")!!,0.0)
    }
    @Test fun schemaRejectsCoercionDuplicatesUnknownFieldsMissingFieldsAndWrongContext() {
        val raw=encoded()
        val malformed=listOf(JSONObject(raw).put("version","1").toString(),JSONObject(raw).put("confirmedAt",1.5).toString(),
            JSONObject(raw).put("confirmedAt","100").toString(),JSONObject(raw).put("source",12).toString(),
            JSONObject(raw).put("adjustmentsComplete",1).toString(),JSONObject(raw).put("garnishment","24").toString(),
            JSONObject(raw).apply{remove("garnishment")}.toString(),JSONObject(raw).put("companyId","other").toString(),
            JSONObject(raw).put("period","2026-05").toString(),JSONObject(raw).put("unexpected",true).toString(),
            raw.dropLast(1)+",\"source\":\"duplicate\"}",raw+" {}")
        malformed.forEach{assertFalse(it,decoded(it).reliable)}
        assertEquals(record,decoded(raw).record)
    }
    @Test fun invalidRecordsNeverPersist() {
        listOf(record.copy(period="2026-4"),record.copy(period="0000-04"),record.copy(companyId="bad|id"),
            record.copy(source=" "),record.copy(source="x".repeat(501)),record.copy(confirmedAt=0),record.copy(garnishment=-1.0),
            record.copy(otherAddition=Double.NaN),record.copy(netPaidObserved=Double.POSITIVE_INFINITY),
            record.copy(transportReimbursement=null)).forEach{assertFalse(ConfirmedMonthlyPayrollSettlementV2.save(context,it,true))}
        assertTrue(prefs().all.isEmpty())
    }
    @Test fun corruptOrWrongPreferenceTypeIsPreservedUntilExplicitRemoval() {
        for(wrongType in listOf(false,true)) {
            if(wrongType)prefs().edit().putInt("fixture|2026-04",42).commit() else prefs().edit().putString("fixture|2026-04","broken").commit()
            val before=prefs().all
            assertFalse(ConfirmedMonthlyPayrollSettlementV2.read(context,"fixture","2026-04").reliable)
            assertFalse(ConfirmedMonthlyPayrollSettlementV2.save(context,record,true))
            assertEquals(before,prefs().all)
            assertTrue(ConfirmedMonthlyPayrollSettlementV2.remove(context,"fixture","2026-04"))
        }
    }
    @Test fun explicitCorruptDeletionDoesNotEraseAChangedOrRepairedSource() {
        prefs().edit().putString("fixture|2026-04","broken-one").commit()
        val initial=ConfirmedMonthlyPayrollSettlementV2.read(context,"fixture","2026-04")
        prefs().edit().putString("fixture|2026-04","broken-two").commit()
        assertFalse(ConfirmedMonthlyPayrollSettlementV2.remove(context,"fixture","2026-04",initial))
        prefs().edit().putString("fixture|2026-04",encoded()).commit()
        assertFalse(ConfirmedMonthlyPayrollSettlementV2.remove(context,"fixture","2026-04",initial))
        assertEquals(record,ConfirmedMonthlyPayrollSettlementV2.read(context,"fixture","2026-04").record)
    }
    @Test fun corruptStringSetReplacementCannotReuseAnAmbiguousCollectionRendering() {
        val before=setOf("a","b")
        val after=setOf("a, b")
        assertEquals(before.toString(),after.toString())
        prefs().edit().putStringSet("fixture|2026-04",before).commit()
        val initial=ConfirmedMonthlyPayrollSettlementV2.read(context,"fixture","2026-04")
        assertFalse(initial.reliable);assertNotNull(initial.revisionToken)
        prefs().edit().putStringSet("fixture|2026-04",after).commit()
        val current=ConfirmedMonthlyPayrollSettlementV2.read(context,"fixture","2026-04")
        assertNotEquals(initial.revisionToken,current.revisionToken)
        assertFalse(ConfirmedMonthlyPayrollSettlementV2.remove(context,"fixture","2026-04",initial))
        assertEquals(after,prefs().getStringSet("fixture|2026-04",null))
        assertTrue(ConfirmedMonthlyPayrollSettlementV2.remove(context,"fixture","2026-04",current))
        assertFalse(prefs().contains("fixture|2026-04"))
    }
    @Test fun revisionEncodingPreservesScalarTypesSetMembersAndUtf16Exactly() {
        fun token(value:Any?)=ConfirmedMonthlyPayrollSettlementV2.revisionToken(value)
        assertEquals(token(linkedSetOf("b","a")),token(linkedSetOf("a","b")))
        assertNotEquals(token(1),token(1L))
        assertNotEquals(token(1),token("1"))
        assertNotEquals(token(true),token("true"))
        assertNotEquals(token(0.0f),token(-0.0f))
        assertNotEquals(token(Float.fromBits(0x7fc00001)),token(Float.fromBits(0x7fc00002)))
        assertNotEquals(token("\uD800"),token("?"))
        assertNotEquals(token("\uD800"),token("\uD801"))
        assertNull(token(listOf("unsupported")))
        assertNull(token(setOf(1)))
    }
    @Test fun fingerprintBoundsRefuseUnverifiableDeletionWhileMissingSourceIsIdempotent() {
        val oversized="x".repeat(262_145)
        prefs().edit().putString("fixture|2026-04",oversized).commit()
        val initial=ConfirmedMonthlyPayrollSettlementV2.read(context,"fixture","2026-04")
        assertFalse(initial.reliable);assertNull(initial.revisionToken)
        assertFalse(ConfirmedMonthlyPayrollSettlementV2.remove(context,"fixture","2026-04",initial))
        assertFalse(ConfirmedMonthlyPayrollSettlementV2.remove(context,"fixture","2026-04"))
        assertEquals(oversized,prefs().getString("fixture|2026-04",null))
        val missing=ConfirmedMonthlyPayrollSettlementV2.read(context,"fixture","2026-05")
        assertTrue(ConfirmedMonthlyPayrollSettlementV2.remove(context,"fixture","2026-05",missing))
    }
    @Test fun staleAccountLeaseCannotReadWriteOrDeleteTheNextAccount() {
        assertTrue(ConfirmedMonthlyPayrollSettlementV2.save(context,record,true))
        val bound=SalaryAccountScope.bindContext(context)
        account("settlement-B");assertTrue(SalaryCompanyStore.createOrUpdate(context,company))
        assertNull(ConfirmedMonthlyPayrollSettlementV2.read(context,"fixture","2026-04").record)
        assertFalse(ConfirmedMonthlyPayrollSettlementV2.read(bound,"fixture","2026-04").reliable)
        assertFalse(ConfirmedMonthlyPayrollSettlementV2.save(bound,record.copy(netPaidObserved=99.0),true))
        assertFalse(ConfirmedMonthlyPayrollSettlementV2.remove(bound,"fixture","2026-04"))
        assertTrue(prefs().all.isEmpty())
        account("settlement-A")
        assertEquals(record,ConfirmedMonthlyPayrollSettlementV2.read(context,"fixture","2026-04").record)
        assertFalse(ConfirmedMonthlyPayrollSettlementV2.save(bound,record.copy(netPaidObserved=98.0),true))
    }
    @Test fun removedCompanyAndConcurrentSourceChangeCannotOverwriteEvidence() {
        val initial=ConfirmedMonthlyPayrollSettlementV2.read(context,"fixture","2026-04")
        assertTrue(ConfirmedMonthlyPayrollSettlementV2.save(context,record,true,initial))
        assertFalse(ConfirmedMonthlyPayrollSettlementV2.save(context,record.copy(source="stale draft"),true,initial))
        assertFalse(ConfirmedMonthlyPayrollSettlementV2.remove(context,"fixture","2026-04",initial))
        val before=prefs().all
        assertTrue(SalaryCompanyStore.remove(context,company.id))
        assertFalse(ConfirmedMonthlyPayrollSettlementV2.save(context,record,true))
        assertFalse(ConfirmedMonthlyPayrollSettlementV2.read(context,"fixture","2026-04").reliable)
        assertEquals(before,prefs().all)
    }
    private fun backup(value:ConfirmedMonthlyPayrollSettlementV2.Record,scope:String="uid:settlement-A"):String =
        JSONObject().put("formatVersion",6).put("accountScope",scope).put("salaryOwner",scope).put("salaryOwnership","account-scoped-v1").put("preferences",JSONObject().put(
            ConfirmedMonthlyPayrollSettlementV2.PREFS,JSONObject().put("${value.companyId}|${value.period}",JSONObject().put("t","s").put("v",encoded(value))))).toString()
    @Test fun restoreMergesOtherMonthsIdempotentlyButConflictsNeverReplaceLocalInput() {
        assertTrue(ConfirmedMonthlyPayrollSettlementV2.save(context,record,true))
        val another=record.copy(period="2026-05",netPaidObserved=1800.0)
        val restore=V2BackupManager.restoreFromJson(context,backup(another));assertTrue(restore.exceptionOrNull()?.stackTraceToString(),restore.isSuccess)
        assertEquals(another,ConfirmedMonthlyPayrollSettlementV2.read(context,"fixture","2026-05").record)
        assertTrue(V2BackupManager.restoreFromJson(context,backup(another)).isSuccess)
        val before=prefs().all
        assertTrue(V2BackupManager.restoreFromJson(context,backup(record.copy(netPaidObserved=999.0))).isFailure)
        assertEquals(before,prefs().all)
        val oldBackup=JSONObject().put("formatVersion",5).put("accountScope","uid:settlement-A").put("preferences",JSONObject().put("appearance_settings",JSONObject())).toString()
        assertTrue(V2BackupManager.restoreFromJson(context,oldBackup).isSuccess)
        assertEquals(before,prefs().all)
        assertTrue(V2BackupManager.restoreFromJson(context,backup(another,"uid:settlement-B")).isFailure)
        assertEquals(before,prefs().all)
    }
    @Test fun restoredSourceInvalidatesCapturedFormBeforeItsWriteOrDelete() {
        val bound=SalaryAccountScope.bindContext(context)
        val initial=ConfirmedMonthlyPayrollSettlementV2.read(bound,"fixture","2026-04")
        assertTrue(V2BackupManager.restoreFromJson(context,backup(record)).isSuccess)
        assertFalse(ConfirmedMonthlyPayrollSettlementV2.save(bound,record.copy(source="stale form"),true,initial))
        assertFalse(ConfirmedMonthlyPayrollSettlementV2.remove(bound,"fixture","2026-04",initial))
        assertEquals(record,ConfirmedMonthlyPayrollSettlementV2.read(context,"fixture","2026-04").record)
    }
    @Test fun legacyPersonalSourceIsQuarantinedAndNeverSilentlyAttributed() {
        val modernWithoutOwner=JSONObject(backup(record)).apply{remove("salaryOwner")}
        assertTrue(V2BackupManager.restoreFromJson(context,modernWithoutOwner.toString()).isFailure)
        assertTrue(prefs().all.isEmpty())
        val legacy=JSONObject(backup(record)).put("formatVersion",5).apply{remove("salaryOwner");remove("salaryOwnership")}
        val result=V2BackupManager.restoreFromJson(context,legacy.toString()).getOrThrow()
        assertEquals(1,result.quarantinedSalaryFiles)
        assertEquals(0,result.restoredFiles)
        assertNotNull(result.recoveryId)
        assertTrue(prefs().all.isEmpty())
        assertNull(ConfirmedMonthlyPayrollSettlementV2.read(context,"fixture","2026-04").record)
        assertTrue(V2BackupManager.legacySalaryRecoveries(context).any{it.id==result.recoveryId})
    }
    @Test fun restoreRejectsCorruptTokensBeforeWritingAnything() {
        assertTrue(ConfirmedMonthlyPayrollSettlementV2.save(context,record,true))
        val before=prefs().all
        assertTrue(V2BackupManager.restoreFromJson(context,backup(record.copy(period="2026-05")).replace("31.25","31.251")).isFailure)
        assertEquals(before,prefs().all)
    }
    @Test fun removedCompanyFactsRemainOpaqueAndRestorableWithoutBlockingOtherData() {
        assertTrue(ConfirmedMonthlyPayrollSettlementV2.save(context,record,true))
        assertTrue(SalaryCompanyStore.remove(context,company.id))
        assertFalse(ConfirmedMonthlyPayrollSettlementV2.read(context,company.id,record.period).reliable)
        val before=prefs().all
        val snapshot=V2BackupManager.snapshot(context)
        assertTrue(snapshot.getJSONObject("preferences").has(ConfirmedMonthlyPayrollSettlementV2.PREFS))
        assertTrue(prefs().edit().clear().commit())
        val restored=V2BackupManager.restoreFromJson(context,snapshot.toString())
        assertTrue(restored.exceptionOrNull()?.stackTraceToString(),restored.isSuccess)
        assertEquals(before,prefs().all)
        assertTrue(V2BackupManager.restoreFromJson(context,backup(record)).isSuccess)
        assertEquals(before,prefs().all)
        assertFalse(ConfirmedMonthlyPayrollSettlementV2.read(context,company.id,record.period).reliable)
        val other=record.copy(companyId="orphan")
        assertTrue(V2BackupManager.restoreFromJson(context,backup(other)).isSuccess)
        assertTrue(prefs().contains("orphan|2026-04"))
        assertFalse(ConfirmedMonthlyPayrollSettlementV2.read(context,"orphan","2026-04").reliable)
    }
    @Test fun registryScopesAndTransfersNewDomainWithoutTreatingItAsLegacy() {
        assertTrue(SalaryPreferenceRegistry.isPersonal(ConfirmedMonthlyPayrollSettlementV2.PREFS))
        assertTrue(SalaryPreferenceRegistry.isTransferable(ConfirmedMonthlyPayrollSettlementV2.PREFS))
        assertNotEquals(SalaryPreferenceRegistry.fileName(ConfirmedMonthlyPayrollSettlementV2.PREFS,"uid:a"),SalaryPreferenceRegistry.fileName(ConfirmedMonthlyPayrollSettlementV2.PREFS,"uid:b"))
        assertFalse(SalaryPreferenceRegistry.legacyNames(context).contains(ConfirmedMonthlyPayrollSettlementV2.PREFS))
    }
}
