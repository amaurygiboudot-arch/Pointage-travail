import Foundation
import AuthenticationServices
import CryptoKit
import FirebaseAuth
import FirebaseCore
import FirebaseFirestore
import GoogleSignIn
import UIKit

@MainActor
final class AuthManager: NSObject, ObservableObject, ASAuthorizationControllerDelegate, ASAuthorizationControllerPresentationContextProviding {
    @Published private(set) var user: User?
    @Published private(set) var isFirebaseConfigured = false
    @Published var errorMessage: String?

    private var authListener: AuthStateDidChangeListenerHandle?
    private var currentAppleNonce: String?

    override init() {
        super.init()
        configureFirebaseIfPossible()
        guard isFirebaseConfigured else { return }

        user = Auth.auth().currentUser
        authListener = Auth.auth().addStateDidChangeListener { [weak self] _, user in
            Task { @MainActor in
                self?.user = user
            }
        }
    }

    deinit {
        if let authListener, FirebaseApp.app() != nil {
            Auth.auth().removeStateDidChangeListener(authListener)
        }
    }

    var isSignedIn: Bool { user != nil }
    var isGoogleLinked: Bool { user?.providerData.contains { $0.providerID == "google.com" } == true }
    var isAppleLinked: Bool { user?.providerData.contains { $0.providerID == "apple.com" } == true }

    func signInWithGoogle() {
        guard isFirebaseConfigured,
              let clientID = FirebaseApp.app()?.options.clientID else {
            errorMessage = "Firebase iOS n'est pas encore configuré."
            return
        }

        if isGoogleLinked {
            errorMessage = "Le compte Google est déjà connecté à ce profil."
            return
        }

        guard let presenter = Self.topViewController() else {
            errorMessage = "Impossible d'ouvrir la connexion Google."
            return
        }

        GIDSignIn.sharedInstance.configuration = GIDConfiguration(clientID: clientID)
        GIDSignIn.sharedInstance.signIn(withPresenting: presenter) { [weak self] result, error in
            if let error {
                Task { @MainActor in self?.errorMessage = error.localizedDescription }
                return
            }

            guard let googleUser = result?.user,
                  let idToken = googleUser.idToken?.tokenString else {
                Task { @MainActor in self?.errorMessage = "Jeton Google manquant." }
                return
            }

            let credential = GoogleAuthProvider.credential(
                withIDToken: idToken,
                accessToken: googleUser.accessToken.tokenString
            )

            Task { @MainActor in
                self?.useCredential(credential)
            }
        }
    }

    func signInWithApple() {
        guard isFirebaseConfigured else {
            errorMessage = "Firebase iOS n'est pas encore configuré."
            return
        }

        if isAppleLinked {
            errorMessage = "Le compte Apple est déjà connecté à ce profil."
            return
        }

        let nonce = UUID().uuidString
        currentAppleNonce = nonce

        let request = ASAuthorizationAppleIDProvider().createRequest()
        request.requestedScopes = [.fullName, .email]
        request.nonce = sha256(nonce)

        let controller = ASAuthorizationController(authorizationRequests: [request])
        controller.delegate = self
        controller.presentationContextProvider = self
        controller.performRequests()
    }

    func authorizationController(controller: ASAuthorizationController, didCompleteWithAuthorization authorization: ASAuthorization) {
        guard let appleCredential = authorization.credential as? ASAuthorizationAppleIDCredential,
              let nonce = currentAppleNonce,
              let tokenData = appleCredential.identityToken,
              let idToken = String(data: tokenData, encoding: .utf8) else {
            errorMessage = "Impossible de récupérer les informations du compte Apple."
            return
        }

        let credential = OAuthProvider.appleCredential(
            withIDToken: idToken,
            rawNonce: nonce,
            fullName: appleCredential.fullName
        )
        useCredential(credential)
    }

    func authorizationController(controller: ASAuthorizationController, didCompleteWithError error: Error) {
        errorMessage = error.localizedDescription
    }

    func presentationAnchor(for controller: ASAuthorizationController) -> ASPresentationAnchor {
        UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .flatMap { $0.windows }
            .first { $0.isKeyWindow } ?? UIWindow()
    }

    func signOut() {
        guard isFirebaseConfigured else { return }
        do {
            try Auth.auth().signOut()
            GIDSignIn.sharedInstance.signOut()
            user = nil
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    private func useCredential(_ credential: AuthCredential) {
        if let currentUser = Auth.auth().currentUser {
            currentUser.link(with: credential) { [weak self] result, error in
                if let error {
                    Task { @MainActor in self?.errorMessage = error.localizedDescription }
                    return
                }
                guard let firebaseUser = result?.user else { return }
                Task { @MainActor in
                    self?.user = firebaseUser
                    self?.saveUserProfile(firebaseUser)
                }
            }
        } else {
            Auth.auth().signIn(with: credential) { [weak self] result, error in
                if let error {
                    Task { @MainActor in self?.errorMessage = error.localizedDescription }
                    return
                }
                guard let firebaseUser = result?.user else { return }
                Task { @MainActor in
                    self?.user = firebaseUser
                    self?.saveUserProfile(firebaseUser)
                }
            }
        }
    }

    private func sha256(_ input: String) -> String {
        let data = Data(input.utf8)
        let digest = SHA256.hash(data: data)
        return digest.map { String(format: "%02x", $0) }.joined()
    }

    private func configureFirebaseIfPossible() {
        if FirebaseApp.app() != nil {
            isFirebaseConfigured = true
            return
        }

        guard Bundle.main.url(forResource: "GoogleService-Info", withExtension: "plist") != nil else {
            isFirebaseConfigured = false
            return
        }

        FirebaseApp.configure()
        isFirebaseConfigured = FirebaseApp.app() != nil
    }

    private func saveUserProfile(_ user: User) {
        let providers = user.providerData.map(\.providerID)
        let data: [String: Any] = [
            "uid": user.uid,
            "displayName": user.displayName ?? "",
            "email": user.email ?? "",
            "photoUrl": user.photoURL?.absoluteString ?? "",
            "platform": "ios",
            "providers": providers,
            "lastLoginAt": FieldValue.serverTimestamp()
        ]

        Firestore.firestore().collection("users").document(user.uid)
            .setData(data, merge: true)
    }

    private static func topViewController(base: UIViewController? = nil) -> UIViewController? {
        let root = base ?? UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .flatMap { $0.windows }
            .first { $0.isKeyWindow }?
            .rootViewController

        if let nav = root as? UINavigationController {
            return topViewController(base: nav.visibleViewController)
        }
        if let tab = root as? UITabBarController, let selected = tab.selectedViewController {
            return topViewController(base: selected)
        }
        if let presented = root?.presentedViewController {
            return topViewController(base: presented)
        }
        return root
    }
}
