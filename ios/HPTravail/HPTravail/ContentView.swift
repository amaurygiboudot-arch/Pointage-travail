import SwiftUI

struct ContentView: View {
    @EnvironmentObject private var store: WorkStoreV2
    @EnvironmentObject private var locationManager: LocationManager
    @EnvironmentObject private var authManager: AuthManager
    @AppStorage("hp_theme") private var theme = "signature"
    @State private var showPausePaymentChoice = false
    @State private var showManualEntry = false
    @State private var pointageCompanySelection = ActiveSalaryCompanySelectionV2.resolve()
    @State private var selectedPointageCompanyId = ""

    var body: some View {
        TabView {
            todayView
                .tabItem { Label("Aujourd'hui", systemImage: "clock") }
            historyView
                .tabItem { Label("Historique", systemImage: "list.bullet.rectangle") }
            SalaryV2View()
                .tabItem { Label("Salaire", systemImage: "eurosign.circle") }
            settingsView
                .tabItem { Label("Réglages", systemImage: "gearshape") }
        }
        .tint(accent)
        .sheet(isPresented: $showManualEntry) {
            ManualEntrySheetV2()
        }
        .confirmationDialog(
            "Cette pause est-elle rémunérée ?",
            isPresented: $showPausePaymentChoice,
            titleVisibility: .visible
        ) {
            Button("Pause rémunérée") {
                store.togglePause(paid: true)
            }
            Button("Pause non rémunérée") {
                store.togglePause(paid: false)
            }
            Button("Annuler", role: .cancel) {}
        } message: {
            Text("HoraTrack ne déduit jamais une pause sans connaître explicitement son statut payé/non payé.")
        }
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

                    pointageEmployerCard

                    HStack(spacing: 18) {
                        actionButton(
                            title: "ENTRÉE",
                            symbol: "arrow.right.circle.fill",
                            color: .green,
                            disabled: !store.storageReliable || store.isWorking || pointageCompanySelection.requiresExplicitSelection
                        ) {
                            clockInWithConfirmedEmployer()
                        }
                        actionButton(title: store.isPaused ? "REPRISE" : "PAUSE", symbol: "pause.circle.fill", color: .orange, disabled: !store.storageReliable || !store.isWorking) {
                            if store.isPaused {
                                if store.currentPauseNeedsQualification {
                                    showPausePaymentChoice = true
                                } else {
                                    store.togglePause()
                                }
                            } else {
                                showPausePaymentChoice = true
                            }
                        }
                        actionButton(title: "SORTIE", symbol: "arrow.left.circle.fill", color: .red, disabled: !store.storageReliable || !store.isWorking) {
                            if store.currentPauseNeedsQualification {
                                showPausePaymentChoice = true
                            } else {
                                store.clockOut()
                            }
                        }
                    }

                    statusCard
                    Button {
                        showManualEntry = true
                    } label: {
                        Label("Ajouter des heures manuellement", systemImage: "square.and.pencil")
                            .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.bordered)
                    .disabled(!store.storageReliable)
                    if let current = store.currentSession {
                        TimelineView(.periodic(from: .now, by: 1)) { context in
                            Text(paidTimeLabel(for: current, until: context.date))
                                .font(.title3.bold())
                        }
                    }
                }
                .padding()
            }
            .navigationTitle("HP Travail")
            .onAppear {
                refreshPointageCompanySelection()
            }
        }
    }

    private var historyView: some View {
        NavigationStack {
            Group {
                if store.storageReliable {
                    List(store.sessions.reversed()) { session in
                        VStack(alignment: .leading, spacing: 6) {
                            Text(session.entry.formatted(date: .abbreviated, time: .shortened))
                                .font(.headline)
                            if let exit = session.exit {
                                Text("Sortie : \(exit.formatted(date: .omitted, time: .shortened))")
                                Text(paidTimeLabel(for: session, until: exit))
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
                } else {
                    VStack(spacing: 12) {
                        Image(systemName: "exclamationmark.triangle")
                            .font(.largeTitle)
                            .foregroundStyle(.orange)
                        Text("Données à vérifier")
                            .font(.headline)
                        Text("L'historique HoraTrack est illisible. Aucun nouveau pointage ne sera enregistré tant qu'il n'est pas réparé.")
                            .multilineTextAlignment(.center)
                            .foregroundStyle(.secondary)
                    }
                    .padding()
                }
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
                    Button("Autoriser la localisation") {
                        locationManager.requestWhenInUseIfNeeded()
                    }
                    Button("Autoriser en arrière-plan") {
                        locationManager.requestAlways()
                    }
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

    private var pointageEmployerCard: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("ENTREPRISE DU POINTAGE")
                .font(.caption.bold())
                .foregroundStyle(.secondary)

            if !pointageCompanySelection.reliable {
                Label("Stockage des entreprises à vérifier", systemImage: "exclamationmark.triangle")
                    .foregroundStyle(.orange)
                Text("Le pointage reste disponible sans rattachement d'entreprise, mais ces heures ne seront pas attribuées automatiquement au Salaire V2.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            } else if pointageCompanySelection.companies.isEmpty {
                Text("Aucune entreprise Salaire V2 confirmée")
                    .font(.headline)
                Text("Le prochain pointage restera sans entreprise. Vous pourrez continuer à suivre votre temps sans inventer un employeur.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            } else {
                Picker("Entreprise", selection: $selectedPointageCompanyId) {
                    if pointageCompanySelection.companies.count > 1 {
                        Text("Choisir une entreprise").tag("")
                    }
                    ForEach(pointageCompanySelection.companies) { company in
                        Text(company.name.isEmpty ? company.id : company.name)
                            .tag(company.id)
                    }
                }
                .onChange(of: selectedPointageCompanyId) { newValue in
                    selectPointageCompany(newValue)
                }

                if pointageCompanySelection.requiresExplicitSelection {
                    Label("Choisissez l'entreprise avant l'entrée", systemImage: "exclamationmark.circle")
                        .font(.footnote)
                        .foregroundStyle(.orange)
                }
            }

            Text("Ce choix s'applique uniquement aux nouveaux pointages. L'historique existant n'est jamais réattribué automatiquement.")
                .font(.caption)
                .foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding()
        .background(.thinMaterial, in: RoundedRectangle(cornerRadius: 18))
    }

    private var statusCard: some View {
        VStack(spacing: 8) {
            Text("STATUT ACTUEL")
                .font(.caption.bold())
                .foregroundStyle(.secondary)
            Text(currentStatusText)
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

    private var currentStatusText: String {
        if !store.storageReliable { return "DONNÉES À VÉRIFIER" }
        if store.isWorking { return store.isPaused ? "EN PAUSE" : "ENTRÉE EN COURS" }
        return "AUCUNE ENTRÉE EN COURS"
    }

    private var locationLabel: String {
        switch locationManager.authorizationStatus {
        case .authorizedAlways: return "Localisation : toujours autorisée"
        case .authorizedWhenInUse: return "Localisation : autorisée pendant l'utilisation"
        case .denied: return "Localisation : refusée"
        case .restricted: return "Localisation : restreinte"
        default: return "Localisation : non demandée"
        }
    }

    private func refreshPointageCompanySelection() {
        let latest = ActiveSalaryCompanySelectionV2.resolve()
        pointageCompanySelection = latest
        selectedPointageCompanyId = latest.activeCompany?.id ?? ""
    }

    private func selectPointageCompany(_ companyId: String) {
        let id = companyId.trimmingCharacters(in: .whitespacesAndNewlines)
        if id.isEmpty {
            ActiveSalaryCompanySelectionV2.clear()
        } else {
            _ = ActiveSalaryCompanySelectionV2.select(companyId: id)
        }
        refreshPointageCompanySelection()
    }

    private func clockInWithConfirmedEmployer() {
        let latest = ActiveSalaryCompanySelectionV2.resolve()
        pointageCompanySelection = latest
        selectedPointageCompanyId = latest.activeCompany?.id ?? ""
        guard !latest.requiresExplicitSelection else { return }
        store.clockIn(employerId: latest.activeCompany?.id)
    }

    private func paidTimeLabel(for session: WorkSession, until endDate: Date) -> String {
        let assessment = store.paidTimeAssessment(for: session, until: endDate)
        guard assessment.reliable else { return "Temps payé : À confirmer" }
        return "Temps payé : \(format(assessment.paidDuration))"
    }

    private func format(_ duration: TimeInterval) -> String {
        let total = Int(duration) / 60
        return String(format: "%02dh %02dm", total / 60, total % 60)
    }
}

private struct ManualEntrySheetV2: View {
    @Environment(\.dismiss) private var dismiss
    @EnvironmentObject private var store: WorkStoreV2
    @State private var day = Date()
    @State private var startTime = Calendar.current.date(
        bySettingHour: 8,
        minute: 0,
        second: 0,
        of: Date()
    ) ?? Date()
    @State private var endTime = Calendar.current.date(
        bySettingHour: 16,
        minute: 0,
        second: 0,
        of: Date()
    ) ?? Date()
    @State private var selectedCompanyId = ""
    @State private var placeLabel = ""
    @State private var errorMessage: String?
    @State private var companies = SalaryCompanyStoreV2.readConfirmed()

    var body: some View {
        NavigationStack {
            Form {
                Section("Plage oubliée") {
                    DatePicker("Date", selection: $day, displayedComponents: .date)
                    DatePicker("Début", selection: $startTime, displayedComponents: .hourAndMinute)
                    DatePicker("Fin", selection: $endTime, displayedComponents: .hourAndMinute)
                    Text("Une heure de fin antérieure au début est enregistrée le lendemain.")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }

                Section("Entreprise") {
                    if companies.reliable {
                        Picker("Entreprise", selection: $selectedCompanyId) {
                            Text("Sans entreprise / autre").tag("")
                            ForEach(companies.companies) { company in
                                Text(company.name.isEmpty ? company.id : company.name)
                                    .tag(company.id)
                            }
                        }
                    } else {
                        Label("Stockage des entreprises à vérifier", systemImage: "exclamationmark.triangle")
                            .foregroundStyle(.orange)
                    }
                    TextField("Lieu / client (facultatif)", text: $placeLabel)
                }

                if let errorMessage {
                    Section {
                        Text(errorMessage)
                            .foregroundStyle(.red)
                    }
                }
            }
            .navigationTitle("Saisie manuelle V2")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Annuler") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Ajouter") { add() }
                        .disabled(!companies.reliable || !store.storageReliable)
                }
            }
        }
    }

    private func add() {
        guard companies.reliable else {
            errorMessage = "Vérifie d'abord le stockage des entreprises."
            return
        }
        guard let range = ManualSessionPolicyV2.normalizedRange(
            day: day,
            startTime: startTime,
            endTime: endTime
        ) else {
            errorMessage = "Les heures sont invalides ou identiques."
            return
        }
        let employerId = selectedCompanyId.isEmpty ? nil : selectedCompanyId
        guard employerId == nil || companies.companies.contains(where: { $0.id == employerId }) else {
            errorMessage = "L'entreprise sélectionnée n'est plus disponible."
            return
        }
        guard store.addManualSession(
            entry: range.entry,
            exit: range.exit,
            employerId: employerId,
            placeLabel: placeLabel
        ) else {
            errorMessage = "Plage non ajoutée : doublon, données invalides ou historique à vérifier."
            return
        }
        dismiss()
    }
}
