import Foundation
import SwiftUI
import FirebaseAuth
import FirebaseCore
import FirebaseFirestore

/// Invalidated when the screen or auth session changes; checked on the transaction queue too.
private final class CloudComfortOperationV2: @unchecked Sendable {
    private let lock = NSLock()
    private var valid = true
    func cancel() { lock.lock(); valid = false; lock.unlock() }
    var isValid: Bool { lock.lock(); defer { lock.unlock() }; return valid }
}

@MainActor
final class CloudComfortStoreV2: ObservableObject {
    @Published private(set) var snapshot: CloudComfortSnapshotV2?
    @Published private(set) var hasServerRead = false
    @Published private(set) var busy = false
    @Published private(set) var message: String?
    private var operation: CloudComfortOperationV2?
    private var owner: String?
    private var session: UUID?
    private var allowedOwner: String?
    private var allowedSession: UUID?

    func activate(uid: String?, session: UUID) {
        cancel()
        allowedOwner = uid; allowedSession = session
    }

    func cancel() {
        operation?.cancel(); operation = nil
        snapshot = nil; hasServerRead = false; busy = false
        owner = nil; session = nil; allowedOwner = nil; allowedSession = nil; message = nil
    }

    private func begin(uid: String, session: UUID) -> CloudComfortOperationV2? {
        guard allowedOwner == uid, allowedSession == session,
              FirebaseApp.app() != nil, Auth.auth().currentUser?.uid == uid else {
            message = "Connexion Firebase requise pour accéder à la sauvegarde du compte."
            return nil
        }
        operation?.cancel()
        let token = CloudComfortOperationV2()
        operation = token; owner = uid; self.session = session; busy = true; message = nil
        return token
    }

    private func current(_ token: CloudComfortOperationV2, uid: String, session: UUID) -> Bool {
        token.isValid && operation === token && owner == uid && self.session == session
            && allowedOwner == uid && allowedSession == session
            && FirebaseApp.app() != nil && Auth.auth().currentUser?.uid == uid
    }

    private func reference(_ uid: String) -> DocumentReference {
        Firestore.firestore().collection("users").document(uid)
            .collection("app_backup").document("comfort_shared_v1")
    }

    private nonisolated static func parse(_ document: DocumentSnapshot) throws -> CloudComfortSnapshotV2? {
        guard document.exists else { return nil }
        guard let fields = document.data() else { throw CloudComfortSnapshotV2.SnapshotError.malformed }
        return try CloudComfortSnapshotV2.parse(fields, timestampIsValid: fields["updatedAt"] is Timestamp)
    }

    func read(uid: String, session: UUID) async {
        guard !busy, let token = begin(uid: uid, session: session) else { return }
        hasServerRead = false; snapshot = nil
        do {
            let document = try await reference(uid).getDocument(source: .server)
            guard current(token, uid: uid, session: session) else { return }
            guard !document.metadata.isFromCache, !document.metadata.hasPendingWrites else {
                throw CloudComfortSnapshotV2.SnapshotError.malformed
            }
            snapshot = try Self.parse(document)
            hasServerRead = true; busy = false
            message = snapshot == nil ? "Aucune sauvegarde sur le serveur." : "Version du serveur lue. Rien n’a été appliqué sur cet appareil."
        } catch {
            guard current(token, uid: uid, session: session) else { return }
            hasServerRead = false; busy = false
            message = "Lecture impossible ou sauvegarde invalide. Aucun réglage n’a été modifié."
        }
    }

    func write(uid: String, session: UUID, transfer: ComfortTransferV2?) async {
        guard !busy, hasServerRead, owner == uid, self.session == session else { return }
        let expected = snapshot?.revision ?? 0
        guard let token = begin(uid: uid, session: session) else { return }
        let documentReference = reference(uid)
        do {
            let payload = try transfer?.encodedText() ?? ""
            _ = try await Firestore.firestore().runTransaction { (transaction, errorPointer) -> Any? in
                do {
                    guard token.isValid, Auth.auth().currentUser?.uid == uid else {
                        throw CloudComfortSnapshotV2.SnapshotError.conflict
                    }
                    let document = try transaction.getDocument(documentReference)
                    let server = try Self.parse(document)
                    let revision = try CloudComfortSnapshotV2.nextRevision(expected: expected, actual: server?.revision)
                    guard token.isValid, Auth.auth().currentUser?.uid == uid else {
                        throw CloudComfortSnapshotV2.SnapshotError.conflict
                    }
                    transaction.setData([
                        "schemaVersion": 1, "revision": revision, "payload": payload,
                        "deleted": transfer == nil, "updatedAt": FieldValue.serverTimestamp()
                    ], forDocument: documentReference)
                    return revision
                } catch {
                    errorPointer?.pointee = error as NSError
                    return nil
                }
            }
            guard current(token, uid: uid, session: session) else { return }
            busy = false; hasServerRead = false; snapshot = nil
            message = transfer == nil
                ? "Suppression enregistrée. Relisez le serveur avant toute nouvelle écriture."
                : "Confort sauvegardé. Relisez le serveur avant toute nouvelle écriture."
        } catch {
            guard current(token, uid: uid, session: session) else { return }
            busy = false; hasServerRead = false; snapshot = nil
            message = "Écriture non confirmée. Le réseau ou la version du serveur a pu changer ; relisez le serveur avant toute nouvelle écriture. Aucun réglage local n’a été modifié."
        }
    }
}
