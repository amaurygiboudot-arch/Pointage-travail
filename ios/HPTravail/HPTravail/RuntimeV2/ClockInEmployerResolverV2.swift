import Foundation

enum ClockInEmployerResolutionV2: Equatable {
    case unassigned
    case employer(String)
    case rejected
}

enum ClockInEmployerResolverV2 {
    static func resolve(
        requestedEmployerId: String?,
        companies: SalaryCompanyReadResultV2
    ) -> ClockInEmployerResolutionV2 {
        guard let requestedEmployerId else { return .unassigned }
        let employerId = requestedEmployerId.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !employerId.isEmpty,
              companies.reliable,
              companies.companies.contains(where: { $0.id == employerId }) else {
            return .rejected
        }
        return .employer(employerId)
    }
}
