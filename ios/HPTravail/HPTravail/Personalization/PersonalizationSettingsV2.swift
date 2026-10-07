import SwiftUI

struct PersonalizationSettingsV2: View {
    @EnvironmentObject private var preferences: PersonalizationStoreV2
    @State private var showExport = false
    @State private var showImport = false
    @State private var pendingImport: VisualPreferencesV2?
    @State private var showReset = false

    var body: some View {
        let session = preferences.sessionID
        Group {
            Section("Confort visuel") {
                Picker("Apparence", selection: preferences.binding(\.appearance)) {
                    Text("Suivre le système").tag("system")
                    Text("Clair").tag("light")
                    Text("Sombre").tag("dark")
                }
                Stepper("Agrandissement : +\(preferences.value.textSteps) niveaux", value: preferences.binding(\.textSteps), in: 0...5)
                Stepper("Zoom de lecture : \(Int(preferences.value.readerScale * 100)) %", value: preferences.binding(\.readerScale), in: 1...4, step: 0.25)
                Text("La taille du système reste la base. L’application peut l’agrandir, sans réduire les besoins d’accessibilité.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                Text("Aperçu : horaires, montants et explications restent lisibles.")
                Toggle("Contraste renforcé des cartes et actions", isOn: preferences.binding(\.highContrast))
                Toggle("Réduire les mouvements", isOn: preferences.binding(\.reduceMotion))
                Toggle("Surfaces opaques", isOn: preferences.binding(\.opaqueSurfaces))
            }
            Section("Contexte temporaire") {
                Picker("Contexte actif", selection: preferences.binding(\.context)) {
                    Text("Mes réglages habituels").tag("standard")
                    Text("Nuit — apparence sombre").tag("night")
                    Text("Économie — effets réduits").tag("economy")
                }
                Toggle("Programmer le contexte Nuit", isOn: preferences.binding(\.nightScheduleEnabled))
                if preferences.value.nightScheduleEnabled {
                    DatePicker("Début de la nuit", selection: scheduleTime(\.nightStartMinute), displayedComponents: .hourAndMinute)
                    DatePicker("Fin de la nuit", selection: scheduleTime(\.nightEndMinute), displayedComponents: .hourAndMinute)
                }
                Text("La plage suit l’heure locale, début inclus et fin exclue. Un contexte manuel prioritaire suspend la plage. L’écran est réévalué chaque minute uniquement pendant l’utilisation ; aucune tâche en arrière-plan.")
                    .font(.caption)
                Text("Le contexte conserve les préférences de base. Revenez aux réglages habituels pour les retrouver.")
                    .font(.caption)
            }
            Section("Saisie") {
                Toggle("Correction du clavier dans les textes libres", isOn: preferences.binding(\.systemSpelling))
                Text("Les montants, dates, identifiants et références restent sans correction automatique. Dictée, sélection et annulation utilisent les commandes du clavier iOS.")
                    .font(.caption)
            }
            SystemPermissionsSectionV2()
            SharedComfortTransferSectionV2()
            CloudComfortSectionV2()
            Section("Conservation et restauration") {
                Text("Préférences conservées sur cet appareil, séparément pour chaque compte et pour le visiteur. La synchronisation entre appareils n’est pas activée.")
                    .font(.caption)
                Button("Exporter les préférences") { if preferences.sessionID == session { showExport = true } }
                Button("Importer un fichier de préférences") { if preferences.sessionID == session { showImport = true } }
                Button("Annuler / rétablir le dernier réglage") { if preferences.sessionID == session { preferences.undo() } }
                    .disabled(preferences.previous == nil)
                Button("Réinitialiser le confort visuel", role: .destructive) { if preferences.sessionID == session { showReset = true } }
                if let error = preferences.errorMessage {
                    Label(error, systemImage: "exclamationmark.triangle")
                        .accessibilityLabel(error)
                }
            }
        .fileExporter(isPresented: $showExport, document: PersonalizationDocumentV2(value: preferences.value), contentType: .json, defaultFilename: "AGKGMG-preferences") { result in
            guard preferences.sessionID == session else { return }
            if case .failure = result { preferences.errorMessage = "Le fichier n’a pas pu être exporté." }
        }
        .fileImporter(isPresented: $showImport, allowedContentTypes: [.json]) { result in
            guard preferences.sessionID == session else { return }
            do {
                let url = try result.get()
                let scoped = url.startAccessingSecurityScopedResource()
                defer { if scoped { url.stopAccessingSecurityScopedResource() } }
                pendingImport = try VisualPreferencesV2.readImport(from: url)
            } catch { preferences.errorMessage = "Import refusé : fichier invalide, incomplet ou version incompatible. Vos réglages sont conservés." }
        }
        .confirmationDialog("Restaurer ces préférences ?", isPresented: Binding(get: { pendingImport != nil }, set: { if !$0 { pendingImport = nil } }), titleVisibility: .visible) {
            Button("Restaurer") {
                guard preferences.sessionID == session else { return }
                if let candidate = pendingImport { preferences.set(candidate) }
                pendingImport = nil
            }
            Button("Annuler", role: .cancel) { pendingImport = nil }
        } message: {
            if let candidate = pendingImport {
                Text("Apparence : \(candidate.appearance), agrandissement : +\(candidate.textSteps), contraste renforcé : \(candidate.highContrast ? "oui" : "non"), zoom de lecture : \(Int(candidate.readerScale * 100)) %, contexte : \(candidate.context), thème : \(candidate.accent), mouvements réduits : \(candidate.reduceMotion ? "oui" : "non"), surfaces opaques : \(candidate.opaqueSurfaces ? "oui" : "non"), correction clavier : \(candidate.systemSpelling ? "oui" : "non"). Nuit programmée : \(candidate.nightScheduleEnabled ? "oui" : "non"), début : \(candidate.nightStartMinute / 60) h \(candidate.nightStartMinute % 60), fin : \(candidate.nightEndMinute / 60) h \(candidate.nightEndMinute % 60). Les données de pointage et de paie restent conservées.")
            }
        }
        .confirmationDialog("Réinitialiser le confort visuel ?", isPresented: $showReset, titleVisibility: .visible) {
            Button("Réinitialiser", role: .destructive) { if preferences.sessionID == session { preferences.resetVisual() } }
            Button("Annuler", role: .cancel) {}
        } message: { Text("Seuls les réglages visuels de ce profil seront réinitialisés. L’annulation restera disponible.") }
        }
    }

    private func scheduleTime(_ key: WritableKeyPath<VisualPreferencesV2, Int>) -> Binding<Date> {
        let session = preferences.sessionID
        return Binding(get: {
            let minute = preferences.value[keyPath: key]
            return Calendar.current.date(bySettingHour: minute / 60, minute: minute % 60, second: 0, of: Date()) ?? Date()
        }, set: { value in
            guard preferences.sessionID == session else { return }
            var next = preferences.value
            next[keyPath: key] = VisualPreferencesV2.localMinute(value)
            preferences.set(next)
        })
    }

}
