import SwiftUI

struct ContentView: View {
    private enum ClockEmployerChoice: Hashable {
        case unresolved
        case unassigned
        case employer(String)
    }

    @EnvironmentObject private var store: WorkStoreV2
    @EnvironmentObject private var locationManager: LocationManager
    @EnvironmentObject private var authManager: AuthManager
    @Environment(\.scenePhase) private var scenePhase
    @AppStorage("hp_theme") private var theme = "signature"
    @AppStorage(CelestialGlobeModeV2.preferenceKey) private var celestialGlobeMode = CelestialGlobeModeV2.local.rawValue
    @State private var showPausePaymentChoice = false
    @State private var showManualEntry = false
    @State private var showGpsZoneEditor = false
    @State private var showGpsConfirmation = false
    @State private var clockCompanies = SalaryCompanyStoreV2.readConfirmed()
    @State private var clockEmployerChoice: ClockEmployerChoice = .unresolved
    @State private var clockInFeedback: String?
    @State private var gpsFeedback: String?
    @State private var homeTabBarVisible = true

    var body: some View {
        TabView {
            CelestialHomeView(tabBarVisible: $homeTabBarVisible)
                .tabItem { Label("Accueil", systemImage: "globe.europe.africa.fill") }
            todayView
                .tabItem { Label("Pointage", systemImage: "clock") }
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
        .sheet(isPresented: $showGpsZoneEditor) {
            GpsZoneEditorSheetV2()
        }
        .confirmationDialog(
            gpsConfirmationTitle,
            isPresented: $showGpsConfirmation,
            titleVisibility: .visible
        ) {
            if let event = locationManager.pendingEvent {
                if event.kind == .arrival {
                    ForEach(event.zoneIds, id: \.self) { zoneId in
                        if let zone = locationManager.zone(id: zoneId) {
                            Button("Confirmer l'entrée — \(zone.label)") {
                                confirmGpsArrival(event: event, zone: zone)
                            }
                        }
                    }
                } else {
                    Button("Confirmer la sortie", role: .destructive) {
                        confirmGpsDeparture(event: event)
                    }
                }
                Button("Ignorer cet événement", role: .destructive) {
                    _ = locationManager.reconcileSession(
                        openSessionId: store.currentSession?.id
                    )
                    locationManager.clearPendingEvent()
                }
            }
            Button("Plus tard", role: .cancel) {}
        } message: {
            Text("La présence GPS reste un indice. HoraTrack n'enregistre aucun temps payé sans ta confirmation.")
        }
        .onAppear {
            _ = locationManager.reconcileSession(openSessionId: store.currentSession?.id)
            showGpsConfirmation = locationManager.pendingEvent != nil
        }
        .onChange(of: scenePhase) { phase in
            guard phase == .active else { return }
            _ = locationManager.reconcileSession(openSessionId: store.currentSession?.id)
        }
        .onChange(of: locationManager.pendingEvent?.id) { pendingId in
            showGpsConfirmation = pendingId != nil
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

                    clockEmployerCard

                    HStack(spacing: 18) {
                        actionButton(
                            title: "ENTRÉE",
                            symbol: "arrow.right.circle.fill",
                            color: .green,
                            disabled: !store.storageReliable || store.isWorking || clockEmployerChoice == .unresolved
                        ) {
                            clockInFeedback = nil
                            let employerId: String?
                            switch clockEmployerChoice {
                            case .unresolved:
                                clockInFeedback = "Choisis l'entreprise de ce pointage."
                                return
                            case .unassigned:
                                employerId = nil
                            case .employer(let id):
                                employerId = id
                            }

                            let didClockIn = store.clockIn(employerId: employerId)
                            refreshClockEmployerSelection()
                            if !didClockIn {
                                clockInFeedback = "Entrée non enregistrée : entreprise ou historique à vérifier."
                            }
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
                                if store.clockOut() {
                                    if !locationManager.reconcileSession(openSessionId: nil) {
                                        gpsFeedback = "Sortie enregistrée, mais le suivi GPS est à vérifier."
                                    }
                                }
                                refreshClockEmployerSelection()
                            }
                        }
                    }

                    if let clockInFeedback {
                        Text(clockInFeedback)
                            .font(.footnote)
                            .foregroundStyle(.orange)
                    }
                    if let gpsFeedback {
                        Text(gpsFeedback)
                            .font(.footnote)
                            .foregroundStyle(.orange)
                    }

                    statusCard
                    if locationManager.pendingEvent != nil {
                        Button {
                            showGpsConfirmation = true
                        } label: {
                            Label("Événement GPS à confirmer", systemImage: "location.circle")
                                .frame(maxWidth: .infinity)
                        }
                        .buttonStyle(.borderedProminent)
                    }
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
                refreshClockEmployerSelection()
            }
        }
    }

    @ViewBuilder
    private var clockEmployerCard: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("ENTREPRISE DU POINTAGE")
                .font(.caption.bold())
                .foregroundStyle(.secondary)

            if let current = store.currentSession {
                Text(activeEmployerLabel(for: current))
                    .fontWeight(.semibold)
                Text("Entreprise enregistrée dans le pointage en cours. Le prochain pointage demandera de nouveau un choix si plusieurs entreprises sont configurées.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            } else if !clockCompanies.reliable {
                Label("Stockage entreprises à vérifier : ce pointage restera sans entreprise.", systemImage: "exclamationmark.triangle")
                    .font(.footnote)
                    .foregroundStyle(.orange)
            } else if clockCompanies.companies.isEmpty {
                Text("Sans entreprise / autre")
                    .fontWeight(.semibold)
                Text("Aucune entreprise confirmée : le pointage reste utilisable et n'est rattaché à personne.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            } else {
                Picker("Entreprise", selection: $clockEmployerChoice) {
                    if clockCompanies.companies.count > 1 {
                        Text("Choisir…").tag(ClockEmployerChoice.unresolved)
                    }
                    Text("Sans entreprise / autre").tag(ClockEmployerChoice.unassigned)
                    ForEach(clockCompanies.companies) { company in
                        Text(salaryCompanyLabel(company))
                            .tag(ClockEmployerChoice.employer(company.id))
                    }
                }
                .pickerStyle(.menu)

                if clockCompanies.companies.count > 1 && clockEmployerChoice == .unresolved {
                    Text("Plusieurs entreprises sont configurées : choisis explicitement celle de ce pointage.")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding()
        .background(.thinMaterial, in: RoundedRectangle(cornerRadius: 18))
    }

    private func activeEmployerLabel(for session: WorkSession) -> String {
        guard let employerId = session.employerId else { return "Sans entreprise / autre" }
        if clockCompanies.reliable,
           let company = clockCompanies.companies.first(where: { $0.id == employerId }) {
            return salaryCompanyLabel(company)
        }
        return "Entreprise enregistrée — \(employerId)"
    }

    private func refreshClockEmployerSelection() {
        let latest = SalaryCompanyStoreV2.readConfirmed()
        clockCompanies = latest
        clockInFeedback = nil

        guard latest.reliable else {
            clockEmployerChoice = .unassigned
            return
        }
        switch latest.companies.count {
        case 0:
            clockEmployerChoice = .unassigned
        case 1:
            clockEmployerChoice = .employer(latest.companies[0].id)
        default:
            clockEmployerChoice = .unresolved
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
                            if let employerId = session.employerId {
                                Text("Entreprise : \(employerId)")
                                    .foregroundStyle(.secondary)
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

                Section("Système céleste") {
                    Picker("Mode du globe", selection: $celestialGlobeMode) {
                        Text("Local").tag(CelestialGlobeModeV2.local.rawValue)
                        Text("Monde").tag(CelestialGlobeModeV2.world.rawValue)
                    }
                    .pickerStyle(.segmented)
                    Text(
                        celestialGlobeMode == CelestialGlobeModeV2.world.rawValue
                            ? "Monde : le terminateur réel reste au centre pour voir ensemble la partie éclairée et la partie nocturne."
                            : "Local : le globe reste centré sur votre position GPS."
                    )
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    if let endpoint = Bundle.main.object(
                        forInfoDictionaryKey: "CelestialWeatherEndpoint"
                    ) as? String,
                       endpoint.contains("open-meteo.com") {
                        Text("Données météo : Open-Meteo • CC BY 4.0")
                            .font(.caption2)
                            .foregroundStyle(.secondary)
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
                    Text(locationManager.statusMessage)
                        .foregroundStyle(.secondary)
                    Toggle(
                        "Pointage GPS automatique",
                        isOn: Binding(
                            get: { locationManager.automaticEnabled },
                            set: { locationManager.setAutomaticEnabled($0) }
                        )
                    )
                    Button("Obtenir ma position actuelle") {
                        locationManager.requestCurrentLocation()
                    }
                    Button("Autoriser en arrière-plan") {
                        locationManager.requestAlways()
                    }
                    .disabled(
                        !locationManager.automaticEnabled || locationManager.zones.isEmpty
                    )
                    Button("Réessayer l'activation GPS") {
                        locationManager.retryRegistration()
                    }
                    .disabled(
                        !locationManager.automaticEnabled || locationManager.zones.isEmpty
                    )
                    if !locationManager.configurationReliable {
                        Button("Réinitialiser la configuration GPS", role: .destructive) {
                            locationManager.resetGpsConfiguration()
                        }
                        Text("Cette action efface les zones et événements GPS illisibles, puis désactive l'automatisme.")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                    ForEach(locationManager.zones) { zone in
                        HStack {
                            VStack(alignment: .leading) {
                                Text(zone.label)
                                Text("Poste • rayon \(Int(zone.radius)) m")
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                            }
                            Spacer()
                            Button(role: .destructive) {
                                locationManager.removeZone(id: zone.id)
                            } label: {
                                Image(systemName: "trash")
                            }
                        }
                    }
                    Button("Ajouter une zone Poste ici") {
                        locationManager.requestCurrentLocation()
                        showGpsZoneEditor = true
                    }
                    .disabled(
                        !locationManager.configurationReliable
                            || locationManager.zones.count >= GpsZoneConfigurationV2.maximumZoneCount
                    )
                    Text("Maximum 10 zones. Les parkings, pauses et zones candidates ne sont pas encore automatisés sur iPhone.")
                        .font(.caption)
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

    private var gpsConfirmationTitle: String {
        switch locationManager.pendingEvent?.kind {
        case .arrival: return "Présence détectée dans une zone Poste"
        case .departure: return "Sortie de toutes les zones Poste détectée"
        case nil: return "Événement GPS"
        }
    }

    private func confirmGpsArrival(event: GpsPendingEventV2, zone: GpsZoneV2) {
        guard locationManager.reconcileSession(openSessionId: store.currentSession?.id) else {
            gpsFeedback = "État GPS à vérifier avant de confirmer cet événement."
            return
        }
        guard !store.isWorking else {
            gpsFeedback = "Une entrée est déjà en cours : événement GPS ignoré."
            locationManager.clearPendingEvent()
            return
        }
        if store.clockIn(
            at: event.occurredAt,
            employerId: zone.employerId,
            placeLabel: zone.label
        ), let sessionId = store.currentSession?.id {
            if locationManager.confirmArrival(eventId: event.id, sessionId: sessionId) {
                gpsFeedback = nil
            } else {
                if store.rollbackClockIn(expectedSessionId: sessionId) {
                    _ = locationManager.reconcileSession(openSessionId: nil)
                    gpsFeedback = "Entrée annulée : l'état GPS n'a pas pu être confirmé."
                } else {
                    gpsFeedback = "Entrée enregistrée, mais les données sont à vérifier."
                }
            }
            refreshClockEmployerSelection()
        } else {
            gpsFeedback = "Entrée GPS non enregistrée : entreprise ou historique à vérifier."
        }
    }

    private func confirmGpsDeparture(event: GpsPendingEventV2) {
        guard locationManager.reconcileSession(openSessionId: store.currentSession?.id) else {
            gpsFeedback = "État GPS à vérifier avant de confirmer cet événement."
            return
        }
        guard let currentSession = store.currentSession else {
            gpsFeedback = "Aucune entrée en cours : sortie GPS ignorée."
            locationManager.clearPendingEvent()
            return
        }
        if store.currentPauseNeedsQualification {
            gpsFeedback = "Qualifie d'abord la pause en cours avant de confirmer la sortie."
            showPausePaymentChoice = true
            return
        }
        guard event.expectedSessionId == currentSession.id else {
            gpsFeedback = "Cette sortie GPS ne correspond pas au pointage en cours."
            return
        }
        if store.clockOut(
            at: event.occurredAt,
            expectedSessionId: currentSession.id
        ) {
            if locationManager.confirmDeparture(
                eventId: event.id,
                sessionId: currentSession.id
            ) {
                gpsFeedback = nil
            } else {
                _ = locationManager.reconcileSession(openSessionId: nil)
                gpsFeedback = "Sortie enregistrée, mais le suivi GPS est à vérifier."
            }
            refreshClockEmployerSelection()
        } else {
            gpsFeedback = "Sortie GPS non enregistrée : chronologie ou historique à vérifier."
        }
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

private func salaryCompanyLabel(_ company: SalaryCompanyV2) -> String {
    let name = company.name.trimmingCharacters(in: .whitespacesAndNewlines)
    let siret = company.siret.trimmingCharacters(in: .whitespacesAndNewlines)
    let details = siret.isEmpty ? company.id : "SIRET \(siret) • \(company.id)"
    return name.isEmpty ? details : "\(name) — \(details)"
}

private struct GpsZoneEditorSheetV2: View {
    @Environment(\.dismiss) private var dismiss
    @EnvironmentObject private var locationManager: LocationManager
    @State private var label = ""
    @State private var radius = 150.0
    @State private var selectedCompanyId = ""
    @State private var companies = SalaryCompanyStoreV2.readConfirmed()
    @State private var errorMessage: String?

    var body: some View {
        NavigationStack {
            Form {
                Section("Zone Poste") {
                    TextField("Nom du lieu", text: $label)
                    Slider(value: $radius, in: 50 ... 1_000, step: 10)
                    Text("Rayon : \(Int(radius)) m")
                        .foregroundStyle(.secondary)
                    if let location = locationManager.location {
                        Text(
                            String(
                                format: "Position prête : %.5f, %.5f",
                                location.coordinate.latitude,
                                location.coordinate.longitude
                            )
                        )
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        Text("Précision estimée : \(Int(location.horizontalAccuracy)) m")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    } else {
                        Label("Position actuelle en attente", systemImage: "location")
                            .foregroundStyle(.orange)
                    }
                    if !locationManager.hasFullAccuracy {
                        Label(
                            "Active la localisation précise dans Réglages iOS pour créer la zone.",
                            systemImage: "scope"
                        )
                        .font(.caption)
                        .foregroundStyle(.orange)
                    }
                }

                Section("Entreprise") {
                    if companies.reliable {
                        Picker("Entreprise", selection: $selectedCompanyId) {
                            Text("Sans entreprise / autre").tag("")
                            ForEach(companies.companies) { company in
                                Text(salaryCompanyLabel(company)).tag(company.id)
                            }
                        }
                    } else {
                        Label("Stockage entreprises à vérifier", systemImage: "exclamationmark.triangle")
                            .foregroundStyle(.orange)
                    }
                }

                if let errorMessage {
                    Section {
                        Text(errorMessage).foregroundStyle(.red)
                    }
                }
            }
            .navigationTitle("Nouvelle zone GPS")
            .navigationBarTitleDisplayMode(.inline)
            .onAppear {
                companies = SalaryCompanyStoreV2.readConfirmed()
                locationManager.requestCurrentLocation()
            }
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Annuler") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Ajouter") { add() }
                        .disabled(
                            label.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                                || locationManager.location == nil
                                || !companies.reliable
                        )
                }
            }
        }
    }

    private func add() {
        let employerId = selectedCompanyId.isEmpty ? nil : selectedCompanyId
        guard employerId == nil || companies.companies.contains(where: { $0.id == employerId }) else {
            errorMessage = "L'entreprise sélectionnée n'est plus disponible."
            return
        }
        guard locationManager.addZoneAtCurrentLocation(
            label: label,
            radius: radius,
            employerId: employerId
        ) else {
            errorMessage = "Zone non enregistrée : position ou configuration à vérifier."
            return
        }
        dismiss()
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
                                Text(salaryCompanyLabel(company))
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
