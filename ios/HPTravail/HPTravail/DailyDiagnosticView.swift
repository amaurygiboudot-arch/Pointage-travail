import SwiftUI

struct DailyDiagnosticView: View {
    @EnvironmentObject private var store: WorkStoreV2
    @State private var enabled = false
    @State private var day = Date()
    @State private var report: String?
    @State private var reportURL: URL?
    @State private var reportAccount: String?
    @State private var message: String?

    var body: some View {
        Section("Diagnostic de la journée") {
            Text("Enregistrement local facultatif : événements du pointage, horaires et décisions GPS, sans coordonnées. Purgé après 14 jours au prochain accès actif et limité à 2 000 événements par compte. Rien n'est envoyé automatiquement.")
                .font(.caption)
            Toggle("Enregistrer le diagnostic", isOn: Binding(get: { enabled }, set: { value in
                guard let account = store.diagnosticAccount else { return }
                do {
                    try DailyDiagnosticV2.setEnabled(value, account: account)
                    enabled = value
                    clearReport()
                    message = nil
                } catch { message = "Diagnostic indisponible. Le pointage reste indépendant." }
            }))
            .disabled(store.diagnosticAccount == nil)
            DatePicker("Journée à analyser", selection: $day, in: ...Date(), displayedComponents: .date)
            Button("Préparer le diagnostic de la journée") {
                guard let account = store.diagnosticAccount else { return }
                do {
                    let text = try DailyDiagnosticV2.export(account: account, day: day, sessions: store.storageReliable ? store.sessions : nil)
                    guard store.diagnosticAccount == account else { return }
                    clearReport()
                    let folder = FileManager.default.temporaryDirectory.appendingPathComponent("HoraTrackDiagnostics", isDirectory: true)
                    try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
                    let file = folder.appendingPathComponent("HoraTrack-diagnostic-\(UUID().uuidString).txt")
                    try Data(text.utf8).write(to: file, options: .atomic)
                    try FileManager.default.setAttributes([.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication], ofItemAtPath: file.path)
                    reportURL = file
                    reportAccount = account
                    report = text
                    message = nil
                } catch {
                    clearReport()
                    message = "Impossible de lire le diagnostic. Aucune donnée historique n'a été reconstruite."
                }
            }
            .disabled(store.diagnosticAccount == nil)
            if let report, let reportURL, reportAccount == store.diagnosticAccount {
                DisclosureGroup("Lire le diagnostic avant partage") {
                    ScrollView { Text(report).font(.caption.monospaced()).textSelection(.enabled) }.frame(maxHeight: 260)
                }
                ShareLink(item: reportURL) { Label("Partager le diagnostic", systemImage: "square.and.arrow.up") }
                Text("À transmettre volontairement dans ChatGPT. Je te demanderai ensuite tes arrivées, pauses, départs et déplacements pour comparer avec les traces. Ce rapport contient des horaires personnels.")
                    .font(.caption)
            }
            Button("Effacer le diagnostic et arrêter l'enregistrement", role: .destructive) {
                guard let account = store.diagnosticAccount else { return }
                do {
                    try DailyDiagnosticV2.erase(account: account)
                    enabled = false
                    clearReport()
                    message = "Diagnostic effacé. Les pointages sont conservés."
                } catch { message = "Effacement du diagnostic impossible." }
            }
            .disabled(store.diagnosticAccount == nil)
            if let message { Text(message).font(.caption) }
            Text("Une journée antérieure à l'activation ou sans traces reste inconnue. L'enregistrement ne permet pas de prouver toutes les interruptions du téléphone.")
                .font(.caption).foregroundStyle(.secondary)
        }
        .onAppear(perform: refresh)
        .onChange(of: store.diagnosticAccount) { _ in refresh() }
        .onChange(of: day) { _ in clearReport() }
        .onDisappear { clearReport() }
    }

    private func clearReport() {
        if let reportURL { try? FileManager.default.removeItem(at: reportURL) }
        reportURL = nil
        report = nil
    }

    private func refresh() {
        clearReport()
        // Previous interrupted exports are temporary files, never a second history store.
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent("HoraTrackDiagnostics", isDirectory: true)
        if let files = try? FileManager.default.contentsOfDirectory(at: folder, includingPropertiesForKeys: nil) {
            for file in files { try? FileManager.default.removeItem(at: file) }
        }
        reportAccount = nil
        enabled = false
        message = nil
        guard let account = store.diagnosticAccount else { return }
        do { enabled = try DailyDiagnosticV2.read(account: account).enabled }
        catch { message = "Diagnostic local illisible ; les pointages ne sont pas affectés." }
    }
}
