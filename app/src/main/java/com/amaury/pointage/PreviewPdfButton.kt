package com.amaury.pointage

import com.amaury.pointage.billing.BillingPdfGate

import android.content.Context
import android.content.Intent
import android.util.AttributeSet
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import com.amaury.pointage.v2.HoraTrackV2
import com.amaury.pointage.v2.V2LegacyPolicy
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class PreviewPdfButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.buttonStyle
) : Button(context, attrs, defStyleAttr) {

    init {
        // A caller can replace or clear this default using the normal Button API.
        super.setOnClickListener { openPreview() }
    }

    internal fun openPreview(year: Int? = null, month: Int? = null) {
        val activity = context as? MainActivity ?: return
        val monthText = activity.findViewById<TextView>(R.id.selectedReportMonthText)?.text?.toString().orEmpty()
        val label = monthText.substringAfter(":", "").trim()
        val cal = Calendar.getInstance(Locale.FRANCE).apply { set(Calendar.DAY_OF_MONTH, 1) }
        runCatching {
            val parsed = SimpleDateFormat("MMMM yyyy", Locale.FRANCE).parse(label.lowercase(Locale.FRANCE))
            if (parsed != null) cal.time = parsed
        }

        if (year != null && month != null) {
            if (!MonthlyPdfExportPolicy.validPeriod(year, month)) return
            cal.clear(); cal.set(year, month, 1)
        }
        if (HoraTrackV2.ENABLED) {
            activity.startActivity(Intent(activity, V2MonthlyPdfActivity::class.java).apply {
                putExtra("report_year", cal.get(Calendar.YEAR))
                putExtra("report_month", cal.get(Calendar.MONTH))
                putExtra("report_preview", true)
            })
            return
        }

        runCatching {
            V2LegacyPolicy.requireLegacyAllowed(V2LegacyPolicy.Domain.PDF)
            val file = File.createTempFile("monthly_preview_", ".pdf", activity.cacheDir)
            file.outputStream().use { out ->
                MonthlyPdfReport.write(
                    activity,
                    PointageStore.load(activity),
                    cal.get(Calendar.YEAR),
                    cal.get(Calendar.MONTH),
                    out
                )
            }
            val pretty = SimpleDateFormat("MMMM_yyyy", Locale.FRANCE).format(cal.time)
                .replaceFirstChar { it.uppercase() }
                .replace("é","e").replace("è","e").replace("ê","e").replace("à","a").replace("ç","c")
            BillingPdfGate.require(activity, file, "Pointage_$pretty.pdf") { authorizedFile ->
                activity.startActivity(Intent(activity, PdfPreviewActivity::class.java).apply {
                    putExtra("pdf_path", authorizedFile.absolutePath)
                    putExtra("pdf_name", "Pointage_$pretty.pdf")
                })
            }
        }.onFailure {
            Toast.makeText(activity, "Impossible de générer l'aperçu PDF", Toast.LENGTH_LONG).show()
        }
    }
}
