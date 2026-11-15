package com.amaury.pointage

import android.app.Application
import android.content.Context
import android.net.Uri
import com.amaury.pointage.v2.*
import com.amaury.pointage.v2.ObservedPayslipDetailsV2 as Details
import com.amaury.pointage.v2.engine.PayslipDocumentParserV2
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application=Application::class,sdk=[28],shadows=[GpsDeliveryAuthShadow::class],instrumentedPackages=["com.google.firebase.auth"])
class ObservedPayslipDetailsV2Test {
    private val context get()=RuntimeEnvironment.getApplication() as Context
    private lateinit var auth:FirebaseAuth
    private val company=SalaryCompanyStore.Company("observed-company","Synthetic company","")
    private lateinit var record:V2PayslipStore.Record
    private fun account(id:String){val user=Mockito.mock(FirebaseUser::class.java);Mockito.`when`(user.uid).thenReturn(id);Mockito.`when`(auth.currentUser).thenReturn(user);SalaryAccountScope.capture()}
    @Before fun setup(){
        auth=Mockito.mock(FirebaseAuth::class.java);GpsDeliveryAuthShadow.auth=auth;account("observed-A")
        SalaryAccountScope.preferences(context,V2PayslipStore.PREFS).edit().clear().commit()
        assertTrue(SalaryCompanyStore.createOrUpdate(context,company))
        record=V2PayslipStore.add(context,2025,2,Uri.parse("content://synthetic/bulletin"),"application/pdf",1234.56,987.65,true,company.id)!!
    }
    private fun document()=Details.Document("2025-03","Synthetic bulletin March",10L,listOf(
        Details.Row("Contribution", "1234.56", "1.2345", "15.24", "2.3456", "35.27", "1503.4"),
        Details.Row("Contribution", null, null, "0", null, "-21.45")))
    private fun saved():Details.Snapshot {assertTrue(Details.save(context,Details.read(context,record.id),document(),true));return Details.read(context,record.id)}
    @Test fun sourceOnlyPresentationWorksWithoutAnyContractOrTimeData(){
        val snapshot=saved();assertTrue(ObservedPayslipPresentationV2.available(snapshot))
        val sections=ObservedPayslipPresentationV2.sections(snapshot)
        assertEquals(2,sections.size);assertTrue(sections.first().rows.any{it[3]=="1234,56"})
        assertEquals(listOf("Contribution","Contribution"),sections.last().rows.map{it[0]})
        assertEquals("1503,4",sections.last().rows[0][4]);assertEquals("-21,45",sections.last().rows[1][6])
        assertEquals("—",sections.last().rows[1][1]);assertEquals("0",sections.last().rows[1][3])
        assertTrue(context.getSharedPreferences("horatrack_v2_test_runtime",0).all.isEmpty())
    }
    @Test fun rejectsWrongPeriodAndFractionalCentsWithoutWriting(){
        val initial=Details.read(context,record.id)
        assertFalse(Details.save(context,initial,document().copy(sourcePeriod="2025-04"),true))
        assertFalse(Details.save(context,initial,document().copy(rows=listOf(Details.Row("fraction",employeeAmount="1.001"))),true))
        assertNull(Details.read(context,record.id).document)
        assertEquals("1.2345",Details.decimal("1,2345",4));assertNull(Details.decimal("1.23456",4))
        assertNull(Details.decimal("1000000000.01",2));assertNull(Details.decimal("NaN",2));assertNull(Details.decimal("1e2",2))
    }
    @Test fun duplicateJsonPropertiesAndInvalidTypesFailClosed(){
        val snap=saved();val raw=Details.encode(snap.binding!!,document())
        assertNull(Details.decode(raw.replaceFirst("\"schema\":1","\"schema\":1,\"schema\":1"),record))
        assertNull(Details.decode(raw.replace("\"employeeAmount\":\"15.24\"","\"employeeAmount\":15.24"),record))
        assertNull(Details.decode(raw.replace("\"sourcePeriod\":\"2025-03\"","\"sourcePeriod\":\"not-a-period\""),record))
    }
    @Test fun aggregateOrEvidenceChangeRequiresReconfirmation(){
        saved()
        assertTrue(PayslipObservedValuesStoreV2.put(context,record.id,mapOf(PayslipDocumentParserV2.KEY_MUTUAL_EMPLOYEE to 16.25),mapOf(PayslipDocumentParserV2.KEY_MUTUAL_EMPLOYEE to "First source")))
        var stale=Details.read(context,record.id);assertTrue(stale.needsReconfirmation);assertFalse(ObservedPayslipPresentationV2.available(stale))
        assertTrue(Details.save(context,stale,document().copy(confirmedAt=11),true))
        assertTrue(PayslipObservedValuesStoreV2.put(context,record.id,mapOf(PayslipDocumentParserV2.KEY_MUTUAL_EMPLOYEE to 16.25),mapOf(PayslipDocumentParserV2.KEY_MUTUAL_EMPLOYEE to "Corrected source")))
        stale=Details.read(context,record.id);assertTrue(stale.needsReconfirmation)
    }
    @Test fun staleDraftCannotOverwriteASecondSaveOrChangedParent(){
        val initial=Details.read(context,record.id);assertTrue(Details.save(context,initial,document(),true))
        assertFalse(Details.save(context,initial,document().copy(source="Other"),true))
        val captured=Details.read(context,record.id)
        SalaryAccountScope.preferences(context,V2PayslipStore.PREFS).edit().putString(V2PayslipStore.KEY_ITEMS,V2PayslipStore.encodeRecords(listOf(record.copy(gross=1299.0)))).commit()
        assertTrue(Details.read(context,record.id).needsReconfirmation)
        assertFalse(Details.save(context,captured,document(),true))
    }
    @Test fun accountChangeRejectsSnapshotEvenWhenRestoredDataAreIdentical(){
        val captured=saved();val source=SalaryAccountScope.preferences(context,V2PayslipStore.PREFS).all
        account("observed-B");assertTrue(SalaryCompanyStore.createOrUpdate(context,company))
        val prefs=SalaryAccountScope.preferences(context,V2PayslipStore.PREFS);assertTrue(V2PreferenceValues.replace(prefs,source))
        val before=prefs.all
        assertFalse(Details.save(context,captured,document().copy(source="Forbidden"),true));assertEquals(before,prefs.all)
    }
    @Test fun removedCompanyAndRemovedRecordCannotAcceptDraft(){
        val captured=saved();assertTrue(SalaryCompanyStore.remove(context,company.id));assertFalse(Details.save(context,captured,document(),true))
        assertTrue(SalaryCompanyStore.createOrUpdate(context,company));assertTrue(V2PayslipStore.remove(context,record.id))
        assertFalse(SalaryAccountScope.preferences(context,V2PayslipStore.PREFS).contains(Details.PREFIX+record.id))
        assertFalse(Details.save(context,captured,document(),true))
    }
    @Test fun boundedRowsAndLabelsAndConfirmationAreRequired(){
        val initial=Details.read(context,record.id)
        assertFalse(Details.save(context,initial,document(),false))
        assertFalse(Details.valid(document().copy(rows=List(151){Details.Row("x",employeeAmount="1")}),record))
        assertFalse(Details.valid(document().copy(rows=listOf(Details.Row("x".repeat(161),employeeAmount="1"))),record))
        assertFalse(Details.valid(document().copy(rows=listOf(Details.Row("empty"))),record))
    }
    @Test fun backupRestoreCarriesDetailedRowsAndOriginalRecordTogether(){
        val snapshot=saved();val prefs=SalaryAccountScope.preferences(context,V2PayslipStore.PREFS)
        val payload=V2PreferenceValues.encode(prefs.all)
        assertTrue(V2BackupManager.isRestorablePreferencePayload(V2PayslipStore.PREFS,payload))
        val backup=JSONObject().put("formatVersion",6).put("accountScope","uid:observed-A").put("salaryOwner","uid:observed-A")
            .put("salaryOwnership","account-scoped-v1").put("preferences",JSONObject().put(V2PayslipStore.PREFS,payload))
        prefs.edit().clear().commit()
        val result=V2BackupManager.restoreFromJson(context,backup.toString())
        assertTrue(result.exceptionOrNull()?.stackTraceToString(),result.isSuccess)
        assertEquals(snapshot.document,Details.read(context,record.id).document)
        assertFalse(Details.read(context,record.id).needsReconfirmation)
        assertTrue(V2BackupManager.restoreFromJson(context,backup.toString()).isSuccess)
        assertEquals(1,V2PayslipStore.forCompany(context,company.id).size)
    }
    @Test fun changedParentPeriodPreservesRowsForExplicitReconfirmation() {
        saved()
        SalaryAccountScope.preferences(context,V2PayslipStore.PREFS).edit().putString(V2PayslipStore.KEY_ITEMS,V2PayslipStore.encodeRecords(listOf(record.copy(month=3)))).commit()
        val stale=Details.read(context,record.id)
        assertTrue(stale.reliable);assertTrue(stale.needsReconfirmation);assertEquals(document().rows,stale.document!!.rows)
        assertFalse(Details.save(context,stale,document(),true))
        assertTrue(Details.save(context,stale,document().copy(sourcePeriod="2025-04"),true))
    }
    @Test fun unconfirmedExtractedTotalsAreNotPresentedAsConfirmed() {
        val unconfirmed=record.copy(confirmedByUser=false)
        SalaryAccountScope.preferences(context,V2PayslipStore.PREFS).edit().putString(V2PayslipStore.KEY_ITEMS,V2PayslipStore.encodeRecords(listOf(unconfirmed))).commit()
        val initial=Details.read(context,record.id);assertTrue(Details.save(context,initial,document(),true))
        assertTrue(ObservedPayslipPresentationV2.sections(Details.read(context,record.id)).first().rows.isEmpty())
    }

}
