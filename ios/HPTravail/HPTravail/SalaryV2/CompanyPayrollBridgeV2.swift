import Foundation

enum CompanyPayrollBridgeV2 {
    struct Result: Equatable {
        let payroll: PayrollResultV2
        let confirmedEmployeeDeductionsComplete: Bool
        let warnings: [String]
        let traces: [String]
    }

    static func calculate(
        defaults: UserDefaults = .standard,
        companyId: String,
        period: CompanyEmployeeDeductionResolverV2.YearMonth,
        contract: ContractV2,
        weeks: [PayrollWeekV2],
        rules: PayrollRulesV2,
        premiums: [PremiumV2] = [],
        baskets: [BasketV2] = []
    ) throws -> Result {
        let snapshot = CompanyEmployeeDeductionStoreV2.resolve(
            defaults: defaults,
            companyId: companyId,
            period: period
        )
        let resolved = CompanyEmployeeDeductionPayrollBridgeV2.resolve(
            snapshot: snapshot,
            period: period
        )

        let payroll = try PayrollEngineV2.calculate(
            contract: contract,
            weeks: weeks,
            rules: rules,
            premiums: premiums,
            baskets: baskets,
            deductions: resolved.deductions
        )

        var warnings = snapshot.warnings + resolved.warnings
        if !resolved.confirmedEmployeeDeductionsComplete {
            warnings.append(
                "Retenues salariales entreprise incomplètes pour \(period) : le sous-total après retenues connues reste informatif et ne constitue pas un net salarié final."
            )
        }

        return Result(
            payroll: payroll,
            confirmedEmployeeDeductionsComplete: resolved.confirmedEmployeeDeductionsComplete,
            warnings: unique(warnings),
            traces: unique(payroll.traces + resolved.traces)
        )
    }

    private static func unique(_ values: [String]) -> [String] {
        var seen = Set<String>()
        return values.filter { seen.insert($0).inserted }
    }
}
