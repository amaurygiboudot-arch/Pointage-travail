package com.amaury.pointage

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.util.AttributeSet
import android.widget.Button
import android.widget.Toast
import com.amaury.pointage.v2.HoraTrackV2
import java.io.File
import java.util.Calendar

/** Bouton d'aperçu du bilan annuel des heures. */
class AnnualWorkPdfButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.buttonStyle
) : Button(context, attrs, defStyleAttr) {
    init {
        setOnClickListener { open() }
    }

    private fun open() {
        val activity = context as? MainActivity ?: return
        val year = Calendar.getInstance().get(Calendar.YEAR)
        runCatching {
            val name = "HoraTrack_Bilan_travail_$year.pdf"
            val file = File(activity.cacheDir, name)
            file.outputStream().use { output ->
                if (HoraTrackV2.ENABLED) {
                    AnnualPdfReports.writeWork(activity, year, output)
                } else {
                    AnnualPdfReports.writeWork(activity, PointageStore.load(activity), year, output)
                }
            }
            activity.startActivity(Intent(activity, PdfPreviewActivity::class.java).apply {
                putExtra("pdf_path", file.absolutePath)
                putExtra("pdf_name", name)
            })
        }.onFailure {
            Toast.makeText(activity, "Impossible de générer le bilan annuel", Toast.LENGTH_LONG).show()
        }
    }
}

/** Bouton d'aperçu de l'estimation annuelle de rémunération. */
class AnnualSalaryPdfButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.buttonStyle
) : Button(context, attrs, defStyleAttr) {
    init {
        setOnClickListener { chooseCompanyAndOpen() }
    }

    private fun chooseCompanyAndOpen() {
        val activity = context as? MainActivity ?: return
        val stored = SalaryCompanyStore.readConfirmed(activity)
        if (!stored.reliable) {
            Toast.makeText(
                activity,
                "Entreprises Salaire indisponibles : vérifie le stockage avant de générer l'estimation annuelle",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        val companies = stored.companies
        when {
            companies.isEmpty() -> Toast.makeText(
                activity,
                "Ajoute d'abord une entreprise dans Salaire",
                Toast.LENGTH_LONG
            ).show()
            companies.size == 1 -> open(activity, companies.single())
            else -> AlertDialog.Builder(activity)
                .setTitle("Entreprise pour l'estimation annuelle")
                .setItems(companies.map(::companyLabel).toTypedArray()) { _, which ->
                    open(activity, companies[which])
                }
                .setNegativeButton("ANNULER", null)
                .show()
        }
    }

    private fun open(activity: MainActivity, company: SalaryCompanyStore.Company) {
        val year = Calendar.getInstance().get(Calendar.YEAR)
        val token = company.siret
            .ifBlank { company.id }
            .replace(Regex("[^A-Za-z0-9_-]"), "_")
            .take(32)
            .ifBlank { "entreprise" }
        val name = "HoraTrack_Estimation_salaire_${token}_$year.pdf"
        runCatching {
            val file = File(activity.cacheDir, name)
            file.outputStream().use { output ->
                if (HoraTrackV2.ENABLED) {
                    AnnualPdfReports.writeSalary(activity, year, output, company)
                } else {
                    AnnualPdfReports.writeSalary(
                        activity,
                        PointageStore.load(activity),
                        year,
                        output,
                        company
                    )
                }
            }
            activity.startActivity(Intent(activity, PdfPreviewActivity::class.java).apply {
                putExtra("pdf_path", file.absolutePath)
                putExtra("pdf_name", name)
            })
        }.onFailure {
            Toast.makeText(
                activity,
                "Impossible de générer l'estimation annuelle",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun companyLabel(company: SalaryCompanyStore.Company) = buildString {
        append(company.name.ifBlank { "Entreprise" })
        if (company.siret.isNotBlank()) append("\nSIRET : ").append(company.siret)
    }
}
