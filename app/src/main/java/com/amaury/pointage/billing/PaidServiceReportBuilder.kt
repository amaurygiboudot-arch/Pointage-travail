package com.amaury.pointage.billing

import java.security.MessageDigest
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

/** Deterministic factual report, independent from Android, payments and AI. */
object PaidServiceReportBuilder {
    const val VERSION = "factual-services-v1"
    val fields = listOf("Brut", "Net avant PAS", "Net imposable", "Heures supplémentaires", "Primes / majorations",
        "Paniers", "Mutuelle salariale", "Prévoyance salariale", "Retraite complémentaire salariale")
    data class Month(
        val year: Int, val month: Int, val recordId: String, val sourceName: String, val sourceSha256: String,
        val imported: String, val observed: Map<String, Double>, val expected: Map<String, Double> = emptyMap(),
        val comparableFields: Set<String> = emptySet(), val chronology: List<String> = emptyList(),
        val references: List<String> = emptyList(), val warnings: List<String> = emptyList(), val reliable: Boolean = false
    )
    data class Input(val productId: String, val companyId: String, val companyName: String, val siret: String,
        val idcc: String, val year: Int, val months: List<Month>, val claimLetter: String? = null)
    data class Report(val title: String, val lines: List<String>, val inputSha256: String, val scope: String)

    fun build(input: Input): Report {
        require(input.companyId.isNotBlank() && input.companyName.isNotBlank()) { "Entreprise confirmée manquante." }
        require(input.productId in setOf("horatrack_analysis", "horatrack_payslip_comparison", "horatrack_annual_review", "horatrack_claim_dossier"))
        require(input.months.isNotEmpty() && input.months.all { it.year == input.year && it.month in 0..11 })
        require(input.months.map { it.month }.distinct().size == input.months.size) { "Plusieurs bulletins pour le même mois : choisir le bulletin de référence avant l'achat." }
        input.months.forEach { month ->
            require(month.recordId.isNotBlank() && month.sourceName.isNotBlank() && Regex("[a-f0-9]{64}").matches(month.sourceSha256)) { "Pièce originale manquante." }
            require(month.observed.keys.all { it in fields } && month.expected.keys.all { it in fields } && month.comparableFields.all { it in fields }) { "Famille de montant non prise en charge." }
            require(month.observed["Brut"] != null && month.observed.values.all(::validAmount)) { "Montants confirmés incomplets ou invalides." }
            require(month.expected.values.all(::validAmount))
        }
        val annual = input.productId == "horatrack_annual_review"
        val analysis = input.productId == "horatrack_analysis"
        if (analysis) require(input.months.size == 1 && input.months.single().observed.size >= 5) { "Au moins cinq familles de montants confirmées sont nécessaires pour ce rapport." }
        else {
            require(input.months.all { it.reliable && comparable(it).size >= 3 && "Brut" in comparable(it) && it.chronology.isNotEmpty() }) {
                "Trois familles comparables dont le brut, des pointages clos et un calcul canonique fiable sont nécessaires."
            }
            require(if (annual) input.months.size >= 6 else input.months.size == 1) { "Le bilan annuel détaillé exige au moins six mois exploitables ; les autres services concernent un seul bulletin." }
        }
        val title = when (input.productId) {
            "horatrack_analysis" -> "Analyse factuelle du bulletin"
            "horatrack_payslip_comparison" -> "Rapprochement pointage et bulletin"
            "horatrack_annual_review" -> "Bilan annuel détaillé — ${input.year}"
            else -> "Dossier factuel de demande d'explications"
        }
        val scope = if (annual) "${input.months.size} mois exploitables sur 12 ; les mois manquants ne sont jamais traités comme des zéros."
            else "${input.months.single().observed.size} familles de montants confirmées sur 9 ; contrôle limité à ces données."
        val lines = mutableListOf(title, "Entreprise : ${input.companyName}", "SIRET : ${input.siret.ifBlank { "non renseigné" }} ; IDCC : ${input.idcc.ifBlank { "non renseigné" }}", scope,
            "Portée : contrôle déterministe de montants confirmés, sans IA, sans expertise exhaustive du bulletin et sans garantie juridique.",
            "Les bases, taux, heures imprimées et toutes les lignes du bulletin ne sont pas disponibles dans les neuf familles enregistrées. Aucune dette certaine ni conformité générale n'est déduite.")
        if (annual) {
            lines += "COUVERTURE DES DOUZE MOIS"
            (0..11).forEach { m ->
                val evidence = input.months.singleOrNull { it.month == m }
                lines += "${period(input.year, m)} : ${if (evidence == null) "NON CONTRÔLÉ — bulletin/pointage/preuves insuffisants ou période non renseignée" else "exploitable ; ${comparable(evidence).size} familles rapprochées"}"
            }
            lines += "Cumuls PARTIELS des seuls mois contrôlés (jamais un total annuel complet lorsque la couverture est inférieure à 12 mois)."
            fields.forEach { field ->
                val known = input.months.mapNotNull { it.observed[field] }
                if (known.isNotEmpty()) {
                    require(validAmount(known.sum())) { "Cumul de montants hors domaine représentable." }
                    lines += "$field : ${money(known.sum())} observés sur ${known.size}/${input.months.size} mois exploitables."
                }
            }
            fields.forEach { field ->
                val negative = input.months.filter { field in comparable(it) && cents(it.observed.getValue(field)) < cents(it.expected.getValue(field)) - 2 }
                val positive = input.months.filter { field in comparable(it) && cents(it.observed.getValue(field)) > cents(it.expected.getValue(field)) + 2 }
                if (negative.size >= 2 || positive.size >= 2) lines += "$field : écarts répétés, ${negative.size} négatifs et ${positive.size} positifs. Aucune somme entre brut, net et sous-composants : ce serait un double compte."
            }
        }
        input.months.sortedBy { it.month }.forEach { month ->
            lines += "BULLETIN ${period(month.year, month.month)}"
            lines += "Pièce : ${month.sourceName} ; import : ${month.imported} ; référence locale : ${month.recordId}."
            lines += "Table des neuf familles — observé / attendu canonique / différence observé moins attendu. Tolérance : 0,02 EUR après arrondi au centime."
            fields.forEach { field ->
                val observed = month.observed[field]
                val expected = month.expected[field].takeIf { field in comparable(month) }
                val status = when {
                    observed == null -> "NON LU/CONFIRMÉ — pas de conclusion"
                    expected == null -> "observé ${money(observed)} ; NON COMPARÉ — preuve de recalcul absente ou portée du service"
                    else -> "observé ${money(observed)} ; attendu ${money(expected)} ; écart ${money((cents(observed) - cents(expected)) / 100.0)} ; " +
                        if (abs(cents(observed) - cents(expected)) <= 2) "concordance sur ce champ seulement" else "ÉCART À EXPLIQUER, pas une créance établie"
                }
                lines += "$field : $status"
            }
            if (analysis) {
                val gross = month.observed.getValue("Brut")
                month.observed["Net avant PAS"]?.let { net ->
                    lines += "Passage brut/net avant impôt : différence observée ${money(gross - net)}. Ce n'est pas la somme certifiée des cotisations : remboursements, exonérations et autres lignes non lues peuvent intervenir."
                }
                lines += "Primes, heures supplémentaires et paniers ne sont pas additionnés au brut : ils peuvent déjà être inclus dans ses composantes."
                lines += "Mutuelle, prévoyance et retraite sont conservées séparément ; leur assiette/taux et leur inclusion dans les autres retenues ne sont pas présumés."
            } else {
                lines += "CHRONOLOGIE ET POINTAGES AYANT ALIMENTÉ LE CALCUL"
                lines += month.chronology
            }
            lines += "PREUVES ET LIMITES DU CALCUL"
            lines += month.references
            lines += month.warnings.distinct().map { "À vérifier : $it" }
            lines += "Empreinte de la pièce originale (SHA-256, identification documentaire uniquement) : ${month.sourceSha256}"
        }
        if (input.productId == "horatrack_claim_dossier") {
            require(!input.claimLetter.isNullOrBlank()) { "Courrier à compléter avant la préparation." }
            lines += "PROJET DE COURRIER MODIFIÉ ET VALIDÉ PAR L'UTILISATEUR — AUCUN ENVOI AUTOMATIQUE"
            lines += input.claimLetter.lines()
            lines += "PIÈCES À JOINDRE PAR L'UTILISATEUR"
            lines += input.months.map { "Bulletin original ${it.sourceName}, période ${period(it.year, it.month)} ; relevé des pointages ci-dessus ; contrat et justificatifs pertinents. Les originaux restent accessibles gratuitement dans HoraTrack." }
        }
        val normalized = mutableListOf(VERSION, input.productId, input.companyId, input.companyName, input.siret, input.idcc, input.year.toString(), input.claimLetter.orEmpty())
        input.months.sortedBy { it.month }.forEach { month ->
            normalized += listOf(month.month.toString(), month.recordId, month.sourceName, month.sourceSha256, month.imported, month.reliable.toString())
            month.observed.toSortedMap().forEach { (k, v) -> normalized += listOf("observed:$k", v.toString()) }
            month.expected.toSortedMap().forEach { (k, v) -> normalized += listOf("expected:$k", v.toString()) }
            normalized += month.comparableFields.sorted(); normalized += month.chronology; normalized += month.references; normalized += month.warnings
        }
        normalized += lines
        val bytes = normalized.joinToString("") { "${it.toByteArray(Charsets.UTF_8).size}:$it" }.toByteArray(Charsets.UTF_8)
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
        return Report(title, lines.toList(), digest, scope)
    }

    fun claimTemplate(company: String, month: Month): String = buildString {
        append("Madame, Monsieur,\n\nJe sollicite la vérification de mon bulletin ${period(month.year, month.month)} pour $company.\n")
        val changed = comparable(month).sorted().filter { abs(cents(month.observed.getValue(it)) - cents(month.expected.getValue(it))) > 2 }
        if (changed.isEmpty()) append("Je souhaite obtenir le détail des bases de calcul et des éléments correspondant à mes pointages.\n")
        changed.forEach { field -> append("$field : montant figurant au bulletin ${money(month.observed.getValue(field))}, estimation HoraTrack ${money(month.expected.getValue(field))}. Merci d'expliquer cet écart et les éléments appliqués.\n") }
        append("\nLes montants HoraTrack sont des estimations liées aux données confirmées ; cette demande ne présume ni faute ni somme certaine due. Je joins le bulletin et le relevé de mes pointages.\n\nMerci de me transmettre vos explications et, si nécessaire, les corrections utiles.\n\n[Nom, date et signature à compléter]")
    }
    private fun comparable(month: Month) = month.comparableFields.filter { it in month.expected && it in month.observed }.toSet()
    private fun validAmount(value: Double) = value.isFinite() && value >= 0.0 && value < Long.MAX_VALUE.toDouble() / 100.0
    private fun cents(value: Double) = (value * 100).roundToLong()
    private fun money(value: Double) = String.format(Locale.FRANCE, "%.2f EUR", value)
    private fun period(year: Int, month: Int) = "%02d/%d".format(Locale.FRANCE, month + 1, year)
}
