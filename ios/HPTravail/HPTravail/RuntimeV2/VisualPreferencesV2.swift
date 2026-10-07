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

    public init() {}

    public var valid: Bool {
        version == 1 && ["system", "light", "dark"].contains(appearance)
            && ["signature", "blue"].contains(accent)
            && (0...5).contains(textSteps)
            && ["standard", "night", "economy"].contains(context)
    }

    public static func decode(_ data: Data) throws -> Self {
        let value = try JSONDecoder().decode(Self.self, from: data)
        guard value.valid else { throw ValidationError.unsupported }
        return value
    }

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
