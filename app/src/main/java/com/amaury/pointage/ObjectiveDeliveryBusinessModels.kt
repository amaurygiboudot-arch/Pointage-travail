package com.amaury.pointage

enum class ObjectiveContractType(val label: String) {
    CDI("CDI"),
    CDD("CDD"),
    INTERIM("Intérim")
}

enum class ObjectiveClassification(val label: String) {
    NON_CADRE("Non-cadre"),
    CADRE("Cadre")
}

data class ObjectiveEmployee(
    val id: String,
    val firstName: String,
    val role: String,
    val contractType: ObjectiveContractType,
    val classification: ObjectiveClassification,
    val weeklyHours: Int,
    val leaveBalanceDays: Int,
    val skillLabel: String,
    val skillLevel: Int,
    val seniorityMonths: Int,
    /**
     * Indice de rémunération interne au scénario.
     * 100 = plancher de référence simulé. Ce n'est pas un salaire réel.
     */
    val payIndex: Int,
    val morale: Int = 70
)

data class ObjectiveStockItem(
    val id: String,
    val label: String,
    val available: Int,
    val reserved: Int,
    val inbound: Int,
    val requiredForOrder: Int,
    val unitCost: Int
) {
    val freeToPromise: Int
        get() = (available - reserved).coerceAtLeast(0)

    val coveredAfterInbound: Boolean
        get() = freeToPromise + inbound >= requiredForOrder
}

data class ObjectiveBusinessState(
    val cash: Int = 25_000,
    val acceptedOrderPrice: Int = 0,
    val acceptedOrderDelayDays: Int = 0,
    val acceptedOrderMargin: Int = 0,
    val handoffQuality: Int = 0,
    val teamMorale: Int = 70,
    /**
     * Plancher du scénario exprimé en indice, pas en euros.
     * La grille interne ne peut pas passer sous cet indice.
     */
    val simulatedLegalFloorIndex: Int = 100,
    val payGridIndex: Int = 100,
    val annualRaiseEnvelopeBasisPoints: Int = 500,
    val annualRaiseUsedBasisPoints: Int = 0,
    val employees: List<ObjectiveEmployee> = emptyList(),
    val stock: List<ObjectiveStockItem> = emptyList(),
    val supplierReliability: Int = 75,
    val supplierLeadTimeDays: Int = 8
)

object ObjectiveBusinessScenarioFactory {
    fun initial(type: ObjectiveCompanyType): ObjectiveBusinessState =
        ObjectiveBusinessState(
            cash = when (type) {
                ObjectiveCompanyType.WORKSHOP -> 32_000
                ObjectiveCompanyType.RETAIL -> 24_000
                ObjectiveCompanyType.SERVICES -> 20_000
            },
            employees = initialEmployees(type),
            stock = initialStock(type),
            supplierReliability = when (type) {
                ObjectiveCompanyType.WORKSHOP -> 74
                ObjectiveCompanyType.RETAIL -> 82
                ObjectiveCompanyType.SERVICES -> 78
            },
            supplierLeadTimeDays = when (type) {
                ObjectiveCompanyType.WORKSHOP -> 12
                ObjectiveCompanyType.RETAIL -> 6
                ObjectiveCompanyType.SERVICES -> 8
            }
        )

    fun initialEmployees(type: ObjectiveCompanyType): List<ObjectiveEmployee> = when (type) {
        ObjectiveCompanyType.WORKSHOP -> listOf(
            employee(
                "lea",
                "Léa",
                "Commerce",
                ObjectiveContractType.CDI,
                ObjectiveClassification.NON_CADRE,
                35,
                16,
                "Qualification client",
                72,
                30,
                116
            ),
            employee(
                "nabil",
                "Nabil",
                "Étude technique",
                ObjectiveContractType.CDI,
                ObjectiveClassification.CADRE,
                39,
                12,
                "Faisabilité",
                80,
                44,
                142
            ),
            employee(
                "ines",
                "Inès",
                "Fabrication",
                ObjectiveContractType.CDI,
                ObjectiveClassification.NON_CADRE,
                35,
                18,
                "Montage",
                68,
                20,
                112
            ),
            employee(
                "tom",
                "Tom",
                "Fabrication",
                ObjectiveContractType.CDD,
                ObjectiveClassification.NON_CADRE,
                28,
                10,
                "Préparation",
                55,
                5,
                105
            )
        )

        ObjectiveCompanyType.RETAIL -> listOf(
            employee(
                "sara",
                "Sara",
                "Vente",
                ObjectiveContractType.CDI,
                ObjectiveClassification.NON_CADRE,
                35,
                15,
                "Conseil",
                74,
                26,
                114
            ),
            employee(
                "yanis",
                "Yanis",
                "Stock",
                ObjectiveContractType.CDI,
                ObjectiveClassification.NON_CADRE,
                35,
                17,
                "Inventaire",
                70,
                36,
                111
            ),
            employee(
                "mae",
                "Maë",
                "Logistique",
                ObjectiveContractType.CDD,
                ObjectiveClassification.NON_CADRE,
                30,
                11,
                "Préparation commande",
                61,
                8,
                106
            )
        )

        ObjectiveCompanyType.SERVICES -> listOf(
            employee(
                "jade",
                "Jade",
                "Accueil client",
                ObjectiveContractType.CDI,
                ObjectiveClassification.NON_CADRE,
                35,
                14,
                "Qualification",
                73,
                28,
                115
            ),
            employee(
                "sami",
                "Sami",
                "Technicien",
                ObjectiveContractType.CDI,
                ObjectiveClassification.NON_CADRE,
                35,
                13,
                "Diagnostic",
                78,
                40,
                124
            ),
            employee(
                "lina",
                "Lina",
                "Planification",
                ObjectiveContractType.CDI,
                ObjectiveClassification.CADRE,
                39,
                16,
                "Organisation",
                76,
                33,
                136
            )
        )
    }

    fun initialStock(type: ObjectiveCompanyType): List<ObjectiveStockItem> = when (type) {
        ObjectiveCompanyType.WORKSHOP -> listOf(
            ObjectiveStockItem("profile", "Profilés", 14, 6, 8, 12, 95),
            ObjectiveStockItem("filling", "Remplissage", 8, 4, 5, 7, 120),
            ObjectiveStockItem("hardware", "Quincaillerie", 24, 10, 0, 9, 28)
        )

        ObjectiveCompanyType.RETAIL -> listOf(
            ObjectiveStockItem("main_product", "Articles principaux", 18, 11, 10, 12, 42),
            ObjectiveStockItem("accessory", "Accessoires", 35, 14, 0, 16, 9),
            ObjectiveStockItem("packaging", "Emballages", 22, 8, 12, 14, 3)
        )

        ObjectiveCompanyType.SERVICES -> listOf(
            ObjectiveStockItem("spare", "Pièces courantes", 12, 7, 8, 9, 38),
            ObjectiveStockItem("consumable", "Consommables", 30, 12, 0, 10, 6),
            ObjectiveStockItem("kit", "Kit d'intervention", 5, 3, 2, 4, 65)
        )
    }

    private fun employee(
        id: String,
        firstName: String,
        role: String,
        contractType: ObjectiveContractType,
        classification: ObjectiveClassification,
        weeklyHours: Int,
        leaveBalanceDays: Int,
        skillLabel: String,
        skillLevel: Int,
        seniorityMonths: Int,
        payIndex: Int
    ): ObjectiveEmployee = ObjectiveEmployee(
        id = id,
        firstName = firstName,
        role = role,
        contractType = contractType,
        classification = classification,
        weeklyHours = weeklyHours,
        leaveBalanceDays = leaveBalanceDays,
        skillLabel = skillLabel,
        skillLevel = skillLevel,
        seniorityMonths = seniorityMonths,
        payIndex = payIndex
    )
}
