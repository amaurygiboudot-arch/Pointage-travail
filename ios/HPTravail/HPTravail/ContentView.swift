import SwiftUI

struct ContentView: View {
    @EnvironmentObject private var store: WorkStore
    @EnvironmentObject private var locationManager: LocationManager
    @EnvironmentObject private var authManager: AuthManager
    @AppStorage("hp_theme") private var theme = "signature"

    var body: some View {
        TabView {
            todayView
                .tabItem { Label("Aujourd'hui", systemImage: "clock") }
            historyView
                .tabItem { Label("Historique", systemImage: "list.bullet.rectangle") }
            settingsView
                .tabItem { Label("Réglages", systemImage: "gearshape") }
        }
        .tint(accent)
        .alert("Compte Google / Apple", isPresented: Binding(
            get: { authManager.errorMessage != nil },
            set: { if !$0 { authManager.errorMessage = nil } }
        )) {
            Button("OK", role: .cancel) { authManager.errorMessage = nil }
        } message: {
            Text(authManager.errorMessage ?? "")
        }
    }

    private var todayView: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 24) {
                    Text(Date.now.formatted(date: .complete, time: .omitted))
                        .font(.headline)
                    Text(Date.now.formatted(date: .omitted, time: .shortened))
                        .font(.system(size: 52, weight: .bold, design: .rounded))

                    HStack(spacing: 18) {
                        actionButton(title: "ENTRÉE", symbol: "arrow.right.circle.fill", color: .green, disabled: store.isWorking) {
                            store.clockIn()
                        }
                        actionButton(title: store.isPaused ? "REPRISE" : "PAUSE", symbol: "pause.circle.fill", color: .orange, disabled: !store.isWorking) {
                            store.togglePause()
                        }
                        actionButton(title: "SORTIE", symbol: "arrow.left.circle.fill", color: .red, disabled: !store.isWorking) {
                            store.clockOut()
                        }
                    }

                    statusCard
                    if let current = store.currentSession {
                        TimelineView(.periodic(from: .now, by: 1)) { _ in
                            Text("Temps travaillé : \(format(store.workedDuration(for: current)))")
                                .font(.title3.bold())
                        }
                    }
                }
                .padding()
            }
            .navigationTitle("HP Travail")
        }
    }

    private var historyView: some View {
        NavigationStack {
            List(store.sessions.reversed()) { session in
                VStack(alignment: .leading, spacing: 6) {
                    Text(session.entry.formatted(date: .abbreviated, time: .shortened))
                        .font(.headline)
                    if let exit = session.exit {
                        Text("Sortie : \(exit.formatted(date: .omitted, time: .shortened))")
                        Text("Travail : \(format(store.workedDuration(for: session)))")
                    } else {
                        Text("En cours")
                            .foregroundStyle(.green)
                    }
                    if !session.pauses.isEmpty {
                        Text("Pauses : \(session.pauses.count)")
                            .foregroundStyle(.secondary)
                    }
                }
                .padding(.vertical, 4)
            }
            .navigationTitle("Historique")
        }
    }

    private var settingsView: some View {
        NavigationStack {
            Form {
                Section("Compte Google / Apple") {
                    if !authManager.isFirebaseConfigured {
                        Text("Configuration Firebase iOS requise")
                            .foregroundStyle(.secondary)
                    } else {
                        HStack {
                            Text("Google")
                            Spacer()
                            Text(authManager.isGoogleLinked ? "Connecté" : "Non connecté")
                                .foregroundStyle(authManager.isGoogleLinked ? .green : .secondary)
                        }
                        if !authManager.isGoogleLinked {
                            Button("SE CONNECTER AVEC GOOGLE") {
                                authManager.signInWithGoogle()
                            }
                        }

                        HStack {
                            Text("Apple")
                            Spacer()
                            Text(authManager.isAppleLinked ? "Connecté" : "Non connecté")
                                .foregroundStyle(authManager.isAppleLinked ? .green : .secondary)
                        }
                        if !authManager.isAppleLinked {
                            Button("SE CONNECTER AVEC APPLE") {
                                authManager.signInWithApple()
                            }
                        }

                        if let user = authManager.user {
                            Text(user.displayName ?? user.email ?? "Profil HP Travail")
                                .foregroundStyle(.secondary)
                            Button("SE DÉCONNECTER DU PROFIL", role: .destructive) {
                                authManager.signOut()
                            }
                        }
                    }
                }

                Section("Apparence") {
                    Picker("Thème", selection: $theme) {
                        Text("Signature Or").tag("signature")
                        Text("Acier Bleu").tag("blue")
                    }
                }

                Section("Localisation") {
                    Text(locationLabel)
                    Button("Obtenir ma position") {
                        locationManager.requestWhenInUseIfNeeded()
                    }
                    Text("La localisation iPhone est utilisée ponctuellement pendant l'utilisation. HoraTrack ne demande pas d'accès permanent en arrière-plan.")
                        .foregroundStyle(.secondary)
                }

                Section("À propos") {
                    Text("Version iPhone de HP Travail")
                    Text("Google et Apple peuvent être liés séparément ou ensemble au même profil Firebase.")
                        .foregroundStyle(.secondary)
                }
            }
            .navigationTitle("Réglages")
        }
    }

    private var statusCard: some View {
        VStack(spacing: 8) {
            Text("STATUT ACTUEL")
                .font(.caption.bold())
                .foregroundStyle(.secondary)
            Text(store.isWorking ? (store.isPaused ? "EN PAUSE" : "ENTRÉE EN COURS") : "AUCUNE ENTRÉE EN COURS")
                .font(.title3.bold())
        }
        .frame(maxWidth: .infinity)
        .padding()
        .background(.thinMaterial, in: RoundedRectangle(cornerRadius: 18))
    }

    private func actionButton(title: String, symbol: String, color: Color, disabled: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            VStack(spacing: 8) {
                Image(systemName: symbol)
                    .font(.system(size: 48))
                Text(title)
                    .font(.caption.bold())
            }
            .frame(maxWidth: .infinity)
        }
        .buttonStyle(.plain)
        .foregroundStyle(disabled ? .secondary : color)
        .disabled(disabled)
    }

    private var accent: Color { theme == "blue" ? .blue : .orange }

    private var locationLabel: String {
        switch locationManager.authorizationStatus {
        case .authorizedAlways: return "Localisation : autorisée (HoraTrack l'utilise seulement au premier plan)"
        case .authorizedWhenInUse: return "Localisation : autorisée pendant l'utilisation"
        case .denied: return "Localisation : refusée"
        case .restricted: return "Localisation : restreinte"
        default: return "Localisation : non demandée"
        }
    }

    private func format(_ duration: TimeInterval) -> String {
        let total = Int(duration) / 60
        return String(format: "%02dh %02dm", total / 60, total % 60)
    }
}
