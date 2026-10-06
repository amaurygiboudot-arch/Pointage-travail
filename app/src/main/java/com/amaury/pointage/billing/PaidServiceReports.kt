package com.amaury.pointage.billing

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.provider.OpenableColumns
import android.text.InputType
import android.widget.EditText
import android.widget.ScrollView
import android.widget.Toast
import com.amaury.pointage.ConventionCatalog
import com.amaury.pointage.SalaryCompanyStore
import com.amaury.pointage.V2SalaryNetBridgeV2
import com.amaury.pointage.v2.*
import com.amaury.pointage.v2.model.SessionStatusV2
import com.google.firebase.auth.FirebaseAuth
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.Executors

/** Selection/preflight only. Private PDF preparation never opens, exports or grants a purchase. */
object PaidServiceReports {
    data class Prepared(val file: File, val name: String, val inputSha256: String, val requestId: String, val summary: String)
    private val worker = Executors.newSingleThreadExecutor()
    private fun uid() = runCatching { FirebaseAuth.getInstance().currentUser?.uid }.getOrNull()

    fun prepare(activity: Activity, productId: String, onPrepared: (Prepared) -> Unit) {
        val user = uid() ?: return message(activity, "Connecte ton compte avant de préparer un service.")
        val companies = SalaryCompanyStore.readConfirmed(activity)
        if (!companies.reliable || companies.companies.isEmpty()) { message(activity, "Confirme d'abord une entreprise et ses bulletins."); return }
        AlertDialog.Builder(activity).setTitle("Entreprise du rapport")
            .setItems(companies.companies.map { it.name }.toTypedArray()) { _, selected ->
                if (uid() != user) return@setItems
                selectPeriod(activity, user, productId, companies.companies[selected], onPrepared)
            }.setNegativeButton("Annuler", null).show()
    }

    private fun selectPeriod(activity: Activity, user: String, product: String, company: SalaryCompanyStore.Company, ready: (Prepared) -> Unit) {
        val read = V2PayslipStore.readResult(activity)
        if (!read.reliable) { message(activity, "Stockage des bulletins à vérifier : aucun achat proposé."); return }
        val records = read.records.filter { it.companyId == company.id && it.confirmedByUser && it.gross != null }
        if (records.isEmpty()) { message(activity, "Importe et confirme un bulletin avec son brut dans cette entreprise."); return }
        if (product == "horatrack_annual_review") {
            val years = records.map { it.year }.distinct().sortedDescending()
            AlertDialog.Builder(activity).setTitle("Année du bilan")
                .setItems(years.map(Int::toString).toTypedArray()) { _, index ->
                    val selected = records.filter { it.year == years[index] }
                    selectAnnualReferences(activity, user, product, company, selected, ready)
                }.setNegativeButton("Annuler", null).show()
        } else {
            val sorted = records.sortedWith(compareByDescending<V2PayslipStore.Record> { it.year }.thenByDescending { it.month })
            AlertDialog.Builder(activity).setTitle("Bulletin de référence")
                .setItems(sorted.map(::recordLabel).toTypedArray()) { _, index ->
                    val selected = sorted[index]
                    load(activity, user, product, company, listOf(selected), ready)
                }.setNegativeButton("Annuler", null).show()
        }
    }

    private fun selectAnnualReferences(activity: Activity, user: String, product: String, company: SalaryCompanyStore.Company,
                                       candidates: List<V2PayslipStore.Record>, ready: (Prepared) -> Unit) {
        val groups = candidates.groupBy { it.month }.toSortedMap().values.toList()
        val chosen = mutableListOf<V2PayslipStore.Record>()
        fun next(index: Int) {
            if (!active(activity, user)) return
            if (index == groups.size) { load(activity, user, product, company, chosen.toList(), ready); return }
            val records = groups[index].sortedByDescending { it.importedAtMs }
            if (records.size == 1) { chosen += records.single(); next(index + 1); return }
            AlertDialog.Builder(activity).setTitle("Choisir la référence %02d/%d".format(records.first().month + 1, records.first().year))
                .setItems(records.map(::recordLabel).toTypedArray()) { _, selected -> chosen += records[selected]; next(index + 1) }
                .setNegativeButton("Annuler", null).show()
        }
        next(0)
    }

    private fun recordLabel(record: V2PayslipStore.Record): String {
        val imported = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss", Locale.FRANCE).withZone(ZoneId.systemDefault())
            .format(Instant.ofEpochMilli(record.importedAtMs))
        return "%02d/%d — importé le %s — réf. %s".format(record.month + 1, record.year, imported, record.id.take(8))
    }

    private fun load(activity: Activity, user: String, product: String, company: SalaryCompanyStore.Company,
                     records: List<V2PayslipStore.Record>, ready: (Prepared) -> Unit) {
        message(activity, "Vérification des pièces et des calculs, avant tout paiement…")
        worker.execute {
            val result = runCatching {
                check(uid() == user) { "Le compte connecté a changé." }
                val companies = SalaryCompanyStore.readConfirmed(activity)
                check(companies.reliable && companies.companies.singleOrNull { it.id == company.id } == company) { "Entreprise à confirmer de nouveau." }
                val currentRecords = V2PayslipStore.readResult(activity)
                check(currentRecords.reliable && records.all { record -> currentRecords.records.singleOrNull { it.id == record.id } == record }) { "Le bulletin a changé : sélection à refaire." }
                check(records.map { it.year to it.month }.distinct().size == records.size) { "Des mois ont plusieurs bulletins : référence ambiguë." }
                val observed = PayslipObservedValuesStoreV2.readResult(activity)
                check(observed.reliable) { "Montants observés illisibles." }
                val annual = product == "horatrack_annual_review"
                val omitted = mutableListOf<String>()
                val months = records.sortedBy { it.month }.mapNotNull { record ->
                    val month = runCatching { evidence(activity, company, record, observed, product == "horatrack_analysis") }
                    if (month.isFailure && annual) {
                        omitted += "%02d/%d : %s".format(record.month + 1, record.year, month.exceptionOrNull()?.message ?: "preuves insuffisantes")
                        null
                    } else month.getOrThrow()
                }
                val input = PaidServiceReportBuilder.Input(product, company.id, company.name, company.siret, company.idcc,
                    records.first().year, months.map { if (annual) it.copy(warnings = it.warnings + omitted) else it })
                if (product == "horatrack_claim_dossier") input.copy(claimLetter = PaidServiceReportBuilder.claimTemplate(company.name, months.single())) else input
            }
            activity.runOnUiThread {
                if (!active(activity, user)) return@runOnUiThread
                result.onSuccess { input ->
                    if (product == "horatrack_claim_dossier") editLetter(activity, user, input, ready)
                    else preflight(activity, user, input, ready)
                }.onFailure { message(activity, "Service non préparé : ${it.message ?: "données insuffisantes"}. Aucun achat proposé.") }
            }
        }
    }

    private fun evidence(activity: Activity, company: SalaryCompanyStore.Company, record: V2PayslipStore.Record,
                         observed: PayslipObservedValuesStoreV2.ReadResult, analysis: Boolean): PaidServiceReportBuilder.Month {
        check(record.confirmedByUser && record.gross != null && record.companyId == company.id)
        check(YearMonth.of(record.year, record.month + 1) <= YearMonth.now()) { "Bulletin futur : période à corriger." }
        val source = original(activity, record)
        val values = observed.valuesByRecord[record.id].orEmpty().toMutableMap()
        val gross = record.gross!!
        check(values["Brut"] == null || kotlin.math.abs(values.getValue("Brut") - gross) <= 0.02) { "Brut contradictoire entre le bulletin confirmé et ses lignes." }
        values.putIfAbsent("Brut", gross)
        val refs = mutableListOf("Montants relus et confirmés par l'utilisateur ; OCR initial non utilisé comme preuve suffisante.",
            "Convention enregistrée : ${company.conventionName.ifBlank { "non renseignée" }} ; IDCC ${company.idcc.ifBlank { "non renseigné" }}.")
        val dateFormat = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm", Locale.FRANCE).withZone(ZoneId.systemDefault())
        if (analysis) return PaidServiceReportBuilder.Month(record.year, record.month, record.id, source.first, source.second,
            dateFormat.format(Instant.ofEpochMilli(record.importedAtMs)), values, references = refs,
            warnings = listOf("Assiettes, taux de cotisations et toutes les lignes du bulletin non enregistrés : contrôle limité aux neuf familles disponibles."))
        val runtime = V2RuntimeReader.allSessions(activity).requireReliable()
        val accepted = SalaryCompanyStore.acceptedEmployerIds(activity, company.id)
        check(accepted.isNotEmpty()) { "Lien entreprise/pointages non confirmé." }
        val zone = ZoneId.systemDefault()
        val start = LocalDate.of(record.year, record.month + 1, 1).atStartOfDay(zone).toInstant().toEpochMilli()
        val end = LocalDate.of(record.year, record.month + 1, 1).plusMonths(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val sessions = runtime.filter { session ->
            session.employerId in accepted && session.realArrivalMs?.let { it < end && (session.realExitMs ?: Long.MAX_VALUE) > start } == true
        }.sortedBy { it.realArrivalMs }
        check(sessions.isNotEmpty() && sessions.all { it.status == SessionStatusV2.CLOSED && it.realArrivalMs != null && it.realExitMs != null &&
            it.realArrivalMs >= start && it.realExitMs <= end }) { "Pointages absents, ouverts ou traversant le mois : compléter les preuves avant le service." }
        val chronology = sessions.flatMap { session ->
            val time = HoraTrackV2.time.calculate(session)
            check(time.reliable) { "Durée ou pause non fiable dans un pointage." }
            val rows = mutableListOf("${dateFormat.format(Instant.ofEpochMilli(session.realArrivalMs!!))} → ${dateFormat.format(Instant.ofEpochMilli(session.realExitMs!!))} : présence ${duration(time.presenceMs)}, temps payé ${duration(time.paidWorkMs)}, pauses non payées ${duration(time.unpaidPauseMs)} ; lieu ${session.placeLabel.orEmpty().ifBlank { "non renseigné" }} ; référence ${session.id}.",
                "Entrée comptée ${session.countedEntryMs?.let { dateFormat.format(Instant.ofEpochMilli(it)) } ?: "non confirmée"} ; sortie comptée ${session.countedExitMs?.let { dateFormat.format(Instant.ofEpochMilli(it)) } ?: "non confirmée"}.")
            session.pauses.forEach { pause -> rows += "Pause ${dateFormat.format(Instant.ofEpochMilli(pause.startMs))} → ${pause.endMs?.let { dateFormat.format(Instant.ofEpochMilli(it)) } ?: "ouverte"} ; ${if (pause.paid == true) "payée" else if (pause.paid == false) "non payée" else "statut inconnu"} ; source ${pause.source}, validation ${pause.status}." }
            session.travels.forEach { travel -> rows += "Déplacement ${dateFormat.format(Instant.ofEpochMilli(travel.startMs))} → ${travel.endMs?.let { dateFormat.format(Instant.ofEpochMilli(it)) } ?: "ouvert"} ; qualification ${travel.classification}." }
            rows
        }
        val contracts = V2EmploymentContractPayrollBridge.resolve(activity, company.id, record.year, record.month)
        val rules = V2ConventionRulePayrollBridge.resolve(activity, company.idcc, record.year, record.month)
        refs += contracts.resolution.calculationSegments.map { "Contrat confirmé, version ${it.snapshot.versionId}, du ${LocalDate.ofEpochDay(it.startEpochDay)} au ${LocalDate.ofEpochDay(it.endEpochDay)}." }
        refs += "Couverture des règles conventionnelles sur le mois : ${if (rules.resolution.readyForCalculation) "confirmée" else "incomplète"} ; ${rules.resolution.calculationSegments.size} segment(s) daté(s)."
        val warnings = mutableListOf<String>(); warnings += contracts.warnings; warnings += rules.warnings
        val expected = when (V2SalaryCalculationRoute.resolve(activity, company, record.year, record.month)) {
            V2SalaryCalculationRoute.Route.MONTHLY -> {
                val convention = ConventionCatalog.findByIdcc(activity, company.idcc) ?: error("Convention indisponible.")
                val calculated = V2SalaryNetBridgeV2.calculateForCompany(activity, company, record.year, record.month, convention)
                warnings += calculated.warnings
                V2PayslipStore.expectedCompanyComparisonValues(calculated) ?: error("Calcul de rémunération non fiable.")
            }
            V2SalaryCalculationRoute.Route.SEGMENTED -> {
                val calculated = V2SegmentedSalaryCanonicalBridge.calculateForCompany(activity, company, record.year, record.month, zone.id)
                warnings += calculated.warnings
                calculated.output?.let(SegmentedPayslipComparisonValuesV2::expected) ?: error("Calcul segmenté incomplet.")
            }
            V2SalaryCalculationRoute.Route.BLOCKED -> error("Contrat ou règles datées incomplets.")
        }
        val comparable = PayslipObservedValuesStoreV2.comparisonValues(observed, record.id).keys.toMutableSet().apply { add("Brut") }
        check(comparable.intersect(expected.keys).intersect(values.keys).size >= 3 && "Brut" in expected) {
            "Moins de trois familles rapprochables dont le brut : détail insuffisant pour ce service."
        }
        refs += "Montants attendus issus du moteur canonique HoraTrack pour cette entreprise et cette période ; aucune reconstitution indépendante ni taux supposé."
        return PaidServiceReportBuilder.Month(record.year, record.month, record.id, source.first, source.second,
            dateFormat.format(Instant.ofEpochMilli(record.importedAtMs)), values, expected, comparable, chronology, refs, warnings.distinct(), true)
    }

    private fun editLetter(activity: Activity, user: String, input: PaidServiceReportBuilder.Input, ready: (Prepared) -> Unit) {
        val editor = EditText(activity).apply { setText(input.claimLetter); minLines = 12; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE }
        AlertDialog.Builder(activity).setTitle("Modifier le projet de courrier")
            .setMessage("Complète le nom, les dates et ta demande. Le PDF contient les pointages détaillés et la liste des pièces à joindre, pas les fichiers originaux. Aucun envoi automatique.")
            .setView(ScrollView(activity).apply { addView(editor) })
            .setPositiveButton("Valider le texte") { _, _ ->
                val text = editor.text.toString().trim()
                if (text.isBlank() || text.length > 20_000) message(activity, "Courrier vide ou trop long : préparation annulée.")
                else preflight(activity, user, input.copy(claimLetter = text), ready)
            }.setNegativeButton("Annuler", null).show()
    }

    private fun preflight(activity: Activity, user: String, input: PaidServiceReportBuilder.Input, ready: (Prepared) -> Unit) {
        val report = runCatching { PaidServiceReportBuilder.build(input) }.getOrElse {
            message(activity, "Rapport insuffisant : ${it.message}. Aucun achat proposé."); return
        }
        AlertDialog.Builder(activity).setTitle("Portée du service avant paiement")
            .setMessage("${report.scope}\n\nContrôle des montants confirmés uniquement, pas chaque ligne, base ou taux du bulletin. Écarts face à une estimation, sans validation juridique. Rapport PDF inclus ; les originaux ne sont pas joints automatiquement.\n\nLa préparation reste privée : aucun aperçu PDF ni export avant l'achat vérifié. Les conditions et le prix Google Play seront présentés ensuite.")
            .setPositiveButton("Continuer") { _, _ ->
                worker.execute {
                    val prepared = runCatching {
                        check(uid() == user)
                        val folder = File(activity.filesDir, "paid_service_prepared/${BillingContract.accountId(user)}").apply { check(mkdirs() || isDirectory) }
                        val file = File(folder, "${report.inputSha256}.pdf")
                        if (!file.exists()) {
                            val temp = File.createTempFile("report_", ".pdf", folder)
                            try { writePdf(report, temp); check(temp.renameTo(file)) } finally { temp.delete() }
                        }
                        check(file.isFile && file.length() > 0)
                        val companyLabel = input.companyName.replace(Regex("[^A-Za-z0-9_-]"), "_").take(32).ifBlank { "entreprise" }
                        val periodLabel = if (input.productId == "horatrack_annual_review") input.year.toString()
                            else "%04d_%02d".format(Locale.FRANCE, input.year, input.months.single().month + 1)
                        Prepared(file, "HoraTrack_${input.productId.removePrefix("horatrack_")}_${companyLabel}_$periodLabel.pdf", report.inputSha256,
                            report.inputSha256, report.scope)
                    }
                    activity.runOnUiThread {
                        if (active(activity, user)) prepared.onSuccess(ready).onFailure { message(activity, "Préparation privée impossible. Aucun achat proposé.") }
                    }
                }
            }.setNegativeButton("Annuler", null).show()
    }

    private fun original(activity: Activity, record: V2PayslipStore.Record): Pair<String, String> {
        val uri = Uri.parse(record.sourceUri)
        check(uri.scheme in setOf("content", "file")) { "Pièce originale non locale." }
        val digest = MessageDigest.getInstance("SHA-256")
        activity.contentResolver.openInputStream(uri)?.use { stream ->
            val prefix = ByteArray(16); var count = 0
            while (count < prefix.size) { val n = stream.read(prefix, count, prefix.size - count); if (n < 0) break; count += n }
            val pdf = count >= 5 && String(prefix, 0, 5, Charsets.US_ASCII) == "%PDF-"
            val jpg = count >= 3 && (prefix[0].toInt() and 255) == 255 && (prefix[1].toInt() and 255) == 216 && (prefix[2].toInt() and 255) == 255
            val png = count >= 8 && prefix.take(8).map { it.toInt() and 255 } == listOf(137, 80, 78, 71, 13, 10, 26, 10)
            val webp = count >= 12 && String(prefix, 0, 4, Charsets.US_ASCII) == "RIFF" && String(prefix, 8, 4, Charsets.US_ASCII) == "WEBP"
            check(pdf || jpg || png || webp) { "Pièce originale non reconnue comme PDF ou image de bulletin." }
            digest.update(prefix, 0, count)
            var total = count.toLong(); val buffer = ByteArray(8192)
            while (true) { val n = stream.read(buffer); if (n < 0) break; total += n; check(total <= 50L * 1024 * 1024) { "Pièce trop volumineuse." }; digest.update(buffer, 0, n) }
        } ?: error("Pièce originale inaccessible : importe-la de nouveau.")
        val name = runCatching { activity.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        } }.getOrNull().orEmpty().ifBlank { "Bulletin %02d/%d".format(record.month + 1, record.year) }.substringAfterLast('/').replace(Regex("[\\r\\n\\t]"), " ").take(160)
        return name to digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    private fun writePdf(report: PaidServiceReportBuilder.Report, file: File) {
        val document = PdfDocument()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 10f }
        val width = 515f
        val wrapped = report.lines.flatMap { text ->
            val output = mutableListOf<String>(); var line = ""
            text.split(' ').forEach { word ->
                if (line.isNotEmpty() && paint.measureText("$line $word") > width) { output += line; line = "" }
                if (paint.measureText(word) > width) {
                    if (line.isNotEmpty()) { output += line; line = "" }
                    var remaining = word
                    while (remaining.isNotEmpty()) { val count = paint.breakText(remaining, true, width, null).coerceAtLeast(1); output += remaining.take(count); remaining = remaining.drop(count) }
                } else line = if (line.isEmpty()) word else "$line $word"
            }
            if (line.isNotEmpty()) output += line
            output + ""
        }
        try {
            wrapped.chunked(46).forEachIndexed { index, lines ->
                val page = document.startPage(PdfDocument.PageInfo.Builder(595, 842, index + 1).create())
                lines.forEachIndexed { row, text -> page.canvas.drawText(text, 40f, 45f + row * 16f, paint) }
                page.canvas.drawText("HoraTrack — rapport factuel — page ${index + 1}", 40f, 815f, paint)
                document.finishPage(page)
            }
            file.outputStream().use { document.writeTo(it) }
        } finally { document.close() }
    }
    private fun duration(ms: Long) = "%dh%02d".format(Locale.FRANCE, ms / 3_600_000, ms / 60_000 % 60)
    private fun active(activity: Activity, user: String) = !activity.isFinishing && !activity.isDestroyed && uid() == user
    private fun message(activity: Activity, text: String) { if (!activity.isFinishing && !activity.isDestroyed) Toast.makeText(activity, text, Toast.LENGTH_LONG).show() }
}
