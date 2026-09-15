import Foundation

struct PaidPauseFactV2: Equatable {
    let start: Date
    let end: Date?
    let paid: Bool?
}

struct PaidTimeAssessmentV2: Equatable {
    let paidDuration: TimeInterval
    let unpaidPauseDuration: TimeInterval
    let paidPauseDuration: TimeInterval
    let unresolvedPauseCount: Int

    var reliable: Bool { unresolvedPauseCount == 0 }
}

enum PaidTimePolicyV2 {
    static func assess(
        sessionStart: Date,
        sessionEnd: Date?,
        pauses: [PaidPauseFactV2],
        until now: Date = Date()
    ) -> PaidTimeAssessmentV2 {
        let effectiveEnd = sessionEnd ?? now
        guard effectiveEnd > sessionStart else {
            return PaidTimeAssessmentV2(
                paidDuration: 0,
                unpaidPauseDuration: 0,
                paidPauseDuration: 0,
                unresolvedPauseCount: 1
            )
        }

        var unresolved = 0
        var unpaidIntervals: [(Date, Date)] = []
        var paidIntervals: [(Date, Date)] = []

        for pause in pauses {
            let pauseEnd = pause.end ?? now
            let start = max(pause.start, sessionStart)
            let end = min(pauseEnd, effectiveEnd)

            guard end > start else { continue }
            guard let paid = pause.paid else {
                unresolved += 1
                continue
            }
            if paid {
                paidIntervals.append((start, end))
            } else {
                unpaidIntervals.append((start, end))
            }
        }

        let mergedUnpaid = merge(unpaidIntervals)
        let mergedPaid = merge(paidIntervals)
        let unpaid = duration(mergedUnpaid)
        let paidPause = max(0, duration(mergedPaid) - overlapDuration(mergedPaid, mergedUnpaid))
        let span = effectiveEnd.timeIntervalSince(sessionStart)

        return PaidTimeAssessmentV2(
            paidDuration: max(0, span - unpaid),
            unpaidPauseDuration: unpaid,
            paidPauseDuration: min(paidPause, max(0, span - unpaid)),
            unresolvedPauseCount: unresolved
        )
    }

    private static func merge(_ intervals: [(Date, Date)]) -> [(Date, Date)] {
        let sorted = intervals
            .filter { $0.1 > $0.0 }
            .sorted { $0.0 < $1.0 }
        guard var current = sorted.first else { return [] }

        var result: [(Date, Date)] = []
        for interval in sorted.dropFirst() {
            if interval.0 <= current.1 {
                current.1 = max(current.1, interval.1)
            } else {
                result.append(current)
                current = interval
            }
        }
        result.append(current)
        return result
    }

    private static func duration(_ intervals: [(Date, Date)]) -> TimeInterval {
        intervals.reduce(0) { total, interval in
            total + max(0, interval.1.timeIntervalSince(interval.0))
        }
    }

    private static func overlapDuration(_ a: [(Date, Date)], _ b: [(Date, Date)]) -> TimeInterval {
        var total: TimeInterval = 0
        var i = 0
        var j = 0
        while i < a.count && j < b.count {
            let start = max(a[i].0, b[j].0)
            let end = min(a[i].1, b[j].1)
            if end > start {
                total += end.timeIntervalSince(start)
            }
            if a[i].1 <= b[j].1 {
                i += 1
            } else {
                j += 1
            }
        }
        return total
    }
}
