package com.amaury.pointage.v2

import android.content.Context
import android.net.Uri
import com.amaury.pointage.ConventionCatalog
import com.amaury.pointage.PointageStore
import com.amaury.pointage.SalaryCalculator
import com.amaury.pointage.v2.engine.PayslipEngineV2
import com.amaury.pointage.v2.engine.PayslipComparisonV2
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Stockage local des bulletins importés. Aucun contenu médical ou secret d'authentification. */
object V2PayslipStore {
    private const val PREFS = "horatrack_v2_payslips"
    private const val KEY_ITEMS = "items"

    data class Record(
        val id:String,
        val year:Int,
        val month:Int,
        val sourceUri:String,
        val sourceMime:String?,
        val gross:Double?,
        val net:Double?,
        val extractionConfidence:Double,
        val confirmedByUser:Boolean,
        val importedAtMs:Long
    )

    fun add(
        context: Context,
        year: Int,
        month: Int,
        uri: Uri,
        mime: String?,
        gross: Double?,
        net: Double?,
        confirmedByUser: Boolean
    ): Record {
        val record = Record(
            id = UUID.randomUUID().toString(),
            year = year,
            month = month.coerceIn(0, 11),
            sourceUri = uri.toString(),
            sourceMime = mime,
            gross = gross,
            net = net,
            extractionConfidence = if (confirmedByUser && (gross != null || net != null)) 1.0 else 0.0,
            confirmedByUser = confirmedByUser,
            importedAtMs = System.currentTimeMillis()
        )
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val a = runCatching { JSONArray(prefs.getString(KEY_ITEMS, "[]") ?: "[]") }.getOrElse { JSONArray() }
        a.put(toJson(record))
        prefs.edit().putString(KEY_ITEMS, a.toString()).apply()
        return record
    }

    fun all(context: Context): List<Record> {
        val raw = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_ITEMS, "[]") ?: "[]"
        val a = runCatching { JSONArray(raw) }.getOrElse { JSONArray() }
        return buildList {
            for (i in 0 until a.length()) a.optJSONObject(i)?.let(::fromJson)?.let(::add)
        }.sortedByDescending { it.importedAtMs }
    }

    fun latest(context: Context): Record? = all(context).firstOrNull()

    fun comparison(context: Context, record: Record): PayslipComparisonV2? {
        val profile = V2ProfileStore.load(context, 1)
        val rate = profile.contract?.grossHourlyRate ?: return null
        val idcc = profile.employer?.collectiveAgreementId.orEmpty()
        val convention = ConventionCatalog.findByIdcc(idcc)
            ?: ConventionCatalog.conventions.firstOrNull { it.idcc.isBlank() }
            ?: return null
        val expected = SalaryCalculator.calculate(
            PointageStore.load(context),
            record.year,
            record.month,
            rate,
            convention
        )
        val observed = linkedMapOf<String, Double>()
        record.gross?.let { observed["Brut"] = it }
        val expectedMap = linkedMapOf<String, Double>("Brut" to expected.monthlyEstimatedGross)
        return PayslipEngineV2.compare(expectedMap, observed, tolerance = 0.02)
    }

    private fun toJson(r: Record) = JSONObject()
        .put("id", r.id)
        .put("year", r.year)
        .put("month", r.month)
        .put("uri", r.sourceUri)
        .put("mime", r.sourceMime ?: JSONObject.NULL)
        .put("gross", r.gross ?: JSONObject.NULL)
        .put("net", r.net ?: JSONObject.NULL)
        .put("confidence", r.extractionConfidence)
        .put("confirmed", r.confirmedByUser)
        .put("importedAt", r.importedAtMs)

    private fun fromJson(o: JSONObject): Record? {
        val id = o.optString("id").takeIf { it.isNotBlank() } ?: return null
        val year = o.optInt("year", 0).takeIf { it in 2000..2200 } ?: return null
        val month = o.optInt("month", -1).takeIf { it in 0..11 } ?: return null
        val uri = o.optString("uri").takeIf { it.isNotBlank() } ?: return null
        return Record(
            id, year, month, uri,
            o.optString("mime").takeIf { it.isNotBlank() && it != "null" },
            o.opt("gross").let { (it as? Number)?.toDouble() },
            o.opt("net").let { (it as? Number)?.toDouble() },
            o.optDouble("confidence", 0.0).coerceIn(0.0, 1.0),
            o.optBoolean("confirmed", false),
            o.optLong("importedAt", 0L)
        )
    }
}
