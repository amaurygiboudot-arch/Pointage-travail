package com.amaury.pointage

import android.content.Context
import com.google.firebase.firestore.FirebaseFirestore
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Table centrale des paramètres de paie.
 * Firestore: payroll_rates/current
 * Les clients ne font que lire. Les règles Firestore doivent réserver l'écriture à l'administration.
 */
object PayrollRatesFirebase {
    private const val PREFS = "payroll_rates_cache"
    private const val COLLECTION = "payroll_rates"
    private const val DOCUMENT = "current"
    private val running = AtomicBoolean(false)

    data class Rates(
        val version: String,
        val effectiveFrom: String,
        val employeeContributionRate: Double,
        val nightPremium: Double,
        val saturdayPremium: Double,
        val sundayPremium: Double,
        val nightStartMinute: Int,
        val nightEndMinute: Int,
        val updatedAt: Long
    )

    fun cached(context: Context): Rates {
        val p=context.getSharedPreferences(PREFS,Context.MODE_PRIVATE)
        return Rates(
            p.getString("version","builtin-2026")?:"builtin-2026",
            p.getString("effectiveFrom","2026-01-01")?:"2026-01-01",
            p.getFloat("employeeContributionRate",0.22f).toDouble(),
            p.getFloat("nightPremium",0.25f).toDouble(),
            p.getFloat("saturdayPremium",0.25f).toDouble(),
            p.getFloat("sundayPremium",0.50f).toDouble(),
            p.getInt("nightStartMinute",21*60),p.getInt("nightEndMinute",6*60),p.getLong("updatedAt",0L)
        )
    }

    fun refresh(context: Context, forced:Boolean=false, done:((Boolean)->Unit)?=null){
        val app=context.applicationContext
        if(!forced && !running.compareAndSet(false,true)){done?.invoke(false);return}
        if(forced) running.set(true)
        FirebaseFirestore.getInstance().collection(COLLECTION).document(DOCUMENT).get()
            .addOnSuccessListener{d->
                if(!d.exists()){running.set(false);done?.invoke(false);return@addOnSuccessListener}
                val version=d.getString("version")?.trim().orEmpty(); if(version.isBlank()){running.set(false);done?.invoke(false);return@addOnSuccessListener}
                fun rate(name:String, fallback:Double)=d.getDouble(name)?.coerceIn(0.0,1.0)?:fallback
                val p=app.getSharedPreferences(PREFS,Context.MODE_PRIVATE)
                p.edit().putString("version",version)
                    .putString("effectiveFrom",d.getString("effectiveFrom")?:"")
                    .putFloat("employeeContributionRate",rate("employeeContributionRate",.22).toFloat())
                    .putFloat("nightPremium",rate("nightPremium",.25).toFloat())
                    .putFloat("saturdayPremium",rate("saturdayPremium",.25).toFloat())
                    .putFloat("sundayPremium",rate("sundayPremium",.50).toFloat())
                    .putInt("nightStartMinute",(d.getLong("nightStartMinute")?:1260L).toInt().coerceIn(0,1439))
                    .putInt("nightEndMinute",(d.getLong("nightEndMinute")?:360L).toInt().coerceIn(0,1439))
                    .putLong("updatedAt",System.currentTimeMillis()).apply()
                running.set(false);done?.invoke(true)
            }.addOnFailureListener{running.set(false);done?.invoke(false)}
    }
}
