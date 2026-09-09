package com.amaury.pointage.v2

import com.amaury.pointage.v2.engine.NetSalaryEngineV2

/**
 * Empêche un net V2 encore partiel de devenir une donnée de référence dans un autre moteur.
 *
 * Le montant partiel reste disponible dans [NetSalaryEngineV2.Result] pour expliquer les retenues
 * déjà connues à l'utilisateur. En revanche, une comparaison automatique ou un calcul dérivé ne
 * doit l'utiliser que lorsque le moteur canonique le marque lui-même comme complet.
 */
object NetSalaryReferencePolicyV2 {
    fun beforeIncomeTax(result: NetSalaryEngineV2.Result): Double? =
        beforeIncomeTax(result.netBeforeIncomeTax, result.complete)

    internal fun beforeIncomeTax(amount: Double, complete: Boolean): Double? =
        amount.takeIf { complete && it.isFinite() && it >= 0.0 }
}
