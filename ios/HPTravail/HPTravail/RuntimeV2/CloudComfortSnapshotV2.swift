import Foundation
import CoreFoundation

/// Firebase-free validation and optimistic concurrency rules for the manual account snapshot.
public struct CloudComfortSnapshotV2: Equatable {
    public let revision: Int
    public let payload: String
    public let deleted: Bool
    public let transfer: ComfortTransferV2?
    public static let maximumRevision = 1_000_000_000
    public enum SnapshotError: Error { case malformed, conflict, exhausted }

    public static func parse(_ fields: [String: Any], timestampIsValid: Bool) throws -> Self {
        guard Set(fields.keys) == ["schemaVersion", "revision", "payload", "deleted", "updatedAt"],
              timestampIsValid,
              integer(fields["schemaVersion"]) == 1,
              let revision = integer(fields["revision"]), (1...maximumRevision).contains(revision),
              let payload = fields["payload"] as? String,
              let deletedNumber = fields["deleted"] as? NSNumber,
              CFGetTypeID(deletedNumber) == CFBooleanGetTypeID() else { throw SnapshotError.malformed }
        let deleted = deletedNumber.boolValue
        guard deleted ? payload.isEmpty : !payload.isEmpty else { throw SnapshotError.malformed }
        let transfer = deleted ? nil : try ComfortTransferV2.decode(payload)
        return Self(revision: revision, payload: payload, deleted: deleted, transfer: transfer)
    }

    private static func integer(_ value: Any?) -> Int? {
        guard let number = value as? NSNumber, CFGetTypeID(number) != CFBooleanGetTypeID() else { return nil }
        let value = number.doubleValue
        guard value.isFinite, value.rounded() == value, value >= 0, value <= Double(maximumRevision) else { return nil }
        return Int(value)
    }

    public static func nextRevision(expected: Int, actual: Int?) throws -> Int {
        let current = actual ?? 0
        guard expected >= 0, expected <= maximumRevision, current == expected else { throw SnapshotError.conflict }
        guard current < maximumRevision else { throw SnapshotError.exhausted }
        return current + 1
    }
}
