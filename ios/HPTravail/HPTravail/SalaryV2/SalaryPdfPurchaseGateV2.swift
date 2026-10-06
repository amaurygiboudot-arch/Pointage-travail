import Foundation

/// Fail closed until StoreKit and server-side purchase verification are connected.
/// No local flag, restored file or client-supplied receipt grants PDF access.
enum SalaryPdfPurchaseGateV2 {
    static var isAvailable: Bool { false }
    static let unavailableMessage = "PDF premium indisponible sur iOS pour le moment. L’achat et la vérification du paiement ne sont pas encore disponibles. Aucun aperçu ni PDF n’est généré."

    enum AccessError: LocalizedError {
        case purchaseUnavailable
        var errorDescription: String? { SalaryPdfPurchaseGateV2.unavailableMessage }
    }

    static func requireVerifiedPurchase() throws {
        guard isAvailable else { throw AccessError.purchaseUnavailable }
    }
}
