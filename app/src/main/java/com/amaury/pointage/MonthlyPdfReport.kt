package com.amaury.pointage

import android.content.Context
import com.amaury.pointage.v2.V2LegacyPolicy
import org.json.JSONArray
import java.io.OutputStream

/**
 * Export mensuel historique.
 * Quand AGKGMGMotor est actif, ce chemin est volontairement interdit :
 * V2MonthlyPdfActivity et MonthlyPdfReportV2 restent les seules sources internes
 * autorisées afin d'éviter tout calcul historique silencieux.
 */
object MonthlyPdfReport {
    fun write(context: Context, data: JSONArray, year: Int, month: Int, output: OutputStream) {
        V2LegacyPolicy.requireLegacyAllowed(V2LegacyPolicy.Domain.PDF)
        LegacyMonthlyPdfWriter.write(context, data, year, month, output)
    }
}

/** Conservé uniquement pour rollback lorsque l'ancien moteur est explicitement réactivé. */
private object LegacyMonthlyPdfWriter {
    fun write(context: Context, data: JSONArray, year: Int, month: Int, output: OutputStream) {
        // Aucun calcul historique n'est exécuté lorsque AGKGMGMotor est actif.
        // Le rollback reste compilable mais doit être réactivé explicitement avant usage.
        throw IllegalStateException("Export mensuel historique non disponible dans cette version de AGKGMG")
    }
}
