package com.amaury.pointage.v2.engine

/**
 * Cotisation accidents du travail / maladies professionnelles.
 *
 * Le taux est propre à l'établissement et doit être recopié d'une source employeur
 * fiable (notification Carsat/Cramif/CGSS ou bulletin). HoraTrack ne choisit jamais
 * un taux collectif par défaut. Cette cotisation est exclusivement patronale et ne
 * doit donc jamais diminuer le net salarié.
 */
object EmployerAtMpContributionV2 {
    data class Result(
        val rate: Double?,
        val baseGross: Double,
        val employerAmount: Double?,
        val complete: Boolean,
        val warnings: List<String>
    )

    fun calculate(gross: Double, confirmedRate: Double?): Result {
        val base = gross.coerceAtLeast(0.0)
        val rate = confirmedRate?.takeIf { it.isFinite() && it in 0.0..1.0 }
        if (rate == null) {
            return Result(
                rate = null,
                baseGross = base,
                employerAmount = null,
                complete = false,
                warnings = listOf("AT/MP employeur : taux de l'établissement non renseigné ; coût employeur incomplet.")
            )
        }
        return Result(
            rate = rate,
            baseGross = base,
            employerAmount = base * rate,
            complete = true,
            warnings = emptyList()
        )
    }
}
