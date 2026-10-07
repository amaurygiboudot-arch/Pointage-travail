import SwiftUI
import UniformTypeIdentifiers

@MainActor
final class PersonalizationStoreV2: ObservableObject {
    @Published private(set) var value = VisualPreferencesV2()
    @Published private(set) var sessionID = UUID()
    @Published private(set) var previous: VisualPreferencesV2?
    @Published var errorMessage: String?
    private var accountID: String?

    init() { activate(accountID: nil) }

    func activate(accountID: String?) {
        self.accountID = accountID
        // A transient generation token prevents delayed UI callbacks crossing auth sessions.
        sessionID = UUID()
        previous = nil
        errorMessage = nil
        do { value = try VisualPreferencesRepositoryV2.read(accountID: accountID) }
        catch {
            value = VisualPreferencesV2()
            errorMessage = "Préférences illisibles : valeurs de secours affichées. Le contenu conservé n’a pas été écrasé."
        }
    }

    func set(_ next: VisualPreferencesV2) {
        persist(next, explicitReplacement: false)
    }

    /// Called only after confirmation of a validated complete preferences file.
    func replaceFromImport(_ next: VisualPreferencesV2) {
        persist(next, explicitReplacement: true)
    }

    private func persist(_ next: VisualPreferencesV2, explicitReplacement: Bool) {
        do {
            guard next.valid else { throw VisualPreferencesV2.ValidationError.unsupported }
            // Never offer the displayed fallback as an undo of unreadable stored data.
            let storedPrevious = try? VisualPreferencesRepositoryV2.read(accountID: accountID)
            if explicitReplacement {
                try VisualPreferencesRepositoryV2.replaceExplicitly(next, accountID: accountID)
            } else {
                try VisualPreferencesRepositoryV2.write(next, accountID: accountID)
            }
            previous = storedPrevious
            value = next
            errorMessage = nil
        } catch VisualPreferencesRepositoryV2.StorageError.existingProfileUnreadable {
            errorMessage = "Préférences conservées mais illisibles : modification refusée. Importez un fichier complet ou confirmez une réinitialisation pour les remplacer."
        } catch { errorMessage = "Préférences non enregistrées : format invalide." }
    }

    func binding<T>(_ key: WritableKeyPath<VisualPreferencesV2, T>) -> Binding<T> {
        let session = sessionID
        return Binding(get: { self.value[keyPath: key] }, set: { updated in
            guard self.sessionID == session else { return }
            var next = self.value
            next[keyPath: key] = updated
            self.set(next)
        })
    }

    func undo() {
        guard let previous else { return }
        set(previous)
    }

    func resetVisual() {
        var next = VisualPreferencesV2()
        // Preserve spelling only when the original profile can actually be read.
        if let stored = try? VisualPreferencesRepositoryV2.read(accountID: accountID) {
            next.systemSpelling = stored.systemSpelling
        }
        persist(next, explicitReplacement: true)
    }
}

struct PersonalizationDocumentV2: FileDocument {
    static var readableContentTypes: [UTType] { [.json] }
    var value: VisualPreferencesV2
    init(value: VisualPreferencesV2) { self.value = value }
    init(configuration: ReadConfiguration) throws {
        guard let data = configuration.file.regularFileContents else {
            throw VisualPreferencesV2.ValidationError.unsupported
        }
        value = try VisualPreferencesV2.decode(data)
    }
    func fileWrapper(configuration: WriteConfiguration) throws -> FileWrapper {
        FileWrapper(regularFileWithContents: try JSONEncoder().encode(value))
    }
}

struct PersonalizationRootModifierV2: ViewModifier {
    @EnvironmentObject private var preferences: PersonalizationStoreV2
    @Environment(\.dynamicTypeSize) private var systemTextSize
    @Environment(\.accessibilityReduceMotion) private var systemReduceMotion
    @Environment(\.scenePhase) private var scenePhase
    @State private var scheduleDate = Date()
    private var scheduleActive: Bool { scenePhase == .active && preferences.value.nightScheduleEnabled }

    private var textSize: DynamicTypeSize {
        let sizes = DynamicTypeSize.allCases.sorted()
        let index = sizes.firstIndex(of: systemTextSize) ?? 3
        return sizes[preferences.value.textSizeIndex(systemIndex: index, maximumIndex: sizes.count - 1)]
    }

    func body(content: Content) -> some View {
        content
            .preferredColorScheme(preferences.value.darkMode(minuteOfDay: VisualPreferencesV2.localMinute(scheduleDate)).map { $0 ? ColorScheme.dark : .light })
            .dynamicTypeSize(textSize)
            .task(id: scheduleActive) {
                scheduleDate = Date()
                guard scheduleActive else { return }
                while !Task.isCancelled {
                    let now = Date()
                    let next = Calendar.current.nextDate(after: now, matching: DateComponents(second: 0), matchingPolicy: .nextTime) ?? now.addingTimeInterval(60)
                    do { try await Task.sleep(nanoseconds: UInt64(max(0.1, min(60, next.timeIntervalSinceNow)) * 1_000_000_000)) }
                    catch { return }
                    guard !Task.isCancelled else { return }
                    scheduleDate = Date()
                }
            }
            .transaction { transaction in
                if systemReduceMotion || preferences.value.effectiveReducedMotion {
                    transaction.animation = nil
                    transaction.disablesAnimations = true
                }
            }
    }
}

/// Native input preserves IME composition, Unicode, dictation and system undo.
/// Structured payroll inputs never receive prose autocorrection.
struct SharedTextFieldV2: View {
    let title: LocalizedStringKey
    @Binding var text: String
    var prose: Bool = false
    @EnvironmentObject private var preferences: PersonalizationStoreV2

    init(_ title: LocalizedStringKey, text: Binding<String>, prose: Bool = false) {
        self.title = title
        self._text = text
        self.prose = prose
    }

    var body: some View {
        TextField(title, text: $text)
            .autocorrectionDisabled(!prose || !preferences.value.systemSpelling)
            .textInputAutocapitalization(prose ? .sentences : .never)
    }
}

struct ReadableCardSurfaceV2: View {
    @EnvironmentObject private var preferences: PersonalizationStoreV2
    @Environment(\.accessibilityReduceTransparency) private var reduceTransparency
    @Environment(\.colorSchemeContrast) private var contrast

    var body: some View {
        if reduceTransparency || contrast == .increased || preferences.value.opaqueSurfaces || preferences.value.highContrast {
            RoundedRectangle(cornerRadius: 18)
                .fill(Color(uiColor: .systemBackground))
                .overlay {
                    RoundedRectangle(cornerRadius: 18)
                        .stroke(Color.primary.opacity(0.5), lineWidth: 1)
                }
        } else {
            RoundedRectangle(cornerRadius: 18).fill(.thinMaterial)
        }
    }
}
