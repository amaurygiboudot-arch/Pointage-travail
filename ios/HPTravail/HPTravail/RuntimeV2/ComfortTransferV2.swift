import Foundation
import CoreFoundation

/// Explicit cross-platform subset. Never imports identity, business data or native UI choices.
public struct ComfortTransferV2: Codable, Equatable {
    public let format: String
    public let version: Int
    public let highContrast: Bool
    public let reduceMotion: Bool
    public let readerScale: Double
    public static let maximumBytes = 4_096

    private enum Keys: String, CodingKey, CaseIterable {
        case format, version, highContrast, reduceMotion, readerScale
    }
    private struct AnyKey: CodingKey {
        let stringValue: String
        var intValue: Int? { nil }
        init?(stringValue: String) { self.stringValue = stringValue }
        init?(intValue: Int) { return nil }
    }
    public enum TransferError: Error { case invalid }

    public init(profile: VisualPreferencesV2) throws {
        guard profile.readerScale.isFinite, (1...4).contains(profile.readerScale) else { throw TransferError.invalid }
        format = "agkgmg.comfort"
        version = 1
        highContrast = profile.highContrast
        reduceMotion = profile.reduceMotion
        readerScale = profile.readerScale
    }

    public init(from decoder: Decoder) throws {
        let all = try decoder.container(keyedBy: AnyKey.self)
        guard Set(all.allKeys.map(\.stringValue)) == Set(Keys.allCases.map(\.rawValue)) else { throw TransferError.invalid }
        let values = try decoder.container(keyedBy: Keys.self)
        format = try values.decode(String.self, forKey: .format)
        version = try values.decode(Int.self, forKey: .version)
        highContrast = try values.decode(Bool.self, forKey: .highContrast)
        reduceMotion = try values.decode(Bool.self, forKey: .reduceMotion)
        readerScale = try values.decode(Double.self, forKey: .readerScale)
        guard format == "agkgmg.comfort", version == 1,
              readerScale.isFinite, (1...4).contains(readerScale) else { throw TransferError.invalid }
    }

    public func encode(to encoder: Encoder) throws {
        var values = encoder.container(keyedBy: Keys.self)
        try values.encode(format, forKey: .format)
        try values.encode(version, forKey: .version)
        try values.encode(highContrast, forKey: .highContrast)
        try values.encode(reduceMotion, forKey: .reduceMotion)
        try values.encode(readerScale, forKey: .readerScale)
    }

    public static func decode(_ text: String) throws -> Self {
        guard text.utf8.count <= maximumBytes else { throw TransferError.invalid }
        let data = Data(text.utf8)
        guard let object = try JSONSerialization.jsonObject(with: data) as? [String: Any],
              let number = object["version"] as? NSNumber,
              CFGetTypeID(number) != CFBooleanGetTypeID(),
              number.doubleValue == 1 else { throw TransferError.invalid }
        return try JSONDecoder().decode(Self.self, from: data)
    }

    public func encodedText() throws -> String {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        let data = try encoder.encode(self)
        guard data.count <= Self.maximumBytes, let text = String(data: data, encoding: .utf8) else { throw TransferError.invalid }
        return text
    }

    /// Call only at confirmation, using the then-current profile. Native values stay intact.
    public func applying(to current: VisualPreferencesV2) -> VisualPreferencesV2 {
        var next = current
        next.highContrast = highContrast
        next.reduceMotion = reduceMotion
        next.readerScale = readerScale
        return next
    }
}
