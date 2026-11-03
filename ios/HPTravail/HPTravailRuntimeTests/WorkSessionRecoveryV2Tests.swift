import Foundation
import XCTest
@testable import RuntimeV2Contract

final class WorkSessionRecoveryV2Tests: XCTestCase {
    private func session(_ start: TimeInterval = 100, id: UUID = UUID(), open: Bool = false) -> WorkSession {
        WorkSession(id: id, entry: Date(timeIntervalSinceReferenceDate: start),
            exit: open ? nil : Date(timeIntervalSinceReferenceDate: start + 10), pauses: [])
    }
    private func fixture(_ run: (UserDefaults, WorkSession, Data) throws -> Void) throws {
        let name = "recovery-\(UUID())"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: name))
        defer { defaults.removePersistentDomain(forName: name) }
        let old = session()
        let bytes = try JSONEncoder().encode([old])
        defaults.set(bytes, forKey: WorkSessionStorageV2.primaryKey)
        try run(defaults, old, bytes)
    }

    func testExplicitRecoveryPreservesOriginalAndStoresReceiptWithJournal() throws {
        try fixture { defaults, old, bytes in
            let preview = try WorkSessionAccountStorageV2.recoveryPreview(account: "uid:A", defaults: defaults)
            XCTAssertTrue(WorkSessionAccountStorageV2.hasUnassignedHistory(defaults: defaults))
            try WorkSessionAccountStorageV2.recover(preview, accountProvider: { "uid:A" }, defaults: defaults)
            XCTAssertFalse(WorkSessionAccountStorageV2.hasUnassignedHistory(defaults: defaults))
            XCTAssertEqual(WorkSessionAccountStorageV2.read(account: "uid:A", defaults: defaults), .valid([old]))
            XCTAssertEqual(WorkSessionAccountStorageV2.read(account: "uid:B", defaults: defaults), .missing)
            XCTAssertEqual(defaults.data(forKey: WorkSessionStorageV2.primaryKey), bytes)
            let next = self.session(200)
            XCTAssertTrue(WorkSessionAccountStorageV2.write([old, next], account: "uid:A", defaults: defaults))
            XCTAssertEqual(WorkSessionAccountStorageV2.read(account: "uid:A", defaults: defaults), .valid([old, next]))
            XCTAssertFalse(WorkSessionAccountStorageV2.hasUnassignedHistory(defaults: defaults))
            XCTAssertThrowsError(try WorkSessionAccountStorageV2.recoveryPreview(account: "uid:B", defaults: defaults))
        }
    }

    func testGuestAndStaleAccountCannotClaim() throws {
        try fixture { defaults, _, _ in
            XCTAssertThrowsError(try WorkSessionAccountStorageV2.recoveryPreview(account: "guest", defaults: defaults))
            let preview = try WorkSessionAccountStorageV2.recoveryPreview(account: "uid:A", defaults: defaults)
            XCTAssertThrowsError(try WorkSessionAccountStorageV2.recover(preview, accountProvider: { "uid:B" }, defaults: defaults))
            XCTAssertNil(defaults.object(forKey: WorkSessionAccountStorageV2.recoveryKey))
        }
    }

    func testChangedDestinationOrSourceRequiresNewConfirmation() throws {
        try fixture { defaults, _, _ in
            let preview = try WorkSessionAccountStorageV2.recoveryPreview(account: "uid:A", defaults: defaults)
            XCTAssertTrue(WorkSessionAccountStorageV2.write([self.session(200)], account: "uid:A", defaults: defaults))
            XCTAssertThrowsError(try WorkSessionAccountStorageV2.recover(preview, accountProvider: { "uid:A" }, defaults: defaults))
            let refreshed = try WorkSessionAccountStorageV2.recoveryPreview(account: "uid:A", defaults: defaults)
            defaults.set(try JSONEncoder().encode([self.session(300)]), forKey: WorkSessionStorageV2.primaryKey)
            XCTAssertThrowsError(try WorkSessionAccountStorageV2.recover(refreshed, accountProvider: { "uid:A" }, defaults: defaults))
            XCTAssertNil(defaults.object(forKey: WorkSessionAccountStorageV2.recoveryKey))
        }
    }

    func testInterruptedReservationIsExclusiveAndReplayableWithoutDuplicates() throws {
        try fixture { defaults, old, bytes in
            let preview = try WorkSessionAccountStorageV2.recoveryPreview(account: "uid:A", defaults: defaults)
            var calls = 0
            XCTAssertThrowsError(try WorkSessionAccountStorageV2.recover(preview, accountProvider: {
                calls += 1
                return calls < 3 ? "uid:A" : "uid:B"
            }, defaults: defaults))
            XCTAssertNotNil(defaults.data(forKey: WorkSessionAccountStorageV2.recoveryKey))
            XCTAssertEqual(WorkSessionAccountStorageV2.read(account: "uid:A", defaults: defaults), .corrupt)
            XCTAssertTrue(WorkSessionAccountStorageV2.hasUnassignedHistory(defaults: defaults))
            XCTAssertThrowsError(try WorkSessionAccountStorageV2.recoveryPreview(account: "uid:B", defaults: defaults))
            let resume = try WorkSessionAccountStorageV2.recoveryPreview(account: "uid:A", defaults: defaults)
            try WorkSessionAccountStorageV2.recover(resume, accountProvider: { "uid:A" }, defaults: defaults)
            XCTAssertEqual(WorkSessionAccountStorageV2.read(account: "uid:A", defaults: defaults), .valid([old]))
            XCTAssertEqual(defaults.data(forKey: WorkSessionStorageV2.primaryKey), bytes)
        }
    }

    func testExactDuplicateDeduplicatesButDivergentIDOrOverlapOrTwoOpenSessionsBlock() throws {
        try fixture { defaults, old, _ in
            XCTAssertTrue(WorkSessionAccountStorageV2.write([old], account: "uid:A", defaults: defaults))
            let preview = try WorkSessionAccountStorageV2.recoveryPreview(account: "uid:A", defaults: defaults)
            try WorkSessionAccountStorageV2.recover(preview, accountProvider: { "uid:A" }, defaults: defaults)
            XCTAssertEqual(WorkSessionAccountStorageV2.read(account: "uid:A", defaults: defaults), .valid([old]))
        }
        try fixture { defaults, old, _ in
            XCTAssertTrue(WorkSessionAccountStorageV2.write([self.session(200, id: old.id)], account: "uid:A", defaults: defaults))
            XCTAssertThrowsError(try WorkSessionAccountStorageV2.recoveryPreview(account: "uid:A", defaults: defaults))
            XCTAssertTrue(WorkSessionAccountStorageV2.write([self.session(105)], account: "uid:A", defaults: defaults))
            XCTAssertThrowsError(try WorkSessionAccountStorageV2.recoveryPreview(account: "uid:A", defaults: defaults))
            defaults.set(try JSONEncoder().encode([self.session(100, open: true)]), forKey: WorkSessionStorageV2.primaryKey)
            XCTAssertTrue(WorkSessionAccountStorageV2.write([self.session(200, open: true)], account: "uid:A", defaults: defaults))
            XCTAssertThrowsError(try WorkSessionAccountStorageV2.recoveryPreview(account: "uid:A", defaults: defaults))
        }
    }

    func testUnqualifiedOpenPauseIsPreservedAndLaterEditsDoNotRevokeAttribution() throws {
        try fixture { defaults, _, _ in
            var old = self.session(100, open: true)
            old.pauses = [PausePeriod(id: UUID(), start: Date(timeIntervalSinceReferenceDate: 105), end: nil, paid: nil)]
            let bytes = try JSONEncoder().encode([old])
            defaults.set(bytes, forKey: WorkSessionStorageV2.primaryKey)
            let preview = try WorkSessionAccountStorageV2.recoveryPreview(account: "uid:A", defaults: defaults)
            try WorkSessionAccountStorageV2.recover(preview, accountProvider: { "uid:A" }, defaults: defaults)
            XCTAssertEqual(WorkSessionAccountStorageV2.read(account: "uid:A", defaults: defaults), .valid([old]))
            XCTAssertTrue(WorkSessionAccountStorageV2.write([], account: "uid:A", defaults: defaults))
            XCTAssertFalse(WorkSessionAccountStorageV2.hasUnassignedHistory(defaults: defaults))
            XCTAssertEqual(defaults.data(forKey: WorkSessionStorageV2.primaryKey), bytes)
        }
    }

    func testBothV1AndV2MustBeAccountedFor() throws {
        try fixture { defaults, old, _ in
            defaults.set(try JSONEncoder().encode([WorkSession]()), forKey: WorkSessionStorageV2.primaryKey)
            defaults.set(try JSONEncoder().encode([old]), forKey: WorkSessionStorageV2.legacyKey)
            let preview = try WorkSessionAccountStorageV2.recoveryPreview(account: "uid:A", defaults: defaults)
            XCTAssertEqual(preview.historicalCount, 1)
            try WorkSessionAccountStorageV2.recover(preview, accountProvider: { "uid:A" }, defaults: defaults)
            XCTAssertEqual(WorkSessionAccountStorageV2.read(account: "uid:A", defaults: defaults), .valid([old]))
        }
        try fixture { defaults, old, _ in
            defaults.set(try JSONEncoder().encode([self.session(300, id: old.id)]), forKey: WorkSessionStorageV2.legacyKey)
            XCTAssertThrowsError(try WorkSessionAccountStorageV2.recoveryPreview(account: "uid:A", defaults: defaults))
        }
    }

    func testSourceChangeAfterCompleteDoesNotBecomeReliableZero() throws {
        try fixture { defaults, _, _ in
            let preview = try WorkSessionAccountStorageV2.recoveryPreview(account: "uid:A", defaults: defaults)
            try WorkSessionAccountStorageV2.recover(preview, accountProvider: { "uid:A" }, defaults: defaults)
            defaults.set(try JSONEncoder().encode([self.session(500)]), forKey: WorkSessionStorageV2.primaryKey)
            XCTAssertTrue(WorkSessionAccountStorageV2.hasUnassignedHistory(defaults: defaults))
        }
    }
    private func diagnose(_ defaults: UserDefaults, generation: UInt64 = 1) throws -> WorkSessionAccountStorageV2.RecoveryDiagnostic {
        let consent = try WorkSessionAccountStorageV2.diagnosticConsent(account: "uid:A", generation: generation, defaults: defaults)
        return try WorkSessionAccountStorageV2.recoveryDiagnostic(consent, ownershipDeclared: true,
            contextProvider: { ("uid:A", generation) }, defaults: defaults)
    }

    func testDiagnosticDeniedPreviewStillReportsPreciseOriginalPauseWithoutMutation() throws {
        try fixture { defaults, _, _ in
            var invalid = self.session(300)
            invalid.pauses = [.init(id: UUID(), start: invalid.entry.addingTimeInterval(1), end: invalid.entry.addingTimeInterval(2), paid: nil)]
            defaults.set(try JSONEncoder().encode([self.session(500), invalid]), forKey: WorkSessionStorageV2.primaryKey)
            let before = defaults.dictionaryRepresentation() as NSDictionary
            XCTAssertThrowsError(try WorkSessionAccountStorageV2.recoveryPreview(account: "uid:A", defaults: defaults))
            for _ in 0..<2 {
                let report = try self.diagnose(defaults)
                XCTAssertEqual(report.stage, .primaryV2)
                XCTAssertEqual(report.code, "unqualifiedPause")
                XCTAssertEqual(report.references, [.init(source: .primaryV2, path: [.index(1), .field(.pauses), .index(0), .field(.paid)])])
                XCTAssertTrue(report.text.contains("étapes suivantes n’ont pas été vérifiées"))
            }
            XCTAssertEqual(defaults.dictionaryRepresentation() as NSDictionary, before)
            XCTAssertNil(defaults.object(forKey: WorkSessionAccountStorageV2.recoveryKey))
        }
    }

    func testDiagnosticHistoricalAndAccountMergeKeepOriginalUnsortedOrdinals() throws {
        try fixture { defaults, old, _ in
            defaults.set(try JSONEncoder().encode([self.session(500), old]), forKey: WorkSessionStorageV2.primaryKey)
            defaults.set(try JSONEncoder().encode([self.session(200)]), forKey: WorkSessionStorageV2.legacyKey)
            let divergent = self.session(100, id: old.id)
            var changed = divergent; changed.placeLabel = "private street"
            XCTAssertTrue(WorkSessionAccountStorageV2.write([self.session(600), changed], account: "uid:A", defaults: defaults))
            let report = try self.diagnose(defaults)
            XCTAssertEqual(report.stage, .accountMerge)
            XCTAssertEqual(report.code, "identityConflict")
            XCTAssertEqual(report.references, [.init(source: .primaryV2, path: [.index(1), .field(.id)]),
                                              .init(source: .accountV2, path: [.index(1), .field(.id)])])
            defaults.set(try JSONEncoder().encode([self.session(700), changed]), forKey: WorkSessionStorageV2.legacyKey)
            let historical = try self.diagnose(defaults)
            XCTAssertEqual(historical.stage, .historicalMerge)
            XCTAssertEqual(historical.references, [.init(source: .legacyV1, path: [.index(1), .field(.id)]),
                                                  .init(source: .primaryV2, path: [.index(1), .field(.id)])])
        }
    }

    func testDiagnosticOverlapPairsDifferentIdentitiesAndAllowsContiguousIntervals() throws {
        try fixture { defaults, _, _ in
            XCTAssertTrue(WorkSessionAccountStorageV2.write([self.session(100)], account: "uid:A", defaults: defaults))
            let report = try self.diagnose(defaults)
            XCTAssertEqual(report.stage, .accountMerge)
            XCTAssertEqual(report.code, "overlap")
            XCTAssertEqual(Set(report.references.map { $0.source.rawValue }), Set(["primaryV2", "accountV2"]))
            XCTAssertEqual(report.references.map(\.path), [[.index(0), .field(.entry)], [.index(0), .field(.entry)]])
            XCTAssertTrue(WorkSessionAccountStorageV2.write([self.session(110)], account: "uid:A", defaults: defaults))
            XCTAssertEqual(try self.diagnose(defaults).stage, .complete)
            XCTAssertNoThrow(try WorkSessionAccountStorageV2.recoveryPreview(account: "uid:A", defaults: defaults))
        }
    }

    func testDiagnosticMultipleOpenSessionsAndPauseOverlapPreserveReferences() throws {
        try fixture { defaults, _, _ in
            defaults.set(try JSONEncoder().encode([self.session(100, open: true)]), forKey: WorkSessionStorageV2.primaryKey)
            XCTAssertTrue(WorkSessionAccountStorageV2.write([self.session(200, open: true)], account: "uid:A", defaults: defaults))
            let report = try self.diagnose(defaults)
            XCTAssertEqual(report.code, "multipleOpenSessions")
            XCTAssertEqual(report.references.count, 2)
            var invalid = self.session(100)
            invalid.pauses = [.init(id: UUID(), start: invalid.entry.addingTimeInterval(4), end: invalid.entry.addingTimeInterval(7), paid: true),
                              .init(id: UUID(), start: invalid.entry.addingTimeInterval(1), end: invalid.entry.addingTimeInterval(5), paid: false)]
            defaults.set(try JSONEncoder().encode([invalid]), forKey: WorkSessionStorageV2.primaryKey)
            let pauses = try self.diagnose(defaults)
            XCTAssertEqual(pauses.code, "overlap")
            XCTAssertEqual(pauses.references.map(\.path), [[.index(0), .field(.pauses), .index(1), .field(.end)],
                                                          [.index(0), .field(.pauses), .index(0), .field(.start)]])
        }
    }

    func testDiagnosticLegacyCompatibilityUnknownKeysAndUnknownOpenPauseUnchanged() throws {
        try fixture { defaults, _, _ in
            var historical = self.session()
            historical.pauses = [.init(id: UUID(), start: historical.entry.addingTimeInterval(1), end: historical.entry.addingTimeInterval(2), paid: nil)]
            var json = try XCTUnwrap(JSONSerialization.jsonObject(with: JSONEncoder().encode([historical])) as? [[String: Any]])
            json[0]["unknown.private@example.test"] = "secret coordinates 48.1234"
            let bytes = try JSONSerialization.data(withJSONObject: json)
            defaults.set(bytes, forKey: WorkSessionStorageV2.primaryKey)
            XCTAssertEqual(try self.diagnose(defaults).code, "unqualifiedPause")
            defaults.removeObject(forKey: WorkSessionStorageV2.primaryKey)
            defaults.set(bytes, forKey: WorkSessionStorageV2.legacyKey)
            let before = defaults.dictionaryRepresentation() as NSDictionary
            XCTAssertEqual(try self.diagnose(defaults).stage, .complete)
            XCTAssertEqual(defaults.dictionaryRepresentation() as NSDictionary, before)
            guard case .valid(let migrated) = WorkSessionPersistenceV2.readLegacy(bytes) else { return XCTFail("V1 compatibility lost") }
            XCTAssertEqual(migrated[0].pauses[0].paid, false)
            var open = self.session(100, open: true)
            open.pauses = [.init(id: UUID(), start: open.entry.addingTimeInterval(1), end: nil, paid: nil)]
            let openBytes = try JSONEncoder().encode([open])
            defaults.set(openBytes, forKey: WorkSessionStorageV2.legacyKey)
            XCTAssertEqual(try self.diagnose(defaults).stage, .complete)
            XCTAssertEqual(WorkSessionPersistenceV2.readLegacy(openBytes), .valid([open]))
        }
    }

    func testDiagnosticDecodeRedactsValuesAndReportsKnownFieldOnly() throws {
        try fixture { defaults, old, _ in
            var json = try XCTUnwrap(JSONSerialization.jsonObject(with: JSONEncoder().encode([old])) as? [[String: Any]])
            let privateText = "private@example.test street 48.12345 employer-secret"
            json[0]["id"] = privateText
            json[0][privateText] = privateText
            defaults.set(try JSONSerialization.data(withJSONObject: json), forKey: WorkSessionStorageV2.primaryKey)
            let report = try self.diagnose(defaults)
            XCTAssertEqual(report.code, "invalidValue")
            XCTAssertEqual(report.references, [.init(source: .primaryV2, path: [.index(0), .field(.id)])])
            XCTAssertFalse(report.text.contains(privateText))
            XCTAssertFalse(report.text.contains(old.id.uuidString))
            defaults.set(Data(privateText.utf8), forKey: WorkSessionStorageV2.primaryKey)
            let malformed = try self.diagnose(defaults)
            XCTAssertEqual(malformed.code, "invalidJSON")
            XCTAssertEqual(malformed.references.first?.path, [])
            XCTAssertFalse(malformed.text.contains(privateText))
        }
    }

    func testDiagnosticRequiresDeclarationAndRejectsEveryChangedSnapshotInput() throws {
        try fixture { defaults, _, _ in
            let consent = try WorkSessionAccountStorageV2.diagnosticConsent(account: "uid:A", generation: 1, defaults: defaults)
            let before = defaults.dictionaryRepresentation() as NSDictionary
            XCTAssertThrowsError(try WorkSessionAccountStorageV2.recoveryDiagnostic(consent, ownershipDeclared: false,
                contextProvider: { ("uid:A", 1) }, defaults: defaults))
            XCTAssertEqual(defaults.dictionaryRepresentation() as NSDictionary, before)
            for key in [WorkSessionStorageV2.primaryKey, WorkSessionStorageV2.legacyKey,
                        WorkSessionAccountStorageV2.key(account: "uid:A")!, WorkSessionAccountStorageV2.recoveryKey] {
                let fresh = try WorkSessionAccountStorageV2.diagnosticConsent(account: "uid:A", generation: 1, defaults: defaults)
                let original = defaults.object(forKey: key)
                defaults.set("wrong type private value", forKey: key)
                XCTAssertFalse(WorkSessionAccountStorageV2.diagnosticIsCurrent(fresh, account: "uid:A", generation: 1, defaults: defaults))
                XCTAssertThrowsError(try WorkSessionAccountStorageV2.recoveryDiagnostic(fresh, ownershipDeclared: true,
                    contextProvider: { ("uid:A", 1) }, defaults: defaults))
                if let original { defaults.set(original, forKey: key) } else { defaults.removeObject(forKey: key) }
            }
        }
    }

    func testDiagnosticRechecksAccountGenerationAndSnapshotBeforePublishing() throws {
        try fixture { defaults, _, _ in
            let consent = try WorkSessionAccountStorageV2.diagnosticConsent(account: "uid:A", generation: 1, defaults: defaults)
            for context in [("uid:B", UInt64(1)), ("uid:A", UInt64(2)), ("guest", UInt64(1))] {
                XCTAssertThrowsError(try WorkSessionAccountStorageV2.recoveryDiagnostic(consent, ownershipDeclared: true,
                    contextProvider: { context }, defaults: defaults))
            }
            var calls = 0
            XCTAssertThrowsError(try WorkSessionAccountStorageV2.recoveryDiagnostic(consent, ownershipDeclared: true,
                contextProvider: { calls += 1; return ("uid:A", calls == 1 ? 1 : 2) }, defaults: defaults))
            calls = 0
            XCTAssertThrowsError(try WorkSessionAccountStorageV2.recoveryDiagnostic(consent, ownershipDeclared: true,
                contextProvider: {
                    calls += 1
                    if calls == 2 { defaults.set(Data("changed".utf8), forKey: WorkSessionStorageV2.legacyKey) }
                    return ("uid:A", 1)
                }, defaults: defaults))
            XCTAssertNil(defaults.object(forKey: WorkSessionAccountStorageV2.recoveryKey))
            XCTAssertThrowsError(try WorkSessionAccountStorageV2.diagnosticConsent(account: "guest", generation: 1, defaults: defaults))
        }
    }

    func testDiagnosticForeignReservationAndCorruptReceiptNeverExposeDetails() throws {
        try fixture { defaults, _, _ in
            let preview = try WorkSessionAccountStorageV2.recoveryPreview(account: "uid:A", defaults: defaults)
            var calls = 0
            XCTAssertThrowsError(try WorkSessionAccountStorageV2.recover(preview, accountProvider: {
                calls += 1; return calls < 3 ? "uid:A" : "uid:B"
            }, defaults: defaults))
            defaults.set(Data("malformed private journal".utf8), forKey: WorkSessionStorageV2.primaryKey)
            let consent = try WorkSessionAccountStorageV2.diagnosticConsent(account: "uid:B", generation: 1, defaults: defaults)
            let before = defaults.dictionaryRepresentation() as NSDictionary
            XCTAssertThrowsError(try WorkSessionAccountStorageV2.recoveryDiagnostic(consent, ownershipDeclared: true,
                contextProvider: { ("uid:B", 1) }, defaults: defaults)) { error in
                guard case WorkSessionAccountStorageV2.RecoveryError.reserved = error else { return XCTFail("Foreign reservation must block first") }
            }
            XCTAssertEqual(defaults.dictionaryRepresentation() as NSDictionary, before)
            defaults.set(Data("private corrupt receipt".utf8), forKey: WorkSessionAccountStorageV2.recoveryKey)
            XCTAssertThrowsError(try self.diagnose(defaults))
        }
    }

    func testDiagnosticValidExactDuplicateAndUnchangedEmptyJournalDoNotWrite() throws {
        try fixture { defaults, old, _ in
            XCTAssertTrue(WorkSessionAccountStorageV2.write([old], account: "uid:A", defaults: defaults))
            for historical in [[old], []] {
                defaults.set(try JSONEncoder().encode(historical), forKey: WorkSessionStorageV2.primaryKey)
                let before = defaults.dictionaryRepresentation() as NSDictionary
                XCTAssertEqual(try self.diagnose(defaults).stage, .complete)
                XCTAssertEqual(defaults.dictionaryRepresentation() as NSDictionary, before)
                XCTAssertEqual(WorkSessionAccountStorageV2.read(account: "uid:A", defaults: defaults), .valid([old]))
            }
        }
    }

    private final class CountingDefaults: UserDefaults {
        var writeCount = 0
        override func set(_ value: Any?, forKey defaultName: String) { writeCount += 1; super.set(value, forKey: defaultName) }
        override func removeObject(forKey defaultName: String) { writeCount += 1; super.removeObject(forKey: defaultName) }
    }

    func testDiagnosticMakesZeroPreferenceWritesForAcceptedRejectedAndUndeclaredConsultations() throws {
        let suite = "consultation-write-count-\(UUID())"
        let defaults = try XCTUnwrap(CountingDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        for bytes in [try JSONEncoder().encode([session()]), Data("malformed".utf8)] {
            defaults.set(bytes, forKey: WorkSessionStorageV2.primaryKey)
            defaults.writeCount = 0
            let consent = try WorkSessionAccountStorageV2.diagnosticConsent(account: "uid:A", generation: 1, defaults: defaults)
            XCTAssertThrowsError(try WorkSessionAccountStorageV2.recoveryDiagnostic(consent, ownershipDeclared: false,
                contextProvider: { ("uid:A", 1) }, defaults: defaults))
            for _ in 0..<2 {
                _ = try WorkSessionAccountStorageV2.recoveryDiagnostic(consent, ownershipDeclared: true,
                    contextProvider: { ("uid:A", 1) }, defaults: defaults)
            }
            XCTAssertEqual(defaults.writeCount, 0)
        }
    }

}
