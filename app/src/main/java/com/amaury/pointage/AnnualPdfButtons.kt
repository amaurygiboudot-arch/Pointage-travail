package com.amaury.pointage

import com.amaury.pointage.billing.BillingPdfGate

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.util.AttributeSet
import android.widget.Button
import android.widget.Toast
import com.amaury.pointage.v2.HoraTrackV2
import com.amaury.pointage.v2.SalaryAccountScope
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
        val sourceContext = SalaryAccountScope.bindContext(activity)
        val lease = SalaryAccountScope.lease(sourceContext)
        if (!SalaryAccountUi.current(sourceContext)) return
        val year = Calendar.getInstance().get(Calendar.YEAR)
        runCatching {
            val name = "AGKGMG_Bilan_travail_$year.pdf"
            val file = File.createTempFile("annual_preview_", ".pdf", activity.cacheDir)
            file.outputStream().use { output ->
                if (HoraTrackV2.ENABLED) {
                    AnnualPdfReports.writeWork(sourceContext, year, output)
                } else {
                    AnnualPdfReports.writeWork(sourceContext, PointageStore.load(activity), year, output)
                }
            }
            BillingPdfGate.require(activity, file, name, accountLease = lease) { authorizedFile ->
                if (!SalaryAccountScope.isCurrent(lease)) return@require
                activity.startActivity(SalaryAccountUi.attach(Intent(activity, PdfPreviewActivity::class.java).apply {
                    putExtra("pdf_path", authorizedFile.absolutePath)
                    putExtra("pdf_name", name)
                }, lease))
            }
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
        val sourceContext = SalaryAccountScope.bindContext(activity)
        val stored = SalaryCompanyStore.readConfirmed(sourceContext)
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
            companies.size == 1 -> open(activity, sourceContext, companies.single())
            else -> SalaryAccountUi.dialog(sourceContext)
                .setTitle("Entreprise pour l'estimation annuelle")
                .setItems(companies.map(::companyLabel).toTypedArray()) { _, which ->
                    open(activity, sourceContext, companies[which])
                }
                .setNegativeButton("ANNULER", null)
                .show()
        }
    }

    private fun open(activity: MainActivity, sourceContext: Context, company: SalaryCompanyStore.Company) {
        val lease = SalaryAccountScope.lease(sourceContext)
        if (!SalaryAccountUi.current(sourceContext)) return
        val year = Calendar.getInstance().get(Calendar.YEAR)
        val token = company.siret
            .ifBlank { company.id }
            .replace(Regex("[^A-Za-z0-9_-]"), "_")
            .take(32)
            .ifBlank { "entreprise" }
        val name = "AGKGMG_Estimation_salaire_${token}_$year.pdf"
        runCatching {
            val file = File.createTempFile("annual_preview_", ".pdf", activity.cacheDir)
            file.outputStream().use { output ->
                if (HoraTrackV2.ENABLED) {
                    AnnualPdfReports.writeSalary(sourceContext, year, output, company)
                } else {
                    AnnualPdfReports.writeSalary(
                        sourceContext,
                        PointageStore.load(activity),
                        year,
                        output,
                        company
                    )
                }
            }
            BillingPdfGate.require(activity, file, name, accountLease = lease) { authorizedFile ->
                if (!SalaryAccountScope.isCurrent(lease)) return@require
                activity.startActivity(SalaryAccountUi.attach(Intent(activity, PdfPreviewActivity::class.java).apply {
                    putExtra("pdf_path", authorizedFile.absolutePath)
                    putExtra("pdf_name", name)
                }, lease))
            }
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
