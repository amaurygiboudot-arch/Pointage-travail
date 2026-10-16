import XCTest
@testable import RuntimeV2Contract

final class DailyDiagnosticV2Tests: XCTestCase {
    private var root: URL!
    private let now = Date(timeIntervalSince1970: 1_800_000_000)
    override func setUp() {
        root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    }
    override func tearDown() { try? FileManager.default.removeItem(at: root) }

    func testIncompleteHistoryDoesNotHideCurrentFactsOrCertifyPayroll() throws {
        let session = WorkSession(id: UUID(), entry: now.addingTimeInterval(-60), exit: nil, pauses: [])
        for history in [WorkSessionAccountStorageV2.DiagnosticHistory.unassigned, .corrupt, .conflict, .recoveryIncomplete, .changed] {
            let report = try DailyDiagnosticV2.export(account: "a", day: now, root: root, now: now,
                sessions: [session], history: history, currentSourceIssue: DailyDiagnosticV2.SourceIssue.none)
            XCTAssertTrue(report.contains("historique : \(history.rawValue)"))
            XCTAssertTrue(report.contains("Historique complet fiable : false"))
            XCTAssertTrue(report.contains("sortie=ouverte/inconnue"))
            XCTAssertFalse(report.contains(session.id.uuidString))
        }
        let unavailable = try DailyDiagnosticV2.export(account: "a", day: now, root: root, now: now,
            history: .corrupt, currentSourceIssue: .corrupt)
        XCTAssertTrue(unavailable.contains("Session courante à l'export : inconnue"))
        XCTAssertFalse(unavailable.contains("aucune dans le journal lisible"))
    }

    func testLegacyEventsKeepUnknownOriginAndNewEventsRetainSeparateTimesAndReasons() throws {
        let legacy = Data("{\"recordedAt\":0,\"kind\":\"clockOutCommitted\"}".utf8)
        let decoded = try JSONDecoder().decode(DailyDiagnosticV2.Event.self, from: legacy)
        XCTAssertNil(decoded.origin)
        XCTAssertNil(decoded.rejection)
        try DailyDiagnosticV2.setEnabled(true, account: "a", root: root, now: now)
        let observed = now.addingTimeInterval(-60)
        try DailyDiagnosticV2.append(.clockOutCommitted, account: "a", occurredAt: observed,
            origin: .gpsConfirmation, root: root, now: now)
        try DailyDiagnosticV2.append(.clockInRejected, account: "a", occurredAt: observed,
            rejection: .sessionAlreadyOpen, root: root, now: now)
        let events = try DailyDiagnosticV2.read(account: "a", root: root, now: now).events
        XCTAssertEqual(events[1].occurredAt, observed)
        XCTAssertEqual(events[1].recordedAt, now)
        XCTAssertEqual(events[1].origin, .gpsConfirmation)
        let report = try DailyDiagnosticV2.export(account: "a", day: now, root: root, now: now)
        XCTAssertTrue(report.contains("origine=GPS_CONFIRMATION"))
        XCTAssertTrue(report.contains("refus=sessionAlreadyOpen"))
    }

    func testOptInIsolationAndErasePreserveOtherAccount() throws {
        try DailyDiagnosticV2.append(.clockInCommitted, account: "a", root: root, now: now)
        XCTAssertTrue(try DailyDiagnosticV2.read(account: "a", root: root, now: now).events.isEmpty)
        try DailyDiagnosticV2.setEnabled(true, account: "a", root: root, now: now)
        try DailyDiagnosticV2.append(.clockInCommitted, account: "a", occurredAt: now, root: root, now: now)
        XCTAssertTrue(try DailyDiagnosticV2.read(account: "b", root: root, now: now).events.isEmpty)
        try DailyDiagnosticV2.setEnabled(true, account: "b", root: root, now: now)
        try DailyDiagnosticV2.erase(account: "a", root: root)
        XCTAssertFalse(try DailyDiagnosticV2.read(account: "a", root: root, now: now).enabled)
        XCTAssertTrue(try DailyDiagnosticV2.read(account: "b", root: root, now: now).enabled)
    }

    func testCanonicalClosureEvidenceExportsWhenDiagnosticsDisabledOrErased() throws {
        let observed = now.addingTimeInterval(-60)
        var session = WorkSession(id: UUID(), entry: now.addingTimeInterval(-600), exit: observed, pauses: [])
        session.closureOrigin = DailyDiagnosticV2.Origin.widget.rawValue
        session.departureObservedAt = observed
        let formatter = ISO8601DateFormatter()
        formatter.timeZone = Calendar.current.timeZone
        for eraseFirst in [false, true] {
            if eraseFirst {
                try DailyDiagnosticV2.setEnabled(true, account: "a", root: root, now: now)
                try DailyDiagnosticV2.erase(account: "a", root: root)
            }
            let report = try DailyDiagnosticV2.export(account: "a", day: now, root: root, now: now, sessions: [session])
            XCTAssertTrue(report.contains("origine=WIDGET"))
            XCTAssertTrue(report.contains("départ observé=\(formatter.string(from: observed))"))
            XCTAssertTrue(try DailyDiagnosticV2.read(account: "a", root: root, now: now).events.isEmpty)
        }
        for origin: String? in [nil, "private-free-text"] {
            session.closureOrigin = origin
            session.departureObservedAt = nil
            let report = try DailyDiagnosticV2.export(account: "a", day: now, root: root, now: now, sessions: [session])
            XCTAssertTrue(report.contains("origine=UNKNOWN, départ observé=inconnue"))
            XCTAssertFalse(report.contains("private-free-text"))
        }
    }

    func testDisabledRecordingAndRetentionAreExplicit() throws {
        try DailyDiagnosticV2.setEnabled(true, account: "a", root: root, now: now)
        try DailyDiagnosticV2.setEnabled(false, account: "a", root: root, now: now)
        try DailyDiagnosticV2.append(.gpsFailure, account: "a", root: root, now: now)
        XCTAssertEqual(try DailyDiagnosticV2.read(account: "a", root: root, now: now).events.count, 2)
        let later = now.addingTimeInterval(DailyDiagnosticV2.retention + 1)
        let journal = try DailyDiagnosticV2.read(account: "a", root: root, now: later)
        XCTAssertTrue(journal.events.isEmpty)
        XCTAssertTrue(journal.truncated)
    }

    func testSelectedDayIncludesLateWritesWithoutInventingEventTime() throws {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(secondsFromGMT: 0)!
        let yesterday = now.addingTimeInterval(-86400)
        try DailyDiagnosticV2.setEnabled(true, account: "private-user-id", root: root, now: now)
        try DailyDiagnosticV2.append(.clockOutCommitted, account: "private-user-id", occurredAt: yesterday, root: root, now: now)
        try DailyDiagnosticV2.append(.gpsRegionCallback, account: "private-user-id", detail: 2, root: root, now: now)
        let old = try DailyDiagnosticV2.export(account: "private-user-id", day: yesterday, calendar: calendar, root: root, now: now)
        XCTAssertTrue(old.contains("clockOutCommitted"))
        XCTAssertFalse(old.contains("| gpsRegionCallback |"))
        XCTAssertFalse(old.contains("private-user-id"))
        let current = try DailyDiagnosticV2.export(account: "private-user-id", day: now, calendar: calendar, root: root, now: now)
        XCTAssertTrue(current.contains("gpsRegionCallback | heure effective=inconnue"))
        XCTAssertTrue(current.contains("À demander à l'utilisateur"))
    }
    func testSnapshotIncludesOvernightSessionWithoutLeakingLabelsOrIDs() throws {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(secondsFromGMT: 0)!
        let midnight = calendar.startOfDay(for: now)
        let id = UUID()
        let zone = UUID()
        let session = WorkSession(id: id, entry: midnight.addingTimeInterval(-3600),
                                  exit: midnight.addingTimeInterval(3600), pauses: [],
                                  employerId: "private-employer", placeLabel: "private-address")
        try DailyDiagnosticV2.setEnabled(true, account: "a", root: root, now: now)
        try DailyDiagnosticV2.append(.gpsRegionCallback, account: "a", zoneId: zone, sessionId: id, root: root, now: now)
        let report = try DailyDiagnosticV2.export(account: "a", day: now, calendar: calendar,
                                                 root: root, now: now, sessions: [session])
        XCTAssertTrue(report.contains("INSTANTANÉ"))
        XCTAssertTrue(report.contains(" : entrée="))
        for secret in [id.uuidString, zone.uuidString, "private-employer", "private-address"] {
            XCTAssertFalse(report.contains(secret))
        }
    }

    func testBoundedReadAndCrossAccountExpiration() throws {
        try DailyDiagnosticV2.setEnabled(true, account: "a", root: root, now: now)
        let path = try XCTUnwrap(FileManager.default.contentsOfDirectory(at: root, includingPropertiesForKeys: nil).first)
        let event = DailyDiagnosticV2.Event(recordedAt: now, occurredAt: nil, kind: .gpsFailure, detail: nil)
        let oversized = DailyDiagnosticV2.Journal(enabled: true,
            events: Array(repeating: event, count: DailyDiagnosticV2.maximumEvents + 1), truncated: false)
        try JSONEncoder().encode(oversized).write(to: path)
        let bounded = try DailyDiagnosticV2.read(account: "a", root: root, now: now)
        XCTAssertEqual(bounded.events.count, DailyDiagnosticV2.maximumEvents)
        XCTAssertTrue(bounded.truncated)
        // Accessing another account also removes expired evidence of an inactive account.
        _ = try DailyDiagnosticV2.read(account: "b", root: root, now: now.addingTimeInterval(DailyDiagnosticV2.retention + 1))
        let purged = try JSONDecoder().decode(DailyDiagnosticV2.Journal.self, from: Data(contentsOf: path))
        XCTAssertTrue(purged.events.isEmpty)
        XCTAssertTrue(purged.enabled)
    }

}
