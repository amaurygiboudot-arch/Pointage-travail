import Foundation

struct SalaryPayrollCoveragePeriodV2: Equatable {
    let startEpochDay: Int64
    let endEpochDay: Int64

    static func forMonth(_ period: YearMonthV2) -> SalaryPayrollCoveragePeriodV2? {
        var utc = Calendar(identifier: .gregorian)
        utc.timeZone = TimeZone(secondsFromGMT: 0)!
        guard let first = utc.date(from: DateComponents(year: period.year, month: period.month, day: 1)),
              let range = utc.range(of: .day, in: .month, for: first),
              let last = utc.date(from: DateComponents(year: period.year, month: period.month, day: range.count))
        else { return nil }

        let firstWeekday = utc.component(.weekday, from: first)
        let daysBackToMonday = (firstWeekday + 5) % 7
        let lastWeekday = utc.component(.weekday, from: last)
        let daysForwardToSunday = (1 - lastWeekday + 7) % 7

        guard let start = utc.date(byAdding: .day, value: -daysBackToMonday, to: first),
              let end = utc.date(byAdding: .day, value: daysForwardToSunday, to: last) else { return nil }

        return .init(
            startEpochDay: Int64(floor(start.timeIntervalSince1970 / 86400)),
            endEpochDay: Int64(floor(end.timeIntervalSince1970 / 86400))
        )
    }

    func isClosed(at now: Date, timeZoneId: String) -> Bool {
        guard let timeZone = TimeZone(identifier: timeZoneId), endEpochDay < Int64.max else { return false }
        var utc = Calendar(identifier: .gregorian)
        utc.timeZone = TimeZone(secondsFromGMT: 0)!
        let endDateUtc = Date(timeIntervalSince1970: Double(endEpochDay + 1) * 86400)
        let parts = utc.dateComponents([.year, .month, .day], from: endDateUtc)
        var local = Calendar(identifier: .gregorian)
        local.timeZone = timeZone
        guard let endExclusive = local.date(from: DateComponents(
            year: parts.year, month: parts.month, day: parts.day, hour: 0, minute: 0, second: 0
        )) else { return false }
        return now >= endExclusive
    }
}
