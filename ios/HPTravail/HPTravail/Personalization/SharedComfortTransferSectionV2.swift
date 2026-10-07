import SwiftUI

struct SharedComfortTransferSectionV2: View {
    @EnvironmentObject private var preferences: PersonalizationStoreV2
    @State private var showPaste = false

    var body: some View {
        let session = preferences.sessionID
        Section("Confort Android / iOS") {
            Text("Transfert manuel du contraste, des mouvements réduits, du zoom de lecture et de la programmation Nuit. Le thème, la taille native, le contexte et la saisie restent propres à cet appareil.")
                .font(.caption)
            Text("Le nouveau format de transfert nécessite une application à jour sur l’appareil destinataire.").font(.caption)
            if let transfer = try? ComfortTransferV2(profile: preferences.value),
               let text = try? transfer.encodedText() {
                ShareLink(item: text) {
                    Label("Partager le confort Android/iOS", systemImage: "square.and.arrow.up")
                }
            }
            Button("Coller un confort Android/iOS") {
                if preferences.sessionID == session { showPaste = true }
            }
        }
        .sheet(isPresented: $showPaste) {
            SharedComfortPasteViewV2(session: session)
        }
        .onChange(of: preferences.sessionID) { _ in showPaste = false }
    }
}

private struct SharedComfortPasteViewV2: View {
    let session: UUID
    @EnvironmentObject private var preferences: PersonalizationStoreV2
    @Environment(\.dismiss) private var dismiss
    @State private var pasted = ""
    @State private var pending: ComfortTransferV2?
    @State private var errorMessage: String?

    var body: some View {
        NavigationStack {
            Form {
                Section("Coller le texte partagé") {
                    TextEditor(text: $pasted)
                        .autocorrectionDisabled(true)
                        .textInputAutocapitalization(.never)
                        .frame(minHeight: 160)
                        .accessibilityLabel("Profil de confort Android/iOS au format JSON")
                    Text("Maximum 4 096 octets UTF-8. Rien n’est appliqué avant votre confirmation.")
                        .font(.caption)
                    Button("Vérifier et afficher l’aperçu") {
                        guard preferences.sessionID == session else { dismiss(); return }
                        do {
                            pending = try ComfortTransferV2.decode(pasted)
                            errorMessage = nil
                        } catch {
                            pending = nil
                            errorMessage = "Profil refusé : format, version, champs ou valeurs invalides, ou taille supérieure à 4 096 octets."
                        }
                    }
                }
                if let candidate = pending {
                    Section("Aperçu des réglages") {
                        Text("Contraste renforcé : \(candidate.highContrast ? "oui" : "non")")
                        Text("Mouvements réduits : \(candidate.reduceMotion ? "oui" : "non")")
                        Text("Zoom de lecture : × \(String(candidate.readerScale))")
                        Text(candidate.nightSchedulePreview)
                        Text("Les préférences d’accessibilité iOS restent prioritaires. Le transfert ne change pas le contexte manuel.")
                            .font(.caption)
                        if preferences.value.context == "economy" {
                            Text("Le contexte Économie actif maintient les mouvements réduits même si le réglage importé est désactivé.")
                                .font(.caption)
                        }
                        Button("Appliquer ces réglages") {
                            guard preferences.sessionID == session else { dismiss(); return }
                            // Re-read now: native changes made since the preview are preserved.
                            preferences.set(candidate.applying(to: preferences.value))
                            if preferences.errorMessage == nil { dismiss() }
                            else { errorMessage = preferences.errorMessage }
                        }
                    }
                }
                if let errorMessage {
                    Section { Label(errorMessage, systemImage: "exclamationmark.triangle") }
                }
            }
            .navigationTitle("Importer le confort")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Annuler") { dismiss() } }
            }
            .onChange(of: pasted) { _ in pending = nil; errorMessage = nil }
            .onChange(of: preferences.sessionID) { _ in pending = nil; pasted = ""; dismiss() }
        }
    }
}
