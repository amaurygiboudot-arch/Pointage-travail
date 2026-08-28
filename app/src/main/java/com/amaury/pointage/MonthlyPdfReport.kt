package com.amaury.pointage

import android.content.Context
import com.amaury.pointage.v2.HoraTrackV2
import org.json.JSONArray
import java.io.OutputStream

/**
 * Export mensuel historique.
 * En V2, ce chemin est volontairement interdit : V2MonthlyPdfActivity et
 * MonthlyPdfReportV2 sont les seules sources autorisées pour éviter un calcul
 * legacy silencieux.
 */
object MonthlyPdfReport {
    fun write(context: Context, data: JSONArray, year: Int, month: Int, output: OutputStream) {
        check(!HoraTrackV2.ENABLED) { "Export legacy désactivé : utiliser MonthlyPdfReportV2" }
        LegacyMonthlyPdfWriter.write(context, data, year, month, output)
    }
}

/** Conservé uniquement pour rollback lorsque V2 est explicitement désactivée. */
private object LegacyMonthlyPdfWriter {
    fun write(context: Context, data: JSONArray, year: Int, month: Int, output: OutputStream) {
        // Aucun calcul legacy n'est exécuté lorsque V2 est active.
        // Le rollback historique reste compilable mais doit être réactivé explicitement avant usage.
        throw IllegalStateException("Export mensuel legacy non disponible dans cette version V2")
    }
}
