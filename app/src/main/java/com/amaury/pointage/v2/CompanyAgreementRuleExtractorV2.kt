package com.amaury.pointage.v2

/**
 * Extraction locale prudente des passages d'accords utiles à HoraTrack.
 * Produit uniquement des candidats à vérifier : aucune règle n'est appliquée automatiquement.
 */
object CompanyAgreementRuleExtractorV2 {
    enum class Category {
        SALARY,
        BONUS,
        WORKING_TIME,
        OVERTIME,
        NIGHT,
        SUNDAY,
        PAID_LEAVE,
        RTT
    }

    data class Candidate(
        val category: Category,
        val excerpt: String,
        val confidence: Double,
        val requiresVerification: Boolean = true
    )

    private val keywords = linkedMapOf(
        Category.SALARY to listOf("salaire", "rémunération", "augmentation", "minimum salarial"),
        Category.BONUS to listOf("prime", "bonus", "gratification", "13e mois", "treizième mois"),
        Category.WORKING_TIME to listOf("durée du travail", "temps de travail", "horaire", "annualisation", "modulation"),
        Category.OVERTIME to listOf("heure supplémentaire", "heures supplémentaires", "majoration"),
        Category.NIGHT to listOf("travail de nuit", "heures de nuit", "majoration de nuit"),
        Category.SUNDAY to listOf("travail du dimanche", "dimanche", "majoration dimanche"),
        Category.PAID_LEAVE to listOf("congé payé", "congés payés", "jour férié", "jours fériés"),
        Category.RTT to listOf("rtt", "réduction du temps de travail", "jour de repos", "jours de repos")
    )

    fun extract(text: String): List<Candidate> {
        if (text.isBlank()) return emptyList()

        return splitIntoPassages(text)
            .flatMap { passage ->
                val normalized = passage.lowercase()
                keywords.mapNotNull { (category, terms) ->
                    val matches = terms.count { normalized.contains(it) }
                    if (matches == 0) null else Candidate(
                        category = category,
                        excerpt = passage.trim().take(1600),
                        confidence = when {
                            matches >= 3 -> 0.90
                            matches == 2 -> 0.80
                            else -> 0.65
                        }
                    )
                }
            }
            .distinctBy { it.category to it.excerpt }
    }

    private fun splitIntoPassages(text: String): List<String> =
        text.replace("\r\n", "\n")
            .split(Regex("\\n\\s*\\n|(?<=[.!?])\\s+(?=[A-ZÀ-ÖØ-Þ])"))
            .map(String::trim)
            .filter { it.length >= 20 }
}
