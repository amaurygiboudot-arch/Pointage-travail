import Foundation
import XCTest
@testable import RuntimeV2Contract

final class TravelJourneyCodecV2Tests: XCTestCase {
    private let account = "uid:one"
    private let startedAt = Date(timeIntervalSince1970: 1_800_000_000)

    func testRoundTripPreservesUnknownLocationAndAccountOwnership() throws {
        let fix = TravelFixV2(latitude: 46.78, longitude: -1.75,
                              accuracyMeters: 10, observedAt: startedAt)
        let trip = TravelJourneyV2(id: "trip-1", startedAt: startedAt,
                                   endedAt: startedAt.addingTimeInterval(3600),
                                   employerId: "company", departure: fix, arrival: nil)
        let bytes = try XCTUnwrap(TravelJourneyCodecV2.encode(account: account, trips: [trip]))
        XCTAssertEqual(TravelJourneyCodecV2.decode(account: account, data: bytes), [trip])
        XCTAssertNil(TravelJourneyCodecV2.decode(account: "uid:other", data: bytes))
        XCTAssertNil(TravelJourneyCodecV2.decode(account: account, data: Data("broken".utf8)))
    }

    func testRejectsInvalidAndDuplicateTrips() {
        let open = TravelJourneyV2(id: "one", startedAt: startedAt, endedAt: nil,
                                   employerId: nil, departure: nil, arrival: nil)
        XCTAssertNil(TravelJourneyCodecV2.encode(account: account, trips: [open, open]))
        let second = TravelJourneyV2(id: "two", startedAt: startedAt, endedAt: nil,
                                     employerId: nil, departure: nil, arrival: nil)
        XCTAssertNil(TravelJourneyCodecV2.encode(account: account, trips: [open, second]))
        var wrongTime = open
        wrongTime.endedAt = startedAt.addingTimeInterval(-5)
        XCTAssertNil(TravelJourneyCodecV2.encode(account: account, trips: [wrongTime]))
    }

    func testRejectsImpossiblePositionEvidence() {
        let fix = TravelFixV2(latitude: 120, longitude: 0, accuracyMeters: 1,
                              observedAt: startedAt)
        let trip = TravelJourneyV2(id: "one", startedAt: startedAt, endedAt: nil,
                                   employerId: nil, departure: fix, arrival: nil)
        XCTAssertNil(TravelJourneyCodecV2.encode(account: account, trips: [trip]))
    }
}
