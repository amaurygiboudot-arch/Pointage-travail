import Foundation

struct SalarySegmentedPayrollWeekEvidenceV2: Equatable {
    let yearForWeekOfYear: Int
    let weekOfYear: Int
    let week: PayrollWeekV2
    let fullWeekContextReliable: Bool
}

struct SalarySegmentedPayrollSliceEvidenceV2: Equatable {
    let startEpochDay: Int64
    let endEpochDay: Int64
    let contractVersionId: String
    let ruleVersionId: String
    let weeks: [SalarySegmentedPayrollWeekEvidenceV2]
    let evidence: PayrollInputEvidenceV2
    let warnings: [String]

    init(
        startEpochDay: Int64,
        endEpochDay: Int64,
        contractVersionId: String,
        ruleVersionId: String,
        weeks: [SalarySegmentedPayrollWeekEvidenceV2],
        evidence: PayrollInputEvidenceV2,
        warnings: [String] = []
    ) {
        self.startEpochDay = startEpochDay
        self.endEpochDay = endEpochDay
        self.contractVersionId = contractVersionId
        self.ruleVersionId = ruleVersionId
        self.weeks = weeks
        self.evidence = evidence
        self.warnings = warnings
    }
}

struct SalarySegmentedWorkedVariableGrossSourceResultV2: Equatable {
    let pieces: [SalarySegmentedWorkedVariableGrossPieceV2]
    let reliable: Bool
    let warnings: [String]
}

/// Produit les variables de brut segmentées à partir de semaines déjà qualifiées avec leur
/// contexte hebdomadaire complet.
///
/// La base mensualisée proratisée appartient à B17/B20 et n'est jamais recalculée ici.
/// Cette couche ne conserve que les composantes additionnelles réellement prouvées.
///
/// - Temps plein : heures supplémentaires variables + majorations temporelles.
/// - Temps partiel : majorations temporelles uniquement tant que les heures complémentaires
///   reposent encore sur un barème supplétif non structuré.
///
/// Une même semaine ne doit apparaître qu'une fois, y compris au sein d'une même tranche.
/// Une duplication ou un partage entre tranches bloque le résultat avant tout calcul monétaire.
enum SalarySegmentedWorkedVariableGrossSourceV2 {
    static let timelineWarning =
        "Variables segmentées : la timeline contrat/règles est absente ou non fiable ; calcul bloqué."
    static let coverageWarning =
        "Variables segmentées : les preuves hebdomadaires ne correspondent pas exactement aux tranches de calcul."
    static let weekContextWarning =
        "Variables segmentées : une semaine est tronquée ou partagée entre plusieurs tranches ; les seuils hebdomadaires ne sont pas fiables."
    static let duplicateWeekWarning =
        "Variables segmentées : une même semaine est fournie plusieurs fois dans une tranche ; calcul bloqué pour éviter un double comptage."
    static let evidenceWarning =
        "Variables segmentées : les preuves de temps/règles/majorations sont incomplètes ; calcul bloqué."
    static let missingWeeksWarning =
        "Variables segmentées : aucune preuve hebdomadaire n'est fournie pour une tranche ; variable inconnue, calcul bloqué."
    static let weekCoverageWarning =
        "Variables segmentées : les semaines reçues ne couvrent pas exactement les dates de la tranche ; calcul bloqué."
    static let invalidPaidTimeWarning =
        "Variables segmentées : une durée payée hebdomadaire est négative ; calcul bloqué."
    static let unsupportedContractWarning =
        "Variables segmentées : ce type de contrat n'est pas supporté par la base segmentée actuelle."
    static let partTimeComplementaryWarning =
        "Variables segmentées : des heures complémentaires temps partiel existent mais leur barème conventionnel structuré n'est pas prouvé ; variable bloquée."
    static let overtimeWarning =
        "Variables segmentées : les heures supplémentaires variables ne sont pas entièrement couvertes par des paliers confirmés."
    static let amountWarning =
        "Variables segmentées : montant variable non fini ou négatif ; calcul bloqué."

    static func calculate(
        contracts: SalaryEmploymentContractPeriodResolutionV2,
        rules: SalaryConventionCoverageV2,
        sliceEvidence: [SalarySegmentedPayrollSliceEvidenceV2]
    ) -> SalarySegmentedWorkedVariableGrossSourceResultV2 {
        let timeline = SalaryPayrollCalculationTimelineV2.align(
            contracts: contracts,
            rules: rules
        )
        guard timeline.reliable, !timeline.slices.isEmpty else {
            return blocked(timeline.warnings + [timelineWarning])
        }

        let boundary = SalarySegmentedPayrollBoundaryV2.assess(
            contracts: contracts,
            rules: rules
        )
        guard boundary.timelineReliable,
              boundary.safeForIndependentWeeklyVariableCalculation else {
            return blocked(
                timeline.warnings
                    + boundary.warnings
                    + [weekContextWarning]
            )
        }

        let expectedKeys = timeline.slices.map(sliceKey)
        let providedKeys = sliceEvidence.map {
            SliceKey(
                startEpochDay: $0.startEpochDay,
                endEpochDay: $0.endEpochDay,
                contractVersionId: normalized($0.contractVersionId),
                ruleVersionId: normalized($0.ruleVersionId)
            )
        }
        guard expectedKeys.allSatisfy({
            !$0.contractVersionId.isEmpty && !$0.ruleVersionId.isEmpty
        }),
        providedKeys.allSatisfy({
            !$0.contractVersionId.isEmpty && !$0.ruleVersionId.isEmpty
        }),
        Set(expectedKeys).count == expectedKeys.count,
        Set(providedKeys).count == providedKeys.count,
        Set(expectedKeys) == Set(providedKeys) else {
            return blocked(timeline.warnings + [coverageWarning])
        }

        let evidenceByKey = Dictionary(
            uniqueKeysWithValues: sliceEvidence.map {
                (
                    SliceKey(
                        startEpochDay: $0.startEpochDay,
                        endEpochDay: $0.endEpochDay,
                        contractVersionId: normalized($0.contractVersionId),
                        ruleVersionId: normalized($0.ruleVersionId)
                    ),
                    $0
                )
            }
        )

        var weekOwners: [WeekKey: SliceKey] = [:]
        var variableByContract: [ContractSegmentKey: Double] = [:]
        var warnings = timeline.warnings

        for slice in timeline.slices {
            let key = sliceKey(slice)
            guard let supplied = evidenceByKey[key] else {
                return blocked(warnings + [coverageWarning])
            }

            // Une liste vide ne prouve pas un zéro : conserver une semaine explicitement qualifiée.
            guard !supplied.weeks.isEmpty else {
                return blocked(
                    warnings
                        + supplied.warnings
                        + supplied.evidence.warnings
                        + [missingWeeksWarning]
                )
            }

            // Bloquer avant les préconditions des calculateurs, sans convertir la corruption en zéro.
            guard supplied.weeks.allSatisfy({ $0.week.paidMinutes >= 0 }) else {
                return blocked(
                    warnings
                        + supplied.warnings
                        + supplied.evidence.warnings
                        + [invalidPaidTimeWarning]
                )
            }

            guard supplied.evidence.grossInputsReliable,
                  supplied.weeks.allSatisfy({ item in item.fullWeekContextReliable }) else {
                return blocked(
                    warnings
                        + supplied.warnings
                        + supplied.evidence.warnings
                        + [evidenceWarning]
                )
            }

            for item in supplied.weeks {
                let weekKey = WeekKey(
                    yearForWeekOfYear: item.yearForWeekOfYear,
                    weekOfYear: item.weekOfYear
                )
                if let previous = weekOwners[weekKey] {
                    let warning = previous == key ? duplicateWeekWarning : weekContextWarning
                    return blocked(warnings + [warning])
                }
                weekOwners[weekKey] = key
            }

            guard hasExactWeekCoverage(
                startEpochDay: slice.startEpochDay,
                endEpochDay: slice.endEpochDay,
                weeks: supplied.weeks
            ) else {
                return blocked(
                    warnings
                        + supplied.warnings
                        + supplied.evidence.warnings
                        + [weekCoverageWarning]
                )
            }

            let contract = slice.contractSnapshot.contract
            guard let rate = contract.grossHourlyRate,
                  rate.isFinite,
                  rate > 0 else {
                return blocked(warnings + [amountWarning])
            }

            let payrollWeeks = supplied.weeks.map { item in item.week }
            let variable: Double
            switch contract.type {
            case .fullTime:
                guard let value = fullTimeVariable(
                    contract: contract,
                    rate: rate,
                    weeks: payrollWeeks,
                    rules: slice.ruleSnapshot.rules,
                    evidence: supplied.evidence,
                    warnings: &warnings
                ) else {
                    return blocked(warnings + [overtimeWarning])
                }
                variable = value

            case .partTime:
                guard let value = partTimeVariable(
                    contract: contract,
                    rate: rate,
                    weeks: payrollWeeks,
                    rules: slice.ruleSnapshot.rules,
                    evidence: supplied.evidence,
                    warnings: &warnings
                ) else {
                    return blocked(warnings + [partTimeComplementaryWarning])
                }
                variable = value

            case .forfaitHours, .forfaitDays, .forfait, .other:
                return blocked(warnings + [unsupportedContractWarning])
            }

            guard variable.isFinite,
                  variable >= -currencyTolerance else {
                return blocked(warnings + [amountWarning])
            }

            guard let contractSegment = contracts.calculationSegments.single(where: {
                normalized($0.snapshot.versionId) == normalized(slice.contractVersionId)
                    && slice.startEpochDay >= $0.startEpochDay
                    && slice.endEpochDay <= $0.endEpochDay
            }) else {
                return blocked(warnings + [coverageWarning])
            }

            let contractKey = ContractSegmentKey(
                versionId: normalized(contractSegment.snapshot.versionId),
                startEpochDay: contractSegment.startEpochDay,
                endEpochDay: contractSegment.endEpochDay
            )
            let normalizedVariable = abs(variable) <= currencyTolerance ? 0 : variable
            let next = (variableByContract[contractKey] ?? 0) + normalizedVariable
            guard next.isFinite, next >= -currencyTolerance else {
                return blocked(warnings + [amountWarning])
            }
            variableByContract[contractKey] =
                abs(next) <= currencyTolerance ? 0 : next
            warnings.append(contentsOf: supplied.warnings)
        }

        let expectedContractKeys = contracts.calculationSegments.map {
            ContractSegmentKey(
                versionId: normalized($0.snapshot.versionId),
                startEpochDay: $0.startEpochDay,
                endEpochDay: $0.endEpochDay
            )
        }
        guard Set(expectedContractKeys).count == expectedContractKeys.count,
              Set(variableByContract.keys) == Set(expectedContractKeys) else {
            return blocked(warnings + [coverageWarning])
        }

        let companyId = normalized(contracts.companyId)
        guard !companyId.isEmpty else {
            return blocked(warnings + [coverageWarning])
        }

        var pieces: [SalarySegmentedWorkedVariableGrossPieceV2] = []
        for segment in contracts.calculationSegments.sorted(by: {
            $0.startEpochDay < $1.startEpochDay
        }) {
            let key = ContractSegmentKey(
                versionId: normalized(segment.snapshot.versionId),
                startEpochDay: segment.startEpochDay,
                endEpochDay: segment.endEpochDay
            )
            guard let amount = variableByContract[key] else {
                return blocked(warnings + [coverageWarning])
            }
            pieces.append(
                SalarySegmentedWorkedVariableGrossPieceV2(
                    companyId: companyId,
                    versionId: key.versionId,
                    startEpochDay: key.startEpochDay,
                    endEpochDay: key.endEpochDay,
                    variableGross: amount,
                    reliable: true
                )
            )
        }

        return SalarySegmentedWorkedVariableGrossSourceResultV2(
            pieces: pieces,
            reliable: true,
            warnings: unique(warnings)
        )
    }

    private static func fullTimeVariable(
        contract: ContractV2,
        rate: Double,
        weeks: [PayrollWeekV2],
        rules: PayrollRulesV2,
        evidence: PayrollInputEvidenceV2,
        warnings: inout [String]
    ) -> Double? {
        guard let contractual = contract.contractualWeeklyMinutes,
              contractual > 0,
              let regularLimit = rules.weeklyRegularMinutes,
              regularLimit > 0 else {
            return nil
        }

        let overtime = FullTimeStructuralOvertimeV2.calculate(
            contractualWeeklyMinutes: contractual,
            regularWeeklyLimit: regularLimit,
            paidWeeks: weeks.map { week in week.paidMinutes },
            grossHourlyRate: rate,
            overtimeTiers: rules.overtimeTiers
        )
        warnings.append(contentsOf: overtime.warnings)

        guard !overtime.provisionalRateUsed,
              overtime.unresolvedStructuralOvertimeMinutes <= 0,
              overtime.unresolvedVariableOvertimeMinutes <= 0 else {
            return nil
        }

        do {
            let premium = try weeks.reduce(0.0) {
                $0 + (try SalaryPayrollPremiumGrossV2.calculate(
                    week: $1,
                    grossHourlyRate: rate,
                    rules: rules
                ))
            }
            guard evidence.grossInputsReliable,
                  premium.isFinite,
                  premium >= 0 else {
                return nil
            }
            let total = overtime.variableOvertimeGross + premium
            return total.isFinite && total >= 0 ? total : nil
        } catch {
            return nil
        }
    }

    private static func partTimeVariable(
        contract: ContractV2,
        rate: Double,
        weeks: [PayrollWeekV2],
        rules: PayrollRulesV2,
        evidence: PayrollInputEvidenceV2,
        warnings: inout [String]
    ) -> Double? {
        guard let contractual = contract.contractualWeeklyMinutes,
              contractual > 0,
              evidence.grossInputsReliable else {
            return nil
        }

        for week in weeks {
            do {
                let complementary = try PartTimeComplementaryHoursV2.calculateWeek(
                    contractualMinutes: contractual,
                    paidMinutes: week.paidMinutes,
                    grossHourlyRate: rate
                )
                warnings.append(contentsOf: complementary.warnings)
                if complementary.complementaryMinutes > 0 {
                    return nil
                }
            } catch {
                return nil
            }
        }

        do {
            let premium = try weeks.reduce(0.0) { partial, week in
                partial + (try SalaryPayrollPremiumGrossV2.calculate(
                    week: week,
                    grossHourlyRate: rate,
                    rules: rules
                ))
            }
            return premium.isFinite && premium >= 0 ? premium : nil
        } catch {
            return nil
        }
    }

    /// Vérifie les semaines ISO touchant les bornes inclusives, sans modifier les durées/montants.
    /// Calendrier grégorien proleptique, années civiles 1 à 9999, commun Android/iOS.
    /// Aucun calendrier utilisateur ni fuseau ne modifie les identités de semaines.
    /// Les semaines de bord conservent leur contexte complet et leurs seuils hebdomadaires.
    private static func hasExactWeekCoverage(
        startEpochDay: Int64,
        endEpochDay: Int64,
        weeks: [SalarySegmentedPayrollWeekEvidenceV2]
    ) -> Bool {
        guard startEpochDay >= -719162,
              endEpochDay <= 2932896,
              endEpochDay >= startEpochDay else {
            return false
        }

        let firstMonday = startEpochDay - ((startEpochDay % 7 + 10) % 7)
        let lastMonday = endEpochDay - ((endEpochDay % 7 + 10) % 7)
        let expectedCount = (lastMonday - firstMonday) / 7 + 1
        guard Int64(weeks.count) == expectedCount else { return false }

        var seen = Set<Int64>()
        for item in weeks {
            guard (1...9999).contains(item.yearForWeekOfYear),
                  (1...53).contains(item.weekOfYear) else { return false }
            let yearStart = firstIsoMonday(year: item.yearForWeekOfYear)
            let monday = yearStart + Int64(item.weekOfYear - 1) * 7
            guard monday < firstIsoMonday(year: item.yearForWeekOfYear + 1),
                  monday >= firstMonday, monday <= lastMonday,
                  seen.insert(monday).inserted else { return false }
        }
        return true
    }

    // La semaine ISO 1 contient le 4 janvier. Les années bissextiles sont comptées
    // par y/4 - y/100 + y/400 ; -719159 rattache le 4 janvier à l'epoch Unix.
    // Appel uniquement avec year dans 1...10000, après validation des identifiants.
    private static func firstIsoMonday(year: Int) -> Int64 {
        let y = Int64(year) - 1
        let january4 = 365 * y + y / 4 - y / 100 + y / 400 - 719159
        return january4 - ((january4 % 7 + 10) % 7)
    }

    private static func sliceKey(
        _ slice: SalaryPayrollCalculationSliceV2
    ) -> SliceKey {
        SliceKey(
            startEpochDay: slice.startEpochDay,
            endEpochDay: slice.endEpochDay,
            contractVersionId: normalized(slice.contractSnapshot.versionId),
            ruleVersionId: normalized(slice.ruleSnapshot.versionId)
        )
    }

    private static func normalized(_ raw: String) -> String {
        raw.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private static func blocked(
        _ warnings: [String]
    ) -> SalarySegmentedWorkedVariableGrossSourceResultV2 {
        SalarySegmentedWorkedVariableGrossSourceResultV2(
            pieces: [],
            reliable: false,
            warnings: unique(warnings)
        )
    }

    private static func unique(_ values: [String]) -> [String] {
        var seen = Set<String>()
        return values.filter { seen.insert($0).inserted }
    }

    private struct SliceKey: Hashable {
        let startEpochDay: Int64
        let endEpochDay: Int64
        let contractVersionId: String
        let ruleVersionId: String
    }

    private struct WeekKey: Hashable {
        let yearForWeekOfYear: Int
        let weekOfYear: Int
    }

    private struct ContractSegmentKey: Hashable {
        let versionId: String
        let startEpochDay: Int64
        let endEpochDay: Int64
    }

    private static let currencyTolerance = 0.005
}

private extension Array {
    func single(where predicate: (Element) -> Bool) -> Element? {
        var match: Element?
        for element in self where predicate(element) {
            guard match == nil else { return nil }
            match = element
        }
        return match
    }
}
