import Foundation

/// Presentation only: never interpreted as a payroll or location permission.
public struct VisualPreferencesV2: Codable, Equatable {
    public var version = 1
    public var appearance = "system"
    public var accent = "signature"
    public var textSteps = 0
    public var highContrast = false
    public var reduceMotion = false
    public var opaqueSurfaces = false
    public var context = "standard"
    public var systemSpelling = false
    public var readerScale = 1.5

    public init() {}

    private enum CodingKeys: String, CodingKey {
        case version, appearance, accent, textSteps, highContrast, reduceMotion
        case opaqueSurfaces, context, systemSpelling, readerScale
    }

    public init(from decoder: Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        version = try values.decode(Int.self, forKey: .version)
        appearance = try values.decode(String.self, forKey: .appearance)
        accent = try values.decode(String.self, forKey: .accent)
        textSteps = try values.decode(Int.self, forKey: .textSteps)
        highContrast = try values.decode(Bool.self, forKey: .highContrast)
        reduceMotion = try values.decode(Bool.self, forKey: .reduceMotion)
        opaqueSurfaces = try values.decode(Bool.self, forKey: .opaqueSurfaces)
        context = try values.decode(String.self, forKey: .context)
        systemSpelling = try values.decode(Bool.self, forKey: .systemSpelling)
        // Existing v1 exports remain valid; a present malformed value is never defaulted.
        readerScale = values.contains(.readerScale)
            ? try values.decode(Double.self, forKey: .readerScale) : 1.5
    }

    public var valid: Bool {
        version == 1 && ["system", "light", "dark"].contains(appearance)
            && ["signature", "blue"].contains(accent)
            && (0...5).contains(textSteps)
            && readerScale.isFinite && (1...4).contains(readerScale)
            && ["standard", "night", "economy"].contains(context)
    }

    public static func decode(_ data: Data) throws -> Self {
        guard data.count <= maximumImportBytes else { throw ValidationError.unsupported }
        let value = try JSONDecoder().decode(Self.self, from: data)
        guard value.valid else { throw ValidationError.unsupported }
        return value
    }

    /// Bounded actual I/O rejects oversized and malformed imports before any preference write.
    public static func readImport(from url: URL) throws -> Self {
        let file = try FileHandle(forReadingFrom: url)
        defer { try? file.close() }
        var data = Data()
        while data.count <= maximumImportBytes {
            let chunk = try file.read(upToCount: maximumImportBytes + 1 - data.count) ?? Data()
            if chunk.isEmpty { return try decode(data) }
            data.append(chunk)
        }
        throw ValidationError.unsupported
    }

    public static let maximumImportBytes = 16_384

    public enum ValidationError: Error { case unsupported }
    public var effectiveDarkMode: Bool? {
        if context == "night" { return true }
        switch appearance {
        case "light": return false
        case "dark": return true
        default: return nil
        }
    }
    public var effectiveReducedMotion: Bool { reduceMotion || context == "economy" }

    /// Never reduce a larger system accessibility size; clamp instead of wrapping.
    public func textSizeIndex(systemIndex: Int, maximumIndex: Int) -> Int {
        min(maximumIndex, max(0, systemIndex) + max(0, textSteps))
    }
}

public enum VisualPreferencesRepositoryV2 {
    public static func key(accountID: String?) -> String {
        let scope = accountID.map { "account:" + $0 } ?? "guest"
        return "agkgmg.personalization.visual.v1." + Data(scope.utf8).base64EncodedString()
    }

    public static func read(accountID: String?, defaults: UserDefaults = .standard) throws -> VisualPreferencesV2 {
        let storageKey = key(accountID: accountID)
        if let data = defaults.data(forKey: storageKey) { return try VisualPreferencesV2.decode(data) }
        var value = VisualPreferencesV2()
        // Legacy device theme belongs to the visitor only; do not copy between accounts.
        if accountID == nil, defaults.string(forKey: "hp_theme") == "blue" { value.accent = "blue" }
        try write(value, accountID: accountID, defaults: defaults)
        if accountID == nil { defaults.removeObject(forKey: "hp_theme") }
        return value
    }

    public static func write(_ value: VisualPreferencesV2, accountID: String?, defaults: UserDefaults = .standard) throws {
        guard value.valid else { throw VisualPreferencesV2.ValidationError.unsupported }
        defaults.set(try JSONEncoder().encode(value), forKey: key(accountID: accountID))
    }
}

/// Reflow-based reading zoom, independent from source text and payroll values.
public enum ReaderZoomPolicyV2 {
    public static func clamped(_ value: Double) -> Double {
        value.isFinite ? min(4, max(1, value)) : 1
    }
    public static func pinched(base: Double, magnification: Double) -> Double {
        guard magnification.isFinite, magnification > 0 else { return clamped(base) }
        let initial = clamped(base)
        if magnification >= 4 / initial { return 4 }
        return clamped(initial * magnification)
    }
}
