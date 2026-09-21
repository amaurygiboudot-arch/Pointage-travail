import Foundation

struct SalaryEmploymentContractSnapshotV2: Equatable {
    let versionId: String
    let sourceId: String
    let effectiveFromEpochDay: Int64
    let effectiveToEpochDay: Int64?
    let contract: ContractV2
    let checkedAtMs: Int64
    let note: String?

    func applies(to epochDay: Int64) -> Bool {
        guard epochDay >= effectiveFromEpochDay else { return false }
        guard let end = effectiveToEpochDay else { return true }
        return epochDay <= end
    }
}

struct SalaryEmploymentContractCoverageSegmentV2: Equatable {
    let startEpochDay: Int64
    let endEpochDay: Int64
    let snapshot: SalaryEmploymentContractSnapshotV2
}

struct SalaryEmploymentContractCoverageV2: Equatable {
    let companyId: String
    let periodStartEpochDay: Int64
    let periodEndEpochDay: Int64
    let segments: [SalaryEmploymentContractCoverageSegmentV2]
    let fullyCovered: Bool

    var singleSnapshotForWholePeriod: SalaryEmploymentContractSnapshotV2? {
        guard fullyCovered,
              segments.count == 1,
              segments[0].startEpochDay == periodStartEpochDay,
              segments[0].endEpochDay == periodEndEpochDay else {
            return nil
        }
        return segments[0].snapshot
    }

    var requiresMultipleContractVersions: Bool {
        fullyCovered && segments.count > 1
    }
}

/// Historique déterministe des contrats salariés confirmés.
///
/// Le contrat courant n'est jamais utilisé comme fallback pour une période historique. Chaque
/// changement de type, durée, taux ou forfait doit être représenté par une version explicitement
/// datée. Les chevauchements et doublons rendent l'historique invalide au lieu d'être arbitrés.
struct SalaryEmploymentContractHistoryV2 {
    private let versions: [String: [SalaryEmploymentContractSnapshotV2]]

    init?(_ snapshots: [SalaryEmploymentContractSnapshotV2]) {
        guard Self.structurallyValid(snapshots) else { return nil }
        versions = Dictionary(grouping: snapshots) {
            Self.normalizeCompanyId($0.contract.employerId)
        }.mapValues { items in
            items.sorted { $0.effectiveFromEpochDay > $1.effectiveFromEpochDay }
        }
    }

    func applicable(companyId: String?, epochDay: Int64) -> SalaryEmploymentContractSnapshotV2? {
        guard let companyId else { return nil }
        let normalized = Self.normalizeCompanyId(companyId)
        guard !normalized.isEmpty else { return nil }
        return versions[normalized]?.first { $0.applies(to: epochDay) }
    }

    func allVersions(companyId: String?) -> [SalaryEmploymentContractSnapshotV2] {
        guard let companyId else { return [] }
        let normalized = Self.normalizeCompanyId(companyId)
        guard !normalized.isEmpty else { return [] }
        return versions[normalized] ?? []
    }

    func coverage(
        companyId: String,
        periodStartEpochDay: Int64,
        periodEndEpochDay: Int64
    ) -> SalaryEmploymentContractCoverageV2? {
        let normalized = Self.normalizeCompanyId(companyId)
        guard !normalized.isEmpty, periodEndEpochDay >= periodStartEpochDay else { return nil }

        let segments = allVersions(companyId: normalized)
            .compactMap { snapshot -> SalaryEmploymentContractCoverageSegmentV2? in
                let start = max(snapshot.effectiveFromEpochDay, periodStartEpochDay)
                let end = min(snapshot.effectiveToEpochDay ?? periodEndEpochDay, periodEndEpochDay)
                guard start <= end else { return nil }
                return SalaryEmploymentContractCoverageSegmentV2(
                    startEpochDay: start,
                    endEpochDay: end,
                    snapshot: snapshot
                )
            }
            .sorted {
                if $0.startEpochDay == $1.startEpochDay {
                    return $0.endEpochDay < $1.endEpochDay
                }
                return $0.startEpochDay < $1.startEpochDay
            }

        let fullyCovered = Self.coversEveryDay(
            segments,
            start: periodStartEpochDay,
            end: periodEndEpochDay
        )
        return SalaryEmploymentContractCoverageV2(
            companyId: normalized,
            periodStartEpochDay: periodStartEpochDay,
            periodEndEpochDay: periodEndEpochDay,
            segments: segments,
            fullyCovered: fullyCovered
        )
    }

    private static func structurallyValid(_ snapshots: [SalaryEmploymentContractSnapshotV2]) -> Bool {
        for snapshot in snapshots {
            guard !snapshot.versionId.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
                  !snapshot.sourceId.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
                  !snapshot.contract.id.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
                  !normalizeCompanyId(snapshot.contract.employerId).isEmpty,
                  snapshot.checkedAtMs >= 0,
                  snapshot.effectiveToEpochDay.map({ $0 >= snapshot.effectiveFromEpochDay }) ?? true else {
                return false
            }
        }

        let grouped = Dictionary(grouping: snapshots) { normalizeCompanyId($0.contract.employerId) }
        for (companyId, items) in grouped {
            let versionIds = items.map { $0.versionId.trimmingCharacters(in: .whitespacesAndNewlines) }
            guard Set(versionIds).count == versionIds.count else { return false }

            let ascending = items.sorted { $0.effectiveFromEpochDay < $1.effectiveFromEpochDay }
            guard ascending.count > 1 else { continue }
            for index in 1..<ascending.count {
                let previous = ascending[index - 1]
                let current = ascending[index]
                guard let previousEnd = previous.effectiveToEpochDay,
                      previousEnd < current.effectiveFromEpochDay else {
                    _ = companyId
                    return false
                }
            }
        }
        return true
    }

    private static func coversEveryDay(
        _ segments: [SalaryEmploymentContractCoverageSegmentV2],
        start: Int64,
        end: Int64
    ) -> Bool {
        guard !segments.isEmpty else { return false }
        var cursor = start
        for segment in segments {
            guard segment.startEpochDay == cursor else { return false }
            if segment.endEpochDay == end { return true }
            guard segment.endEpochDay < end,
                  segment.endEpochDay < Int64.max else {
                return false
            }
            cursor = segment.endEpochDay + 1
        }
        return false
    }

    private static func normalizeCompanyId(_ value: String) -> String {
        value.trimmingCharacters(in: .whitespacesAndNewlines)
    }
}
