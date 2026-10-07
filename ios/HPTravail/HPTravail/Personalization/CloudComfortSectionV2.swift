import SwiftUI

struct CloudComfortSectionV2: View {
    @EnvironmentObject private var preferences: PersonalizationStoreV2
    @EnvironmentObject private var auth: AuthManager
    @StateObject private var cloud = CloudComfortStoreV2()
    @State private var restore: ComfortTransferV2?
    @State private var save: ComfortTransferV2?
    @State private var delete = false

    var body: some View {
        let session = preferences.sessionID
        let uid = auth.user?.uid
        Section("Sauvegarde manuelle du compte") {
            Text("Seuls le contraste, les mouvements réduits et le zoom de lecture sont transférés. Chaque lecture ou écriture est lancée par votre action ; aucun suivi ni envoi automatique.")
                .font(.caption)
            if let uid, auth.isFirebaseConfigured {
                Button("Lire la sauvegarde du compte") {
                    guard preferences.sessionID == session, auth.user?.uid == uid else { return }
                    Task { await cloud.read(uid: uid, session: session) }
                }
                .disabled(cloud.busy)
                if let snapshot = cloud.snapshot {
                    Text("Révision du serveur : \(snapshot.revision)")
                    if snapshot.deleted {
                        Text("Sauvegarde supprimée. La révision est conservée pour bloquer les anciennes écritures.")
                    } else if let transfer = snapshot.transfer {
                        preview(transfer)
                        Button("Restaurer ces trois réglages") { restore = transfer }
                            .disabled(cloud.busy)
                    }
                }
                Button("Sauvegarder ce confort") {
                    guard preferences.sessionID == session, auth.user?.uid == uid else { return }
                    save = try? ComfortTransferV2(profile: preferences.value)
                }
                .disabled(!cloud.hasServerRead || cloud.busy)
                Button("Supprimer la sauvegarde du compte", role: .destructive) { delete = true }
                    .disabled(!cloud.hasServerRead || cloud.busy)
                if !cloud.hasServerRead {
                    Text("Lisez d’abord la version du serveur avant de sauvegarder ou supprimer.")
                        .font(.caption)
                }
                if cloud.busy { ProgressView("Opération demandée en cours") }
                if let message = cloud.message { Text(message).font(.caption) }
            } else {
                Text("Connectez-vous à votre compte pour utiliser sa sauvegarde. Les réglages locaux restent disponibles.")
            }
        }
        .confirmationDialog("Restaurer le confort lu sur le serveur ?", isPresented: Binding(get: { restore != nil }, set: { if !$0 { restore = nil } }), titleVisibility: .visible) {
            Button("Appliquer les trois réglages") {
                guard preferences.sessionID == session, auth.user?.uid == uid else { return }
                if let transfer = restore { preferences.set(transfer.applying(to: preferences.value)) }
                restore = nil
            }
            Button("Annuler", role: .cancel) { restore = nil }
        } message: {
            if let restore { Text(description(restore)) }
        }
        .confirmationDialog("Sauvegarder ce confort dans le compte ?", isPresented: Binding(get: { save != nil }, set: { if !$0 { save = nil } }), titleVisibility: .visible) {
            Button("Sauvegarder les trois réglages") {
                guard preferences.sessionID == session, let uid, auth.user?.uid == uid, let transfer = save else { return }
                save = nil
                Task { await cloud.write(uid: uid, session: session, transfer: transfer) }
            }
            Button("Annuler", role: .cancel) { save = nil }
        } message: {
            if let save { Text(description(save) + " Toute modification concurrente fera refuser l’écriture.") }
        }
        .confirmationDialog("Supprimer la sauvegarde du compte ?", isPresented: $delete, titleVisibility: .visible) {
            Button("Supprimer", role: .destructive) {
                guard preferences.sessionID == session, let uid, auth.user?.uid == uid else { return }
                Task { await cloud.write(uid: uid, session: session, transfer: nil) }
            }
            Button("Annuler", role: .cancel) {}
        } message: { Text("Les réglages de cet appareil sont conservés. Une trace de suppression versionnée empêche un appareil ayant une ancienne version de réécrire sans relire.") }
        .onAppear { cloud.activate(uid: uid, session: session) }
        .onChange(of: preferences.sessionID) { _ in clear() }
        .onChange(of: auth.user?.uid) { _ in clear() }
        .onDisappear { clear() }
    }

    private func description(_ value: ComfortTransferV2) -> String {
        "Contraste renforcé : \(value.highContrast ? "oui" : "non"). Mouvements réduits : \(value.reduceMotion ? "oui" : "non"). Zoom de lecture : × \(String(value.readerScale)). Les choix système et le contexte actif restent prioritaires."
    }
    private func preview(_ value: ComfortTransferV2) -> some View { Text(description(value)) }
    private func clear() { restore = nil; save = nil; delete = false; cloud.cancel() }
}
