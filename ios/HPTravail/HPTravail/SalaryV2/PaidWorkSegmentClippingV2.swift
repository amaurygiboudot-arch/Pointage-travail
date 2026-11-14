import Foundation

/// Preserve qualification when a session is divided by a calendar or contract boundary.
/// An explicit but incomplete segment list must never become the legacy empty-list fallback.
enum PaidWorkSegmentClippingV2 {
    static func clip(
        _ segments: [PaidWorkSegmentFactV2],
        sessionStart: Date,
        sessionEnd: Date?,
        rangeStart: Date,
        rangeEnd: Date
    ) -> [PaidWorkSegmentFactV2] {
        guard !segments.isEmpty else { return [] }
        let unresolved = [PaidWorkSegmentFactV2(
            start: rangeStart, end: rangeEnd, kind: .toConfirm, confirmed: false
        )]
        guard sessionStart.timeIntervalSince1970.isFinite,
              let sessionEnd, sessionEnd.timeIntervalSince1970.isFinite,
              sessionEnd > sessionStart,
              rangeStart.timeIntervalSince1970.isFinite,
              rangeEnd.timeIntervalSince1970.isFinite,
              rangeStart >= sessionStart, rangeEnd <= sessionEnd, rangeEnd > rangeStart else {
            return unresolved
        }

        let ordered = segments.sorted { $0.start < $1.start }
        var cursor = sessionStart
        var result: [PaidWorkSegmentFactV2] = []
        for segment in ordered {
            guard segment.start.timeIntervalSince1970.isFinite, segment.start == cursor,
                  let end = segment.end, end.timeIntervalSince1970.isFinite,
                  end > segment.start, end <= sessionEnd else { return unresolved }
            let clippedStart = max(segment.start, rangeStart)
            let clippedEnd = min(end, rangeEnd)
            if clippedEnd > clippedStart {
                result.append(PaidWorkSegmentFactV2(
                    start: clippedStart, end: clippedEnd,
                    kind: segment.kind, confirmed: segment.confirmed
                ))
            }
            cursor = end
        }
        guard cursor == sessionEnd, !result.isEmpty else { return unresolved }
        return result
    }
}
