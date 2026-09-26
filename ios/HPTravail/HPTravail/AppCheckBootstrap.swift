import Foundation
import FirebaseAppCheck
import FirebaseCore

/// Centralise l'initialisation Firebase App Check iOS.
///
/// - Debug: utilise le fournisseur App Check debug afin de fonctionner dans le
///   simulateur et sur les appareils de développement sans App Attest valide.
///   Le jeton affiché par Firebase dans la console Xcode doit être enregistré
///   dans Firebase App Check et ne doit jamais être commité.
/// - Release: utilise App Attest. La capability App Attest devra être activée
///   dans Xcode avec le compte Apple Developer avant une distribution réelle.
enum IOSAppCheckBootstrap {
    static var providerLabel: String {
#if DEBUG
        return "App Check debug"
#else
        return "App Attest"
#endif
    }

    static func configureBeforeFirebase() {
#if DEBUG
        AppCheck.setAppCheckProviderFactory(AppCheckDebugProviderFactory())
#else
        AppCheck.setAppCheckProviderFactory(IOSAppAttestProviderFactory())
#endif
    }
}

private final class IOSAppAttestProviderFactory: NSObject, AppCheckProviderFactory {
    func createProvider(with app: FirebaseApp) -> AppCheckProvider? {
        AppAttestProvider(app: app)
    }
}
