package com.amaury.pointage

import android.content.Context
import android.content.Intent
import android.util.AttributeSet
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import com.amaury.pointage.v2.HoraTrackV2
import com.amaury.pointage.v2.V2RuntimeStore
import com.amaury.pointage.v2.engine.MonthlyPdfReportV2
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class PreviewPdfButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.buttonStyle
) : Button(context, attrs, defStyleAttr) {

    override fun setOnClickListener(l: View.OnClickListener?) {
        super.setOnClickListener { openPreview() }
    }

    private fun openPreview() {
        val activity = context as? MainActivity ?: return
        val monthText = activity.findViewById<TextView>(R.id.selectedReportMonthText)?.text?.toString().orEmpty()
        val label = monthText.substringAfter(":", "").trim()
        val cal = Calendar.getInstance(Locale.FRANCE).apply { set(Calendar.DAY_OF_MONTH, 1) }
        runCatching {
            val parsed = SimpleDateFormat("MMMM yyyy", Locale.FRANCE).parse(label.lowercase(Locale.FRANCE))
            if (parsed != null) cal.time = parsed
        }

        runCatching {
            val file = File(activity.cacheDir, "Pointage_${cal.get(Calendar.YEAR)}_${cal.get(Calendar.MONTH) + 1}.pdf")
            file.outputStream().use { out ->
                if (HoraTrackV2.ENABLED) {
                    MonthlyPdfReportV2.write(
                        V2RuntimeStore.allSessions(activity),
                        cal.get(Calendar.YEAR),
                        cal.get(Calendar.MONTH),
                        out
                    )
                } else {
                    MonthlyPdfReport.write(
                        activity,
                        PointageStore.load(activity),
                        cal.get(Calendar.YEAR),
                        cal.get(Calendar.MONTH),
                        out
                    )
                }
            }
            val pretty = SimpleDateFormat("MMMM_yyyy", Locale.FRANCE).format(cal.time)
                .replaceFirstChar { it.uppercase() }
                .replace("é","e").replace("è","e").replace("ê","e").replace("à","a").replace("ç","c")
            activity.startActivity(Intent(activity, PdfPreviewActivity::class.java).apply {
                putExtra("pdf_path", file.absolutePath)
                putExtra("pdf_name", "Pointage_$pretty.pdf")
            })
        }.onFailure { error ->
            V2Diagnostics.report(activity, "PDF mensuel", error)
            Toast.makeText(activity, "Impossible de générer l'aperçu PDF", Toast.LENGTH_LONG).show()
        }
    }
}
