package com.amaury.pointage

data class TravelRight(
    val title: String,
    val detail: String
)

object TravelRightsCatalog {
    private val byIdcc: Map<String, List<TravelRight>> = mapOf(
        "3127" to listOf(
            TravelRight(
                "🚗 Client → client",
                "Le temps de déplacement entre deux interventions peut constituer du temps de travail effectif lorsque le salarié ne retrouve pas sa liberté entre les interventions."
            ),
            TravelRight(
                "🚙 Véhicule personnel",
                "Pour un déplacement professionnel effectué avec le véhicule personnel, la convention prévoit une indemnité kilométrique minimale de 0,35 € par kilomètre professionnel, sous réserve des conditions d'application du texte conventionnel."
            ),
            TravelRight(
                "🏠 Domicile → premier client",
                "Le trajet normal entre le domicile et le premier lieu d'intervention n'est pas, en principe, du temps de travail effectif."
            ),
            TravelRight(
                "🏠 Dernier client → domicile",
                "Le trajet normal entre le dernier lieu d'intervention et le domicile n'est pas, en principe, du temps de travail effectif."
            ),
            TravelRight(
                "⏱ Trajet anormalement long",
                "Lorsque le trajet domicile/intervention dépasse le trajet normal défini par la convention, une compensation financière peut être due. Le texte conventionnel prévoit notamment une référence minimale de 10 % du taux horaire dans les conditions qu'il fixe."
            ),
            TravelRight(
                "⏳ Attente entre interventions < 15 min",
                "Une interruption inférieure à 15 minutes entre deux interventions est considérée comme du temps de travail effectif et doit être rémunérée."
            ),
            TravelRight(
                "⏸ Interruption > 15 min",
                "Une interruption supérieure à 15 minutes n'est pas du temps de travail effectif si le salarié retrouve réellement sa liberté et peut vaquer à ses occupations personnelles."
            ),
            TravelRight(
                "🍴 Temps de repas",
                "Le temps de restauration est du temps de travail effectif lorsque le salarié doit rester sur le lieu d'intervention en raison des nécessités du service."
            ),
            TravelRight(
                "🥪 Indemnité repas / panier",
                "Une indemnité repas ou panier n'est pas automatiquement due dans toutes les situations. Il faut vérifier les conditions prévues par la convention, le contrat et les accords d'entreprise."
            ),
            TravelRight(
                "📏 Trajet normal",
                "La convention retient notamment comme référence de trajet domicile/intervention un trajet ne dépassant pas 45 minutes ou 30 km dans la zone d'intervention, selon les conditions prévues par le texte."
            ),
            TravelRight(
                "📋 Méthode de calcul",
                "L'entreprise doit appliquer une méthode de calcul des temps et kilomètres professionnels et la porter à la connaissance des salariés. Conserver les plannings, kilomètres et horaires permet de vérifier les montants réellement dus."
            )
        )
    )

    fun forIdcc(idcc: String): List<TravelRight> = byIdcc[idcc].orEmpty()
}
