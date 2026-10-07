import Foundation
import CoreFoundation

/// Explicit cross-platform subset. Never imports identity, business data or native UI choices.
public struct ComfortTransferV2: Codable, Equatable {
    public let format: String
    public let version: Int
    public let highContrast: Bool
    public let reduceMotion: Bool
    public let readerScale: Double
    /// Absent only in legacy v1 transfers; applying those preserves the local schedule.
    public let nightScheduleEnabled: Bool?
    public let nightStartMinute: Int?
    public let nightEndMinute: Int?
    public static let maximumBytes = 4_096

    private enum Keys: String, CodingKey, CaseIterable {
        case format, version, highContrast, reduceMotion, readerScale
        case nightScheduleEnabled, nightStartMinute, nightEndMinute
    }
    private struct AnyKey: CodingKey {
        let stringValue: String
        var intValue: Int? { nil }
        init?(stringValue: String) { self.stringValue = stringValue }
        init?(intValue: Int) { return nil }
    }
    public enum TransferError: Error { case invalid }

    public init(profile: VisualPreferencesV2) throws {
        guard profile.readerScale.isFinite, (1...4).contains(profile.readerScale),
              (0...1439).contains(profile.nightStartMinute), (0...1439).contains(profile.nightEndMinute),
              profile.nightStartMinute != profile.nightEndMinute else { throw TransferError.invalid }
        format = "agkgmg.comfort"
        version = 2
        highContrast = profile.highContrast
        reduceMotion = profile.reduceMotion
        readerScale = profile.readerScale
        nightScheduleEnabled = profile.nightScheduleEnabled
        nightStartMinute = profile.nightStartMinute
        nightEndMinute = profile.nightEndMinute
    }

    public init(from decoder: Decoder) throws {
        let all = try decoder.container(keyedBy: AnyKey.self)
        let values = try decoder.container(keyedBy: Keys.self)
        format = try values.decode(String.self, forKey: .format)
        version = try values.decode(Int.self, forKey: .version)
        let legacyKeys: Set<String> = ["format", "version", "highContrast", "reduceMotion", "readerScale"]
        let expectedKeys = version == 1 ? legacyKeys : Set(Keys.allCases.map(\.rawValue))
        guard [1, 2].contains(version), Set(all.allKeys.map(\.stringValue)) == expectedKeys else {
            throw TransferError.invalid
        }
        highContrast = try values.decode(Bool.self, forKey: .highContrast)
        reduceMotion = try values.decode(Bool.self, forKey: .reduceMotion)
        readerScale = try values.decode(Double.self, forKey: .readerScale)
        if version == 2 {
            nightScheduleEnabled = try values.decode(Bool.self, forKey: .nightScheduleEnabled)
            nightStartMinute = try values.decode(Int.self, forKey: .nightStartMinute)
            nightEndMinute = try values.decode(Int.self, forKey: .nightEndMinute)
            guard let start = nightStartMinute, let end = nightEndMinute,
                  (0...1439).contains(start), (0...1439).contains(end), start != end else {
                throw TransferError.invalid
            }
        } else {
            nightScheduleEnabled = nil
            nightStartMinute = nil
            nightEndMinute = nil
        }
        guard format == "agkgmg.comfort",
              readerScale.isFinite, (1...4).contains(readerScale) else { throw TransferError.invalid }
    }

    public func encode(to encoder: Encoder) throws {
        var values = encoder.container(keyedBy: Keys.self)
        try values.encode(format, forKey: .format)
        try values.encode(version, forKey: .version)
        try values.encode(highContrast, forKey: .highContrast)
        try values.encode(reduceMotion, forKey: .reduceMotion)
        try values.encode(readerScale, forKey: .readerScale)
        if version == 2 {
            try values.encode(nightScheduleEnabled, forKey: .nightScheduleEnabled)
            try values.encode(nightStartMinute, forKey: .nightStartMinute)
            try values.encode(nightEndMinute, forKey: .nightEndMinute)
        }
    }

    public static func decode(_ text: String) throws -> Self {
        guard text.utf8.count <= maximumBytes else { throw TransferError.invalid }
        let data = Data(text.utf8)
        guard let object = try JSONSerialization.jsonObject(with: data) as? [String: Any],
              let number = object["version"] as? NSNumber,
              CFGetTypeID(number) != CFBooleanGetTypeID(),
              [1.0, 2.0].contains(number.doubleValue) else { throw TransferError.invalid }
        return try JSONDecoder().decode(Self.self, from: data)
    }

    public func encodedText() throws -> String {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        let data = try encoder.encode(self)
        guard data.count <= Self.maximumBytes, let text = String(data: data, encoding: .utf8) else { throw TransferError.invalid }
        return text
    }

    /// Wall-clock times are interpreted in the destination device's local time zone.
    public var nightSchedulePreview: String {
        guard let enabled = nightScheduleEnabled, let start = nightStartMinute, let end = nightEndMinute else {
            return "Ancienne sauvegarde : la programmation Nuit locale est conservée."
        }
        let startTime = String(format: "%02d:%02d", start / 60, start % 60)
        let endTime = String(format: "%02d:%02d", end / 60, end % 60)
        return "Programmation Nuit : \(enabled ? "activée" : "désactivée"), de \(startTime) à \(endTime). Horaires interprétés dans le fuseau horaire local de cet appareil."
    }

    /// Call only at confirmation, using the then-current profile. Native values stay intact.
    public func applying(to current: VisualPreferencesV2) -> VisualPreferencesV2 {
        var next = current
        next.highContrast = highContrast
        next.reduceMotion = reduceMotion
        next.readerScale = readerScale
        if let enabled = nightScheduleEnabled, let start = nightStartMinute, let end = nightEndMinute {
            next.nightScheduleEnabled = enabled
            next.nightStartMinute = start
            next.nightEndMinute = end
        }
        return next
    }
}
