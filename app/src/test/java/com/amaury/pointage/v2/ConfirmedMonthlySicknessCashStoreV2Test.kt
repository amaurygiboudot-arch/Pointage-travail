package com.amaury.pointage.v2

import com.amaury.pointage.GpsDeliveryAuthShadow
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import org.mockito.Mockito
import android.app.Application
import android.content.Context
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], shadows = [GpsDeliveryAuthShadow::class], instrumentedPackages = ["com.google.firebase.auth"])
class ConfirmedMonthlySicknessCashStoreV2Test {
    private val context get() = RuntimeEnvironment.getApplication() as Context
    private val record = ConfirmedMonthlySicknessCashV2.Record("a", "2026-10", 100.0, null, "Décompte CPAM 42", 1L)
    private fun json() = JSONObject().put("version",1).put("companyId","a").put("period","2026-10")
        .put("direct",100.0).put("subrogated",JSONObject.NULL).put("source","Décompte CPAM 42").put("confirmedAt",1L)
    @Before fun reset() {
        val auth = Mockito.mock(FirebaseAuth::class.java)
        val user = Mockito.mock(FirebaseUser::class.java)
        Mockito.`when`(user.uid).thenReturn("sickness-store-owner")
        Mockito.`when`(auth.currentUser).thenReturn(user)
        GpsDeliveryAuthShadow.auth = auth
        SalaryAccountScope.preferences(context, "salary_confirmed_sickness_cash_v2").edit().clear().commit()
    }
    @Test fun roundtripReplacementScopeAndCorruptOverwriteGuard() {
        assertFalse(ConfirmedMonthlySicknessCashV2.save(context,record,false))
        assertTrue(ConfirmedMonthlySicknessCashV2.save(context,record,true))
        assertEquals(record, ConfirmedMonthlySicknessCashV2.read(context,"a","2026-10").record)
        assertNull(ConfirmedMonthlySicknessCashV2.read(context,"b","2026-10").record)
        assertNull(ConfirmedMonthlySicknessCashV2.read(context,"a","2026-09").record)
        val replacement=record.copy(directEmployeeNetBeforeTax=null,subrogatedEmployerNetBeforeTax=200.0)
        assertTrue(ConfirmedMonthlySicknessCashV2.save(context,replacement,true))
        assertEquals(replacement,ConfirmedMonthlySicknessCashV2.read(context,"a","2026-10").record)
        SalaryAccountScope.preferences(context, "salary_confirmed_sickness_cash_v2").edit().putString("a|2026-10","broken").commit()
        assertFalse(ConfirmedMonthlySicknessCashV2.read(context,"a","2026-10").reliable)
        assertFalse(ConfirmedMonthlySicknessCashV2.save(context,record,true))
        assertTrue(ConfirmedMonthlySicknessCashV2.remove(context,"a","2026-10"))
        assertTrue(ConfirmedMonthlySicknessCashV2.save(context,record,true))
    }
    @Test fun decoderRejectsCoercionMissingKeysFractionalIntegersAndCrossScope() {
        val bad = listOf(json().put("version",1.5),json().put("version","1"),json().put("confirmedAt",1.5),
            json().put("confirmedAt","1"),json().put("companyId",12),json().put("period",12),
            json().put("source",12),json().put("direct","100.00"),json().put("direct",true),
            json().apply{remove("direct")},json().apply{remove("subrogated")},json().put("companyId","b"),
            json().put("period","2026-09"),json().put("confirmedAt",1e30))
        bad.forEach { assertFalse(it.toString(), ConfirmedMonthlySicknessCashV2.decode(it.toString(),"a","2026-10").reliable) }
        assertEquals(record,ConfirmedMonthlySicknessCashV2.decode(json().toString(),"a","2026-10").record)
    }
    @Test fun wrongPreferenceTypeBlocksReadingAndOverwriteWithoutCrashing() {
        SalaryAccountScope.preferences(context, "salary_confirmed_sickness_cash_v2").edit().putInt("a|2026-10",42).commit()
        assertFalse(ConfirmedMonthlySicknessCashV2.read(context,"a","2026-10").reliable)
        assertFalse(ConfirmedMonthlySicknessCashV2.save(context,record,true))
    }
}
