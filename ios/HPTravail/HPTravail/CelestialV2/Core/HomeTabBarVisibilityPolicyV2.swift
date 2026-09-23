import Foundation

enum HomeTabBarVisibilityPolicyV2 {
    static let inactivityTimeoutNanoseconds: UInt64 = 10_000_000_000
    static let inactivityTimeoutSeconds: TimeInterval = 10

    static func shouldHide(isHome: Bool, inactiveFor seconds: TimeInterval) -> Bool {
        isHome && seconds >= inactivityTimeoutSeconds
    }
}
