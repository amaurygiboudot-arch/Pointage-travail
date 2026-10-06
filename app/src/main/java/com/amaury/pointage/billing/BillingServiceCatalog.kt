package com.amaury.pointage.billing

/** Approved target prices and preparation readiness; only live Play offers and server checks permit payment. */
object BillingServiceCatalog {
    enum class Availability { DELIVERY_NOT_READY, READY }
    data class Service(
        val productId: String,
        val title: String,
        val targetPriceEuroCents: Int,
        val description: String,
        val pdfIncluded: Boolean = true,
        val availability: Availability = Availability.READY
    ) {
        val readyForPreparation: Boolean get() = availability == Availability.READY
        // A catalogue entry cannot authorize or promise a store purchase.
        val purchasable: Boolean get() = false
        val targetPriceLabel: String get() = "${targetPriceEuroCents / 100},${(targetPriceEuroCents % 100).toString().padStart(2, '0')} €"
    }

    val services = listOf(
        Service("horatrack_analysis", "Analyse d'un bulletin", 499,
            "Analyse d'un bulletin de paie et rapport PDF inclus."),
        Service("horatrack_payslip_comparison", "Comparaison pointage et bulletin", 699,
            "Comparaison du pointage avec un bulletin, écarts expliqués et rapport PDF inclus."),
        Service("horatrack_annual_review", "Bilan annuel", 999,
            "Audit annuel des heures, primes, écarts et périodes incomplètes, avec rapport PDF inclus."),
        Service("horatrack_claim_dossier", "Dossier de réclamation", 1499,
            "Chronologie, écarts, courrier modifiable et liste des justificatifs à joindre, avec PDF inclus.")
    )
}
