import SwiftUI
import CoreLocation
import UIKit

/// A separate opt-in travel diary; work clock and salary are never mutated here.
struct TravelJourneyViewV2: View {
    private struct PendingCapture {
        let id: String
        let account: String
        let departure: Bool
        let at: Date
    }

    @EnvironmentObject private var store: WorkStoreV2
    @EnvironmentObject private var locationManager: LocationManager
    @EnvironmentObject private var authManager: AuthManager
    @Environment(\.scenePhase) private var scenePhase
    @State private var journeys: [TravelJourneyV2]?
    @State private var pendingCapture: PendingCapture?
    @State private var feedback: String?
    @State private var showMapsConsent = false

    private var openJourney: TravelJourneyV2? {
        journeys?.first { $0.endedAt == nil }
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    Text("Trajets professionnels")
                        .font(.title2.bold())

                    Text("Départ et arrivée enregistrés séparément du pointage. Une position GPS est recherchée à chaque action, sans suivi continu en arrière-plan.")
                        .font(.footnote)
                        .foregroundStyle(.secondary)

                    if journeys == nil {
                        Label("Journal à vérifier ou compte indisponible. Aucune donnée ne sera écrasée.",
                              systemImage: "exclamationmark.triangle")
                            .foregroundStyle(.orange)
                    } else if let active = openJourney {
                        Label("Déplacement en cours depuis \(active.startedAt.formatted(date: .abbreviated, time: .shortened))",
                              systemImage: "car.fill")
                            .foregroundStyle(.green)
                    } else {
                        Label("Aucun déplacement en cours", systemImage: "checkmark.circle")
                    }

                    Button {
                        startJourney()
                    } label: {
                        Label("DÉMARRER UN DÉPLACEMENT", systemImage: "play.circle.fill")
                            .frame(maxWidth: .infinity, minHeight: 48)
                    }
                    .buttonStyle(.borderedProminent)
                    .disabled(journeys == nil || openJourney != nil)

                    Button {
                        finishJourney()
                    } label: {
                        Label("TERMINER LE DÉPLACEMENT", systemImage: "stop.circle.fill")
                            .frame(maxWidth: .infinity, minHeight: 48)
                    }
                    .buttonStyle(.bordered)
                    .disabled(journeys == nil || openJourney == nil)

                    Button {
                        showMapsConsent = true
                    } label: {
                        Label("AFFICHER DANS GOOGLE MAPS", systemImage: "map")
                            .frame(maxWidth: .infinity, minHeight: 48)
                    }
                    .buttonStyle(.bordered)
                    .disabled(journeys == nil)

                    if let feedback {
                        Text(feedback).font(.footnote).foregroundStyle(.orange)
                    }

                    Text("Historique des déplacements").font(.headline)
                    ForEach(Array((journeys ?? []).reversed().prefix(30))) { trip in
                        VStack(alignment: .leading, spacing: 4) {
                            Text("Départ : \(trip.startedAt.formatted(date: .abbreviated, time: .shortened))")
                            if let endedAt = trip.endedAt {
                                Text("Arrivée : \(endedAt.formatted(date: .abbreviated, time: .shortened))")
                            } else {
                                Text("En cours").foregroundStyle(.green)
                            }
                            let fixCount = (trip.departure == nil ? 0 : 1) +
                                (trip.arrival == nil ? 0 : 1)
                            Text("Positions GPS : \(fixCount)/2 — kilomètres réellement parcourus non déterminés")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                        .padding(.vertical, 6)
                        Divider()
                    }

                    Text("Maps peut afficher un itinéraire estimé. Le kilométrage réellement parcouru, les heures payées et les indemnités ne sont pas déduits automatiquement de la carte.")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
                .padding()
            }
            .navigationTitle("Déplacements")
        }
        .onAppear(perform: reload)
        .onChange(of: authManager.user?.uid) { _ in
            pendingCapture = nil
            feedback = nil
            reload()
        }
        .onChange(of: scenePhase) { phase in
            if phase == .active { reload() }
        }
        .onChange(of: locationManager.location?.timestamp) { _ in
            capturePositionIfAvailable()
        }
        .confirmationDialog("Ouvrir Google Maps ?", isPresented: $showMapsConsent,
                            titleVisibility: .visible) {
            Button("Ouvrir dans Maps") {
                if let url = mapsURL() { UIApplication.shared.open(url) }
            }
            Button("Annuler", role: .cancel) {}
        } message: {
            Text("Seules les coordonnées connues du trajet sélectionné seront transmises à Maps si tu ouvres le lien. Aucun kilométrage réel n'est certifié par cet itinéraire.")
        }
    }

    private func reload() {
        guard let account = TravelJourneyStoreV2.currentAccount() else {
            journeys = nil
            return
        }
        journeys = TravelJourneyStoreV2.read(account: account)
    }

    private func startJourney() {
        guard let account = TravelJourneyStoreV2.currentAccount() else {
            feedback = "Compte indisponible : trajet non démarré."
            return
        }
        let companies = SalaryCompanyStoreV2.readConfirmed()
        let employer = store.currentSession?.employerId ??
            (companies.reliable ? companies.companies.count == 1 ? companies.companies[0].id : nil : nil)
        let at = Date()
        guard let id = TravelJourneyStoreV2.begin(account: account, at: at, employerId: employer) else {
            feedback = "Départ non enregistré : journal à vérifier."
            reload()
            return
        }
        feedback = "Départ enregistré. Position GPS facultative en cours de recherche."
        pendingCapture = PendingCapture(id: id, account: account, departure: true, at: at)
        locationManager.requestCurrentLocation()
        reload()
    }

    private func finishJourney() {
        guard let account = TravelJourneyStoreV2.currentAccount() else {
            feedback = "Compte indisponible : trajet non terminé."
            return
        }
        let at = Date()
        guard let id = TravelJourneyStoreV2.finish(account: account, at: at) else {
            feedback = "Arrivée non enregistrée : journal à vérifier."
            reload()
            return
        }
        feedback = "Arrivée enregistrée. Position GPS facultative en cours de recherche."
        pendingCapture = PendingCapture(id: id, account: account, departure: false, at: at)
        locationManager.requestCurrentLocation()
        reload()
    }

    private func capturePositionIfAvailable() {
        guard let capture = pendingCapture,
              capture.account == TravelJourneyStoreV2.currentAccount(),
              let location = locationManager.location else { return }
        guard abs(location.timestamp.timeIntervalSince(capture.at)) <= 180 else {
            pendingCapture = nil
            return
        }
        let position = TravelFixV2(
            latitude: location.coordinate.latitude,
            longitude: location.coordinate.longitude,
            accuracyMeters: location.horizontalAccuracy,
            observedAt: location.timestamp
        )
        if position.isUsable &&
            TravelJourneyStoreV2.attachFix(account: capture.account, id: capture.id,
                                           departure: capture.departure, fix: position) {
            feedback = "Position GPS enregistrée pour ce déplacement."
        } else {
            feedback = "Heure conservée ; position GPS indisponible ou imprécise."
        }
        pendingCapture = nil
        reload()
    }

    private func mapsURL() -> URL? {
        let trip = journeys?.last
        let start = trip?.departure
        let end = trip?.arrival
        var parts = URLComponents()
        parts.scheme = "https"
        parts.host = "www.google.com"
        if let start, let end {
            parts.path = "/maps/dir/"
            parts.queryItems = [
                URLQueryItem(name: "api", value: "1"),
                URLQueryItem(name: "origin", value: "\(start.latitude),\(start.longitude)"),
                URLQueryItem(name: "destination", value: "\(end.latitude),\(end.longitude)"),
                URLQueryItem(name: "travelmode", value: "driving")
            ]
        } else if let sample = start ?? end {
            parts.path = "/maps/search/"
            parts.queryItems = [
                URLQueryItem(name: "api", value: "1"),
                URLQueryItem(name: "query", value: "\(sample.latitude),\(sample.longitude)")
            ]
        } else {
            parts.path = "/maps/@"
            parts.queryItems = [
                URLQueryItem(name: "api", value: "1"),
                URLQueryItem(name: "map_action", value: "map")
            ]
        }
        return parts.url
    }
}
