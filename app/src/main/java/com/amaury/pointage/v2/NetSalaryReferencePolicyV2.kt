package com.amaury.pointage.v2

import com.amaury.pointage.v2.engine.NetSalaryEngineV2

/**
 * Empêche un net V2 encore partiel de devenir une donnée de référence dans un autre moteur.
 *
 * Les montants partiels restent disponibles dans [NetSalaryEngineV2.Result] pour expliquer les
 * retenues déjà connues à l'utilisateur. En revanche, une comparaison automatique ou un calcul
 * dérivé ne doit les utiliser que lorsque la référence nette principale est elle-même admissible.
 */
object NetSalaryReferencePolicyV2 {
    fun beforeIncomeTax(result: NetSalaryEngineV2.Result): Double? =
        beforeIncomeTax(result.netBeforeIncomeTax, result.complete)

    fun taxable(result: NetSalaryEngineV2.Result): Double? =
        taxable(result.netTaxable, beforeIncomeTax(result) != null)

    internal fun beforeIncomeTax(amount: Double, complete: Boolean): Double? =
        amount.takeIf { complete && it.isFinite() && it >= 0.0 }

    internal fun taxable(amount: Double?, beforeIncomeTaxReferenceAvailable: Boolean): Double? =
        amount?.takeIf { beforeIncomeTaxReferenceAvailable && it.isFinite() && it >= 0.0 }
}
