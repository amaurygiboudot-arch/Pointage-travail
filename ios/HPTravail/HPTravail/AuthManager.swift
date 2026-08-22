import Foundation
import FirebaseAuth
import FirebaseCore
import FirebaseFirestore
import GoogleSignIn
import UIKit

@MainActor
final class AuthManager: ObservableObject {
    @Published private(set) var user: User?
    @Published private(set) var isFirebaseConfigured = false
    @Published var errorMessage: String?

    private var authListener: AuthStateDidChangeListenerHandle?

    init() {
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

    func signInWithGoogle() {
        guard isFirebaseConfigured,
              let clientID = FirebaseApp.app()?.options.clientID else {
            errorMessage = "Firebase iOS n'est pas encore configuré."
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

            Auth.auth().signIn(with: credential) { [weak self] authResult, error in
                if let error {
                    Task { @MainActor in self?.errorMessage = error.localizedDescription }
                    return
                }
                guard let firebaseUser = authResult?.user else { return }
                Task { @MainActor in
                    self?.user = firebaseUser
                    self?.saveUserProfile(firebaseUser)
                }
            }
        }
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
        let data: [String: Any] = [
            "uid": user.uid,
            "displayName": user.displayName ?? "",
            "email": user.email ?? "",
            "photoUrl": user.photoURL?.absoluteString ?? "",
            "platform": "ios",
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
