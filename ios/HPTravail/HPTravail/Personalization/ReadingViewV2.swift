import SwiftUI

private struct ReadingSelectionV2: Identifiable {
    let id = UUID()
    let text: String
    let session: UUID
}

/// Opt-in, visible and VoiceOver-accessible reading action. Does not mutate source content.
private struct ReadingActionModifierV2: ViewModifier {
    let text: String
    @EnvironmentObject private var preferences: PersonalizationStoreV2
    @State private var selection: ReadingSelectionV2?

    func body(content: Content) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            content
            Button {
                selection = ReadingSelectionV2(text: text, session: preferences.sessionID)
            } label: {
                Label("Lire en grand", systemImage: "text.magnifyingglass")
                    .font(.caption)
                    .frame(minHeight: 44)
            }
            .buttonStyle(.borderless)
            .accessibilityHint("Ouvre un texte défilable avec agrandissement et réduction")
        }
        .sheet(item: $selection) { selected in
            if selected.session == preferences.sessionID {
                ReadingViewV2(text: selected.text, session: selected.session)
            }
        }
        .onChange(of: preferences.sessionID) { _ in selection = nil }
    }
}

extension View {
    func readingActionV2(_ text: String) -> some View {
        modifier(ReadingActionModifierV2(text: text))
    }
}

private struct ReadingViewV2: View {
    let text: String
    let session: UUID
    @EnvironmentObject private var preferences: PersonalizationStoreV2
    @Environment(\.dismiss) private var dismiss
    @ScaledMetric(relativeTo: .body) private var bodySize: CGFloat = 17
    @State private var zoom = 1.5
    @State private var pinchBase: Double?
    @GestureState private var pinching = false
    @State private var saved = false
    @StateObject private var speech = ReadingSpeechV2()
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.accessibilityVoiceOverEnabled) private var voiceOver

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    ViewThatFits(in: .horizontal) {
                        HStack(spacing: 16) { zoomButtons }
                        VStack(alignment: .leading, spacing: 12) { zoomButtons }
                    }
                    Text("Zoom de lecture : \(Int(zoom * 100)) %")
                        .font(.caption)
                        .accessibilityValue("\(Int(zoom * 100)) pour cent")
                    Button(saved ? "Zoom mémorisé" : "Mémoriser ce zoom") {
                        guard preferences.sessionID == session else { dismiss(); return }
                        var next = preferences.value
                        next.readerScale = zoom
                        preferences.set(next)
                        saved = preferences.errorMessage == nil
                    }
                    .buttonStyle(.bordered)
                    if let error = preferences.errorMessage {
                        Label(error, systemImage: "exclamationmark.triangle")
                            .font(.caption)
                    }
                    Button(speech.speaking ? "Arrêter la lecture vocale" : "Écouter avec la voix de l’iPhone") {
                        guard preferences.sessionID == session else { speech.stop(); dismiss(); return }
                        if speech.speaking { speech.stop() } else { speech.start(text) }
                    }
                    .buttonStyle(.bordered)
                    if let message = speech.message { Text(message).font(.caption) }
                    Text(text)
                        .font(.system(size: bodySize * CGFloat(zoom)))
                        .foregroundStyle(Color.primary)
                        .textSelection(.enabled)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .fixedSize(horizontal: false, vertical: true)
                }
                .padding()
            }
            .background(Color(uiColor: .systemBackground))
            .simultaneousGesture(
                MagnificationGesture()
                    .updating($pinching) { _, active, _ in active = true }
                    .onChanged { magnification in
                        guard preferences.sessionID == session else { return }
                        if pinchBase == nil { pinchBase = zoom }
                        resize(ReaderZoomPolicyV2.pinched(base: pinchBase ?? zoom, magnification: Double(magnification)))
                    }
                    .onEnded { _ in pinchBase = nil }
            )
            .navigationTitle("Lecture agrandie")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Fermer") { dismiss() }
                }
            }
            .onAppear { zoom = ReaderZoomPolicyV2.clamped(preferences.value.readerScale) }
            .onChange(of: pinching) { active in if !active { pinchBase = nil } }
            .onChange(of: preferences.sessionID) { _ in speech.stop(); dismiss() }
            .onChange(of: scenePhase) { phase in if phase != .active { speech.stop() } }
            .onChange(of: voiceOver) { enabled in if enabled { speech.stop() } }
            .onDisappear { speech.stop() }
        }
    }

    private var zoomButtons: some View {
        Group {
            zoomButton("Réduire", symbol: "minus.magnifyingglass", disabled: zoom <= 1) { resize(zoom - 0.25) }
            zoomButton("Agrandir", symbol: "plus.magnifyingglass", disabled: zoom >= 4) { resize(zoom + 0.25) }
            zoomButton("Réinitialiser à 100 %", symbol: "arrow.counterclockwise", disabled: zoom == 1) { resize(1) }
        }
    }

    private func resize(_ value: Double) {
        zoom = ReaderZoomPolicyV2.clamped(value)
        saved = false
    }

    private func zoomButton(_ label: String, symbol: String, disabled: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: symbol)
                .font(.title2)
                .frame(minWidth: 44, minHeight: 44)
        }
        .accessibilityLabel(label)
        .disabled(disabled)
        .buttonStyle(.bordered)
    }
}
