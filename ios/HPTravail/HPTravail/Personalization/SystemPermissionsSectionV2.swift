import SwiftUI
import CoreLocation
import UserNotifications
import UIKit

/// Reads actual system grants; opening settings never requests a new permission itself.
struct SystemPermissionsSectionV2: View {
    @EnvironmentObject private var location: LocationManager
    @Environment(\.openURL) private var openURL
    @Environment(\.scenePhase) private var scenePhase
    @State private var notificationStatus = "Vérification…"

    var body: some View {
        Section("Autorisations système") {
            VStack(alignment: .leading, spacing: 4) {
                Text("Localisation").font(.headline)
                Text(locationStatus)
            }
            VStack(alignment: .leading, spacing: 4) {
                Text("Notifications").font(.headline)
                Text(notificationStatus)
            }
            Button("Ouvrir les autorisations iOS") {
                if let url = URL(string: UIApplication.openSettingsURLString) { openURL(url) }
            }
            Text("Ces états proviennent d’iOS. L’autorisation des notifications ne signifie pas qu’un rappel est programmé. Les accès sont demandés uniquement par les fonctions qui les utilisent.")
                .font(.caption)
        }
        .task(id: scenePhase) {
            guard scenePhase == .active else { return }
            let settings = await UNUserNotificationCenter.current().notificationSettings()
            guard !Task.isCancelled else { return }
            switch settings.authorizationStatus {
            case .notDetermined: notificationStatus = "Non demandée"
            case .denied: notificationStatus = "Refusée dans iOS"
            case .authorized: notificationStatus = "Autorisée dans iOS"
            case .provisional: notificationStatus = "Autorisation provisoire, discrète"
            case .ephemeral: notificationStatus = "Autorisation temporaire"
            @unknown default: notificationStatus = "État iOS non reconnu"
            }
        }
    }

    private var locationStatus: String {
        switch location.authorizationStatus {
        case .notDetermined: return "Non demandée"
        case .restricted: return "Restreinte par iOS"
        case .denied: return "Refusée dans iOS"
        case .authorizedWhenInUse: return "Autorisée pendant l’utilisation"
        case .authorizedAlways: return "Autorisée, y compris en arrière-plan"
        @unknown default: return "État iOS non reconnu"
        }
    }
}
