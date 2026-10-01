import CoreFoundation
import Foundation

struct SalaryAbsenceSourceV2: Equatable {
    let absences: [SalaryAbsenceFactV2]
    let reliable: Bool
    let warnings: [String]
}

/// Stockage local iOS des absences utilisées par Salaire V2.
///
/// Une liste vide n'est jamais assimilée à « aucune absence » sans confirmation mensuelle
/// exhaustive explicite. Toute mutation de la liste invalide les confirmations existantes.
enum SalaryAbsenceStoreV2 {
    struct ReadResult: Equatable {
        let absences: [SalaryAbsenceFactV2]
        let reliable: Bool
        let warnings: [String]
    }

    struct MonthConfirmation: Equatable {
        let period: YearMonthV2
        let source: String
    }

    struct ConfirmationReadResult: Equatable {
        let confirmations: [MonthConfirmation]
        let reliable: Bool
        let warnings: [String]
    }

    static let storageWarning =
        "Absences : stockage local incohérent ; aucune absence ni absence d'absence ne peut être déduite."
    static let coverageWarning =
        "Absences : confirmation mensuelle incohérente ; la liste du mois reste à confirmer."

    private static let recordsPrefix = "salary_absences_v2.records."
    private static let confirmationsPrefix = "salary_absences_v2.confirmations."
    private static let lock = NSLock()

    static func resolve(
        companyId rawCompanyId: String,
        period: YearMonthV2,
        defaults: UserDefaults = .standard
    ) -> SalaryAbsenceSourceV2 {
        let companyId = normalized(rawCompanyId)
        guard confirmedCompany(companyId, defaults: defaults) else {
            return SalaryAbsenceSourceV2(
                absences: [],
                reliable: false,
                warnings: ["Absences : entreprise absente ou stockage des entreprises non fiable."]
            )
        }

        let records = read(companyId: companyId, defaults: defaults)
        let confirmations = readConfirmations(companyId: companyId, defaults: defaults)
        guard records.reliable, confirmations.reliable else {
            return SalaryAbsenceSourceV2(
                absences: records.absences,
                reliable: false,
                warnings: unique(records.warnings + confirmations.warnings)
            )
        }

        let matching = confirmations.confirmations.filter { $0.period == period }
        guard matching.count == 1 else {
            let warning = matching.isEmpty
                ? "Absences \(period) : liste mensuelle non confirmée exhaustive ; une liste vide ne vaut pas absence d'absence."
                : coverageWarning
            return SalaryAbsenceSourceV2(
                absences: records.absences,
                reliable: false,
                warnings: [warning]
            )
        }

        return SalaryAbsenceSourceV2(
            absences: records.absences,
            reliable: true,
            warnings: []
        )
    }

    static func read(
        companyId rawCompanyId: String,
        defaults: UserDefaults = .standard
    ) -> ReadResult {
        let companyId = normalized(rawCompanyId)
        guard confirmedCompany(companyId, defaults: defaults) else {
            return ReadResult(
                absences: [],
                reliable: false,
                warnings: ["Absences : entreprise absente ou stockage des entreprises non fiable."]
            )
        }
        guard let object = defaults.object(forKey: recordsKey(companyId)) else {
            return ReadResult(absences: [], reliable: true, warnings: [])
        }
        guard let raw = object as? String else { return unreliableRead() }
        return decodeRecords(raw, expectedCompanyId: companyId)
    }

    static func readConfirmations(
        companyId rawCompanyId: String,
        defaults: UserDefaults = .standard
    ) -> ConfirmationReadResult {
        let companyId = normalized(rawCompanyId)
        guard confirmedCompany(companyId, defaults: defaults) else {
            return ConfirmationReadResult(
                confirmations: [],
                reliable: false,
                warnings: ["Absences : entreprise absente ou stockage des entreprises non fiable."]
            )
        }
        guard let object = defaults.object(forKey: confirmationsKey(companyId)) else {
            return ConfirmationReadResult(confirmations: [], reliable: true, warnings: [])
        }
        guard let raw = object as? String else {
            return ConfirmationReadResult(
                confirmations: [],
                reliable: false,
                warnings: [coverageWarning]
            )
        }
        return decodeConfirmations(raw)
    }

    @discardableResult
    static func save(
        companyId rawCompanyId: String,
        absence: SalaryAbsenceFactV2,
        defaults: UserDefaults = .standard
    ) -> Bool {
        let companyId = normalized(rawCompanyId)
        guard confirmedCompany(companyId, defaults: defaults),
              valid(absence, companyId: companyId) else {
            return false
        }

        lock.lock()
        defer { lock.unlock() }

        let stored = read(companyId: companyId, defaults: defaults)
        let confirmations = readConfirmations(companyId: companyId, defaults: defaults)
        guard stored.reliable, confirmations.reliable else { return false }

        var items = stored.absences.filter { $0.id != absence.id }
        items.append(absence)
        guard writeRecords(items, companyId: companyId, defaults: defaults) else {
            return false
        }
        // Toute modification peut changer plusieurs mois pour une absence traversante.
        defaults.removeObject(forKey: confirmationsKey(companyId))
        return read(companyId: companyId, defaults: defaults).reliable
    }

    @discardableResult
    static func remove(
        companyId rawCompanyId: String,
        absenceId rawAbsenceId: String,
        defaults: UserDefaults = .standard
    ) -> Bool {
        let companyId = normalized(rawCompanyId)
        let absenceId = rawAbsenceId.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !absenceId.isEmpty,
              confirmedCompany(companyId, defaults: defaults) else {
            return false
        }

        lock.lock()
        defer { lock.unlock() }

        let stored = read(companyId: companyId, defaults: defaults)
        guard stored.reliable else { return false }
        let remaining = stored.absences.filter { $0.id != absenceId }
        guard writeRecords(remaining, companyId: companyId, defaults: defaults) else {
            return false
        }
        defaults.removeObject(forKey: confirmationsKey(companyId))
        return true
    }

    @discardableResult
    static func confirmMonth(
        companyId rawCompanyId: String,
        period: YearMonthV2,
        source rawSource: String,
        defaults: UserDefaults = .standard
    ) -> Bool {
        let companyId = normalized(rawCompanyId)
        let source = rawSource.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !source.isEmpty,
              confirmedCompany(companyId, defaults: defaults) else {
            return false
        }

        lock.lock()
        defer { lock.unlock() }

        let records = read(companyId: companyId, defaults: defaults)
        let stored = readConfirmations(companyId: companyId, defaults: defaults)
        guard records.reliable, stored.reliable else { return false }

        var confirmations = stored.confirmations.filter { $0.period != period }
        confirmations.append(MonthConfirmation(period: period, source: source))
        guard let raw = encodeConfirmations(confirmations) else { return false }
        defaults.set(raw, forKey: confirmationsKey(companyId))
        guard defaults.string(forKey: confirmationsKey(companyId)) == raw else { return false }
        let reloaded = readConfirmations(companyId: companyId, defaults: defaults)
        return reloaded.reliable && reloaded.confirmations.contains {
            $0.period == period && $0.source == source
        }
    }

    static func decodeRecords(
        _ raw: String,
        expectedCompanyId: String
    ) -> ReadResult {
        guard let data = raw.data(using: .utf8),
              let array = try? JSONSerialization.jsonObject(with: data) as? [[String: Any]] else {
            return unreliableRead()
        }

        var items: [SalaryAbsenceFactV2] = []
        var malformed = false
        for object in array {
            guard let absence = decodeAbsence(object),
                  valid(absence, companyId: expectedCompanyId) else {
                malformed = true
                continue
            }
            items.append(absence)
        }
        if Set(items.map(\.id)).count != items.count { malformed = true }

        return ReadResult(
            absences: items,
            reliable: !malformed,
            warnings: malformed ? [storageWarning] : []
        )
    }

    static func decodeConfirmations(_ raw: String) -> ConfirmationReadResult {
        guard let data = raw.data(using: .utf8),
              let array = try? JSONSerialization.jsonObject(with: data) as? [[String: Any]] else {
            return ConfirmationReadResult(
                confirmations: [],
                reliable: false,
                warnings: [coverageWarning]
            )
        }

        var confirmations: [MonthConfirmation] = []
        var malformed = false
        for object in array {
            guard let periodRaw = object["period"] as? String,
                  let period = YearMonthV2(periodRaw),
                  let sourceRaw = object["source"] as? String else {
                malformed = true
                continue
            }
            let source = sourceRaw.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !source.isEmpty else {
                malformed = true
                continue
            }
            confirmations.append(MonthConfirmation(period: period, source: source))
        }
        if Set(confirmations.map(\.period)).count != confirmations.count {
            malformed = true
        }

        return ConfirmationReadResult(
            confirmations: confirmations,
            reliable: !malformed,
            warnings: malformed ? [coverageWarning] : []
        )
    }

    private static func writeRecords(
        _ items: [SalaryAbsenceFactV2],
        companyId: String,
        defaults: UserDefaults
    ) -> Bool {
        guard items.allSatisfy({ valid($0, companyId: companyId) }),
              Set(items.map(\.id)).count == items.count,
              let raw = encodeRecords(items) else {
            return false
        }
        defaults.set(raw, forKey: recordsKey(companyId))
        return defaults.string(forKey: recordsKey(companyId)) == raw
    }

    private static func encodeRecords(_ items: [SalaryAbsenceFactV2]) -> String? {
        let array: [[String: Any]] = items.map { absence in
            [
                "id": absence.id,
                "employerId": absence.employerId ?? NSNull(),
                "type": absence.type,
                "startMs": absence.start.timeIntervalSince1970 * 1_000.0,
                "endMs": absence.end.timeIntervalSince1970 * 1_000.0,
                "salaryTreatment": absence.salaryTreatment.rawValue,
                "fullDay": absence.fullDay,
                "status": absence.status.rawValue
            ]
        }
        guard JSONSerialization.isValidJSONObject(array),
              let data = try? JSONSerialization.data(withJSONObject: array),
              let raw = String(data: data, encoding: .utf8) else {
            return nil
        }
        return raw
    }

    private static func encodeConfirmations(
        _ confirmations: [MonthConfirmation]
    ) -> String? {
        let array = confirmations
            .sorted { $0.period < $1.period }
            .map { ["period": $0.period.description, "source": $0.source] }
        guard JSONSerialization.isValidJSONObject(array),
              let data = try? JSONSerialization.data(withJSONObject: array),
              let raw = String(data: data, encoding: .utf8) else {
            return nil
        }
        return raw
    }

    private static func decodeAbsence(
        _ object: [String: Any]
    ) -> SalaryAbsenceFactV2? {
        guard let idRaw = object["id"] as? String,
              let typeRaw = object["type"] as? String,
              let startNumber = object["startMs"] as? NSNumber,
              let endNumber = object["endMs"] as? NSNumber,
              !isBool(startNumber),
              !isBool(endNumber),
              let treatmentRaw = object["salaryTreatment"] as? String,
              let treatment = SalaryAbsenceTreatmentV2(rawValue: treatmentRaw),
              let fullDay = object["fullDay"] as? Bool,
              let statusRaw = object["status"] as? String,
              let status = SalaryAbsenceDecisionStatusV2(rawValue: statusRaw) else {
            return nil
        }

        let id = idRaw.trimmingCharacters(in: .whitespacesAndNewlines)
        let type = typeRaw.trimmingCharacters(in: .whitespacesAndNewlines)
        let startSeconds = startNumber.doubleValue / 1_000.0
        let endSeconds = endNumber.doubleValue / 1_000.0
        guard !id.isEmpty, !type.isEmpty,
              startSeconds.isFinite, endSeconds.isFinite else {
            return nil
        }

        let employerId: String?
        if object["employerId"] == nil || object["employerId"] is NSNull {
            employerId = nil
        } else if let raw = object["employerId"] as? String {
            let value = normalized(raw)
            employerId = value.isEmpty ? nil : value
        } else {
            return nil
        }

        return SalaryAbsenceFactV2(
            id: id,
            employerId: employerId,
            type: type,
            start: Date(timeIntervalSince1970: startSeconds),
            end: Date(timeIntervalSince1970: endSeconds),
            salaryTreatment: treatment,
            fullDay: fullDay,
            status: status
        )
    }

    private static func valid(
        _ absence: SalaryAbsenceFactV2,
        companyId: String
    ) -> Bool {
        let id = absence.id.trimmingCharacters(in: .whitespacesAndNewlines)
        let type = absence.type.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !id.isEmpty, !type.isEmpty,
              absence.start.timeIntervalSince1970.isFinite,
              absence.end.timeIntervalSince1970.isFinite,
              absence.end > absence.start else {
            return false
        }
        let employer = normalized(absence.employerId ?? "")
        return employer == companyId
    }

    private static func confirmedCompany(
        _ companyId: String,
        defaults: UserDefaults
    ) -> Bool {
        guard !companyId.isEmpty else { return false }
        return SalaryCompanyStoreV2.confirmedCompany(
            SalaryCompanyStoreV2.readConfirmed(defaults: defaults),
            companyId: companyId
        ) != nil
    }

    private static func recordsKey(_ companyId: String) -> String {
        recordsPrefix + companyId
    }

    private static func confirmationsKey(_ companyId: String) -> String {
        confirmationsPrefix + companyId
    }

    private static func normalized(_ value: String) -> String {
        value.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private static func isBool(_ value: NSNumber) -> Bool {
        CFGetTypeID(value) == CFBooleanGetTypeID()
    }

    private static func unreliableRead() -> ReadResult {
        ReadResult(absences: [], reliable: false, warnings: [storageWarning])
    }

    private static func unique(_ values: [String]) -> [String] {
        var seen = Set<String>()
        return values.filter { seen.insert($0).inserted }
    }
}
