import SwiftUI
import AVFoundation
import UIKit

/// Uses an already enumerated native Apple voice; no network provider or audio recording.
@MainActor
final class ReadingSpeechV2: NSObject, ObservableObject, AVSpeechSynthesizerDelegate {
    @Published private(set) var speaking = false
    @Published private(set) var message: String?
    private let synthesizer = AVSpeechSynthesizer()
    private var active: AVSpeechUtterance?

    override init() {
        super.init()
        synthesizer.delegate = self
    }

    func start(_ text: String) {
        stop()
        guard !UIAccessibility.isVoiceOverRunning else {
            message = "VoiceOver est actif : utilisez sa lecture pour éviter deux voix simultanées."
            return
        }
        guard !text.isEmpty, text.count <= 50_000 else {
            message = "Lecture indisponible pour ce texte vide ou supérieur à 50 000 caractères."
            return
        }
        // The app's current explanations are French. Never silently select a different language.
        let installed = AVSpeechSynthesisVoice.speechVoices().filter {
            $0.language.hasPrefix("fr") && $0.identifier.hasPrefix("com.apple.") && $0.quality == .default
        }
        guard let voice = installed.first(where: { $0.language == "fr-FR" }) ?? installed.first else {
            message = "Aucune voix française native standard utilisable n’a été trouvée. Configurez une voix dans les réglages d’accessibilité iOS."
            return
        }
        let utterance = AVSpeechUtterance(string: text)
        utterance.voice = voice
        utterance.rate = AVSpeechUtteranceDefaultSpeechRate
        active = utterance; message = nil; speaking = true
        synthesizer.speak(utterance)
    }

    func stop() {
        active = nil
        synthesizer.stopSpeaking(at: .immediate)
        speaking = false
    }

    nonisolated func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer, didFinish utterance: AVSpeechUtterance) {
        Task { @MainActor [weak self] in
            guard let self, self.active === utterance else { return }
            self.active = nil; self.speaking = false
        }
    }
    nonisolated func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer, didCancel utterance: AVSpeechUtterance) {
        Task { @MainActor [weak self] in
            guard let self, self.active === utterance else { return }
            self.active = nil; self.speaking = false
        }
    }
}
