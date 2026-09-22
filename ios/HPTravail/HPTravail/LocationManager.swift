import Combine
import CoreLocation
import CoreMotion
import Foundation
import UIKit

struct CelestialTrackingStateV2 {
    let snapshot: CelestialSnapshotV2?
    let locationQuality: CelestialLocationQualityV2
    let locationAge: TimeInterval?
    let locationAccuracyMeters: Double?
    let trueHeadingDegrees: Double?
    let headingQuality: CelestialHeadingQualityV2
    let headingAge: TimeInterval?
    let pitchDegrees: Double?
    let rollDegrees: Double?

    var hasRealDirectionalSky: Bool {
        snapshot != nil
            && locationQuality == .valid
            && trueHeadingDegrees != nil
            && CelestialHeadingPolicyV2.isUsable(headingQuality)
    }

    static let unavailable = CelestialTrackingStateV2(
        snapshot: nil,
        locationQuality: .unavailable,
        locationAge: nil,
        locationAccuracyMeters: nil,
        trueHeadingDegrees: nil,
        headingQuality: .unavailable,
        headingAge: nil,
        pitchDegrees: nil,
        rollDegrees: nil
    )
}

final class LocationManager: NSObject, ObservableObject, CLLocationManagerDelegate {
    private static let registrationFingerprintKey = "horatrack_gps_registration_fingerprint_v2"
    private static let registrationIdKey = "horatrack_gps_registration_id_v2"
    private static let regionPrefix = "horatrack.v2."

    private let manager: CLLocationManager
    private let motionManager: CMMotionManager
    private let defaults: UserDefaults
    private var locationRequestPending = false
    private var registrationFingerprintInFlight: String?
    private var registrationIdInFlight: UUID?
    private var registrationExpectedIdentifiers: Set<String> = []
    private var registrationStartedIdentifiers: Set<String> = []
    private var registrationSuspended = false
    private var refreshTimer: Timer?
    private var latestHeading: CLHeading?
    private var latestMotion: CMDeviceMotion?
    private var latestMotionUptime: TimeInterval?
    /// Dedicated qualified sample for the sky. The published `location`
    /// remains the result of an explicit one-shot request used by pointage and
    /// zone creation; continuous celestial tracking must not overwrite it.
    private var celestialLocation: CLLocation?
    private var celestialTrackingActive = false

    @Published private(set) var authorizationStatus: CLAuthorizationStatus
    @Published private(set) var location: CLLocation?
    @Published private(set) var zones: [GpsZoneV2] = []
    @Published private(set) var configurationReliable = true
    @Published private(set) var automaticEnabled: Bool
    @Published private(set) var pendingEvent: GpsPendingEventV2?
    @Published private(set) var statusMessage = "Pointage GPS désactivé"
    @Published private(set) var celestialState = CelestialTrackingStateV2.unavailable

    var hasFullAccuracy: Bool {
        manager.accuracyAuthorization == .fullAccuracy
    }

    deinit {
        refreshTimer?.invalidate()
        motionManager.stopDeviceMotionUpdates()
        manager.stopUpdatingHeading()
        manager.stopUpdatingLocation()
    }

    init(
        defaults: UserDefaults = .standard,
        manager: CLLocationManager = CLLocationManager(),
        motionManager: CMMotionManager = CMMotionManager()
    ) {
        self.defaults = defaults
        self.manager = manager
        self.motionManager = motionManager
        authorizationStatus = manager.authorizationStatus
        automaticEnabled = defaults.bool(forKey: GpsZoneConfigurationV2.enabledKey)
        super.init()
        manager.delegate = self
        manager.desiredAccuracy = kCLLocationAccuracyHundredMeters
        reloadAndReconcile()
    }

    func requestCurrentLocation() {
        location = nil
        locationRequestPending = true
        switch manager.authorizationStatus {
        case .notDetermined:
            manager.requestWhenInUseAuthorization()
        case .authorizedWhenInUse, .authorizedAlways:
            manager.requestLocation()
        default:
            locationRequestPending = false
            statusMessage = "Autorisation de localisation requise"
        }
    }

    func requestWhenInUseIfNeeded() {
        switch manager.authorizationStatus {
        case .notDetermined:
            manager.requestWhenInUseAuthorization()
        case .authorizedWhenInUse, .authorizedAlways:
            startCelestialSensors()
        default:
            refreshCelestialState()
        }
    }

    func startCelestialTracking() {
        celestialTrackingActive = true
        requestWhenInUseIfNeeded()
    }

    func stopCelestialTracking() {
        celestialTrackingActive = false
        manager.stopUpdatingHeading()
        motionManager.stopDeviceMotionUpdates()
        refreshTimer?.invalidate()
        refreshTimer = nil
        manager.stopUpdatingLocation()
        manager.desiredAccuracy = kCLLocationAccuracyHundredMeters
        manager.distanceFilter = kCLDistanceFilterNone
        latestHeading = nil
        latestMotion = nil
        latestMotionUptime = nil
        celestialLocation = nil
        refreshCelestialState()
    }

    func requestAlways() {
        guard automaticEnabled, configurationReliable, !zones.isEmpty else {
            statusMessage = "Active le pointage GPS et ajoute une zone avant cette autorisation"
            return
        }
        switch manager.authorizationStatus {
        case .notDetermined:
            manager.requestWhenInUseAuthorization()
        case .authorizedWhenInUse:
            manager.requestAlwaysAuthorization()
        case .authorizedAlways:
            retryRegistration()
        default:
            statusMessage = "Autorisation de localisation refusée"
        }
    }

    func setAutomaticEnabled(_ enabled: Bool) {
        defaults.set(enabled, forKey: GpsZoneConfigurationV2.enabledKey)
        automaticEnabled = enabled
        registrationSuspended = false
        reloadAndReconcile(configurationChanged: true)
    }

    @discardableResult
    func addZoneAtCurrentLocation(
        label: String,
        radius: Double,
        employerId: String?
    ) -> Bool {
        let cleanLabel = label.trimmingCharacters(in: .whitespacesAndNewlines)
        guard configurationReliable,
              zones.count < GpsZoneConfigurationV2.maximumZoneCount,
              let location,
              Date().timeIntervalSince(location.timestamp) >= 0,
              Date().timeIntervalSince(location.timestamp) <= 60,
              manager.accuracyAuthorization == .fullAccuracy,
              location.horizontalAccuracy >= 0,
              location.horizontalAccuracy <= min(radius, 100),
              !cleanLabel.isEmpty else {
            requestCurrentLocation()
            return false
        }
        let zone = GpsZoneV2(
            id: UUID(),
            label: cleanLabel,
            latitude: location.coordinate.latitude,
            longitude: location.coordinate.longitude,
            radius: radius,
            employerId: employerId,
            kind: .worksite
        )
        return persistZones(zones + [zone])
    }

    func removeZone(id: UUID) {
        _ = persistZones(zones.filter { $0.id != id })
    }

    func zone(id: UUID) -> GpsZoneV2? {
        zones.first { $0.id == id }
    }

    @discardableResult
    func clearPendingEvent() -> Bool {
        guard case .valid(var state) = GpsStateStoreV2.read(
            defaults.data(forKey: GpsStateStoreV2.key)
        ) else {
            pendingEvent = nil
            return false
        }
        guard !state.pendingEvents.isEmpty else {
            pendingEvent = nil
            return true
        }
        state.pendingEvents.removeFirst()
        if state.pendingEvents.count < GpsPresenceTransitionV2.maximumPendingEventCount {
            state.eventQueueOverflowed = false
        }
        if GpsStateStoreV2.write(state, defaults: defaults) {
            pendingEvent = state.pendingEvents.first
            return true
        }
        suspendRegistration(message: "État GPS non fiable — automatisme suspendu")
        return false
    }

    @discardableResult
    func confirmArrival(eventId: UUID, sessionId: UUID) -> Bool {
        updateStateForCompletedEvent(
            eventId: eventId,
            alreadyCompleted: { state in
                state.confirmedSessionId == sessionId
                    && !state.pendingEvents.contains(where: { $0.id == eventId })
            }
        ) { state, event in
            guard event.kind == .arrival else { return false }
            state.confirmedSessionId = sessionId
            if let nextDeparture = state.pendingEvents.firstIndex(where: {
                $0.kind == .departure && $0.expectedSessionId == nil
            }) {
                state.pendingEvents[nextDeparture].expectedSessionId = sessionId
            }
            return true
        }
    }

    @discardableResult
    func confirmDeparture(eventId: UUID, sessionId: UUID) -> Bool {
        updateStateForCompletedEvent(
            eventId: eventId,
            alreadyCompleted: { state in
                state.confirmedSessionId != sessionId
                    && !state.pendingEvents.contains(where: { $0.id == eventId })
            }
        ) { state, event in
            guard event.kind == .departure,
                  event.expectedSessionId == sessionId,
                  state.confirmedSessionId == nil || state.confirmedSessionId == sessionId else {
                return false
            }
            state.confirmedSessionId = nil
            return true
        }
    }

    @discardableResult
    func reconcileSession(openSessionId: UUID?) -> Bool {
        guard let fingerprint = GpsZoneConfigurationV2.fingerprint(
            enabled: automaticEnabled,
            zones: zones
        ) else {
            pendingEvent = nil
            return true
        }
        guard let stored = validatedState(fingerprint: fingerprint) else {
            statusMessage = "État GPS à vérifier"
            return false
        }
        let reconciled = GpsPresenceTransitionV2.reconcileSession(
            state: transitionState(from: stored),
            openSessionId: openSessionId
        )
        guard reconciled != transitionState(from: stored) else {
            pendingEvent = stored.pendingEvents.first
            return true
        }
        let persisted = persistedState(from: reconciled, fingerprint: fingerprint)
        guard GpsStateStoreV2.write(persisted, defaults: defaults) else {
            suspendRegistration(message: "État GPS non fiable — automatisme suspendu")
            return false
        }
        pendingEvent = persisted.pendingEvents.first
        return true
    }

    func retryRegistration() {
        manager.monitoredRegions
            .filter { $0.identifier.hasPrefix(Self.regionPrefix) }
            .forEach { manager.stopMonitoring(for: $0) }
        clearRegistrationMarkers()
        clearInFlightRegistration()
        registrationSuspended = false
        reloadAndReconcile()
    }

    func resetGpsConfiguration() {
        invalidateRegistration(clearBusinessState: true)
        defaults.removeObject(forKey: GpsZoneConfigurationV2.zonesKey)
        defaults.set(false, forKey: GpsZoneConfigurationV2.enabledKey)
        zones = []
        automaticEnabled = false
        registrationSuspended = false
        location = nil
        let resetSucceeded = GpsZoneConfigurationV2.read(
            defaults.data(forKey: GpsZoneConfigurationV2.zonesKey)
        ) == .missing
            && GpsStateStoreV2.read(defaults.data(forKey: GpsStateStoreV2.key)) == .missing
            && !defaults.bool(forKey: GpsZoneConfigurationV2.enabledKey)
        configurationReliable = resetSucceeded
        statusMessage = resetSucceeded
            ? "Configuration GPS réinitialisée — ajoute une nouvelle zone"
            : "Impossible de réinitialiser la configuration GPS"
    }

    func reloadAndReconcile(configurationChanged: Bool = false) {
        automaticEnabled = defaults.bool(forKey: GpsZoneConfigurationV2.enabledKey)
        switch GpsZoneConfigurationV2.read(
            defaults.data(forKey: GpsZoneConfigurationV2.zonesKey)
        ) {
        case .missing:
            zones = []
            configurationReliable = true
        case .valid(let stored):
            zones = stored
            configurationReliable = true
        case .corrupt:
            zones = []
            configurationReliable = false
        }

        guard configurationReliable else {
            suspendRegistration(message: "Configuration GPS à vérifier — automatisme suspendu")
            pendingEvent = nil
            return
        }

        let fingerprint = GpsZoneConfigurationV2.fingerprint(
            enabled: automaticEnabled,
            zones: zones
        )
        if configurationChanged {
            GpsStateStoreV2.clear(defaults: defaults)
            pendingEvent = nil
        } else if let suspensionMessage = restoreState(expectedFingerprint: fingerprint) {
            suspendRegistration(message: suspensionMessage)
            return
        }

        guard let fingerprint else {
            invalidateRegistration(clearBusinessState: false)
            statusMessage = zones.isEmpty
                ? "Ajoute une zone pour activer le pointage GPS"
                : "Pointage GPS désactivé"
            return
        }
        guard manager.authorizationStatus == .authorizedAlways else {
            invalidateRegistration(clearBusinessState: false)
            statusMessage = "Autorisation Toujours requise pour le pointage GPS"
            return
        }
        guard CLLocationManager.isMonitoringAvailable(for: CLCircularRegion.self) else {
            invalidateRegistration(clearBusinessState: false)
            statusMessage = "Surveillance des zones indisponible sur cet appareil"
            return
        }

        reconcileRegions(fingerprint: fingerprint)
    }

    func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        authorizationStatus = manager.authorizationStatus
        if locationRequestPending,
           (authorizationStatus == .authorizedWhenInUse || authorizationStatus == .authorizedAlways) {
            manager.requestLocation()
        }
        if celestialTrackingActive,
           (authorizationStatus == .authorizedWhenInUse || authorizationStatus == .authorizedAlways) {
            startCelestialSensors()
        } else if authorizationStatus != .authorizedWhenInUse
                    && authorizationStatus != .authorizedAlways {
            manager.stopUpdatingLocation()
            manager.stopUpdatingHeading()
            motionManager.stopDeviceMotionUpdates()
            refreshTimer?.invalidate()
            refreshTimer = nil
            latestHeading = nil
            latestMotion = nil
            latestMotionUptime = nil
            refreshCelestialState()
        }
        registrationSuspended = false
        reloadAndReconcile()
    }

    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        let usableForOneShot = locations.filter { $0.horizontalAccuracy >= 0 }
        if locationRequestPending {
            locationRequestPending = false
            location = usableForOneShot.max(by: { $0.timestamp < $1.timestamp })
        }

        if celestialTrackingActive {
            let candidateIndex = CelestialTrackingPolicyV2.preferredCandidateIndex(
                currentTimestamp: celestialLocation?.timestamp,
                currentAccuracyMeters: celestialLocation?.horizontalAccuracy,
                candidates: locations.map {
                    (timestamp: $0.timestamp, accuracyMeters: $0.horizontalAccuracy)
                },
                now: Date()
            )
            if let candidateIndex {
                celestialLocation = locations[candidateIndex]
            }
        }
        refreshCelestialState()
    }

    func locationManager(_ manager: CLLocationManager, didEnterRegion region: CLRegion) {
        handle(region: region, transition: .enter, occurredAt: Date())
    }

    func locationManager(_ manager: CLLocationManager, didExitRegion region: CLRegion) {
        handle(region: region, transition: .exit, occurredAt: Date())
    }

    func locationManager(
        _ manager: CLLocationManager,
        didDetermineState state: CLRegionState,
        for region: CLRegion
    ) {
        switch state {
        case .inside:
            handle(region: region, transition: .enter, occurredAt: Date())
        case .outside:
            handle(region: region, transition: .exit, occurredAt: Date())
        case .unknown:
            break
        }
    }

    func locationManager(_ manager: CLLocationManager, didUpdateHeading newHeading: CLHeading) {
        guard celestialTrackingActive else { return }
        latestHeading = newHeading
        refreshCelestialState()
    }

    func locationManagerShouldDisplayHeadingCalibration(_ manager: CLLocationManager) -> Bool {
        celestialTrackingActive && (latestHeading?.headingAccuracy ?? -1) < 0
    }

    private func startCelestialSensors() {
        guard celestialTrackingActive else { return }
        manager.desiredAccuracy = kCLLocationAccuracyBest
        manager.distanceFilter = 25
        manager.headingFilter = 1
        manager.startUpdatingLocation()
        updateHeadingOrientation()
        if CLLocationManager.headingAvailable() {
            manager.startUpdatingHeading()
        }
        startMotionIfAvailable()
        if refreshTimer == nil {
            let timer = Timer(timeInterval: 1, repeats: true) { [weak self] _ in
                self?.refreshCelestialState()
            }
            RunLoop.main.add(timer, forMode: .common)
            refreshTimer = timer
        }
        refreshCelestialState()
    }

    private func startMotionIfAvailable() {
        guard motionManager.isDeviceMotionAvailable,
              !motionManager.isDeviceMotionActive else { return }

        let frames = CMMotionManager.availableAttitudeReferenceFrames()
        let frame: CMAttitudeReferenceFrame
        if frames.contains(.xTrueNorthZVertical) {
            frame = .xTrueNorthZVertical
        } else if frames.contains(.xMagneticNorthZVertical) {
            frame = .xMagneticNorthZVertical
        } else {
            frame = .xArbitraryCorrectedZVertical
        }

        motionManager.deviceMotionUpdateInterval = 0.2
        motionManager.startDeviceMotionUpdates(using: frame, to: .main) { [weak self] motion, _ in
            guard let self, self.celestialTrackingActive, let motion else { return }
            self.updateHeadingOrientation()
            self.latestMotion = motion
            self.latestMotionUptime = ProcessInfo.processInfo.systemUptime
            self.refreshCelestialState()
        }
    }

    private func updateHeadingOrientation() {
        switch UIDevice.current.orientation {
        case .portrait:
            manager.headingOrientation = .portrait
        case .portraitUpsideDown:
            manager.headingOrientation = .portraitUpsideDown
        case .landscapeLeft:
            manager.headingOrientation = .landscapeLeft
        case .landscapeRight:
            manager.headingOrientation = .landscapeRight
        default:
            break
        }
    }

    func locationManager(_ manager: CLLocationManager, didStartMonitoringFor region: CLRegion) {
        guard let fingerprint = registrationFingerprintInFlight,
              let registrationId = registrationIdInFlight,
              registrationExpectedIdentifiers.contains(region.identifier),
              GpsZoneConfigurationV2.zoneId(
                  fromRegionIdentifier: region.identifier,
                  fingerprint: fingerprint,
                  registrationId: registrationId
              ) != nil else {
            manager.stopMonitoring(for: region)
            return
        }
        registrationStartedIdentifiers.insert(region.identifier)
        guard registrationStartedIdentifiers == registrationExpectedIdentifiers else { return }

        let registeredRegions = manager.monitoredRegions.filter {
            registrationExpectedIdentifiers.contains($0.identifier)
        }
        guard Set(registeredRegions.map(\.identifier)) == registrationExpectedIdentifiers else {
            suspendRegistration(message: "Activation GPS incomplète — touche Réessayer")
            return
        }
        defaults.set(fingerprint, forKey: Self.registrationFingerprintKey)
        defaults.set(registrationId.uuidString, forKey: Self.registrationIdKey)
        guard defaults.string(forKey: Self.registrationFingerprintKey) == fingerprint,
              defaults.string(forKey: Self.registrationIdKey) == registrationId.uuidString else {
            suspendRegistration(message: "Impossible d'enregistrer l'activation GPS")
            return
        }
        clearInFlightRegistration()
        statusMessage = "Pointage GPS actif — \(zones.count) zone(s)"
        registeredRegions.forEach { manager.requestState(for: $0) }
    }

    func locationManager(
        _ manager: CLLocationManager,
        monitoringDidFailFor region: CLRegion?,
        withError error: Error
    ) {
        guard let region else {
            suspendRegistration(message: "Surveillance GPS interrompue — touche Réessayer")
            return
        }
        if registrationExpectedIdentifiers.contains(region.identifier) {
            suspendRegistration(message: "Une zone GPS n'a pas pu être activée")
            return
        }
        guard isRegistered(region: region) else { return }
        suspendRegistration(message: "Une zone GPS n'est plus surveillée")
    }

    func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        locationRequestPending = false
        if (error as? CLError)?.code == .denied {
            reloadAndReconcile()
        } else {
            statusMessage = "Position actuelle indisponible"
        }
    }

    private func persistZones(_ updated: [GpsZoneV2]) -> Bool {
        guard GpsZoneConfigurationV2.isValid(updated),
              let data = try? JSONEncoder().encode(updated) else {
            return false
        }
        defaults.set(data, forKey: GpsZoneConfigurationV2.zonesKey)
        guard GpsZoneConfigurationV2.read(
            defaults.data(forKey: GpsZoneConfigurationV2.zonesKey)
        ) == .valid(updated) else {
            configurationReliable = false
            return false
        }
        registrationSuspended = false
        reloadAndReconcile(configurationChanged: true)
        return true
    }

    private func reconcileRegions(fingerprint: String) {
        let monitored = manager.monitoredRegions.filter {
            $0.identifier.hasPrefix(Self.regionPrefix)
        }
        let registeredFingerprint = defaults.string(forKey: Self.registrationFingerprintKey)
        let registeredId = defaults.string(forKey: Self.registrationIdKey).flatMap {
            UUID(uuidString: $0)
        }
        let expectedRegisteredIdentifiers = registeredId.map { registrationId in
            Set(zones.map {
                GpsZoneConfigurationV2.regionIdentifier(
                    zoneId: $0.id,
                    fingerprint: fingerprint,
                    registrationId: registrationId
                )
            })
        }
        let monitoredIdentifiers = Set(monitored.map(\.identifier))
        let state = validatedState(fingerprint: fingerprint)
        if let state, state.eventQueueOverflowed {
            pendingEvent = state.pendingEvents.first
            suspendRegistration(
                message: "Trop d'événements GPS à confirmer — automatisme suspendu"
            )
            return
        }
        if let expectedRegisteredIdentifiers,
           monitoredIdentifiers == expectedRegisteredIdentifiers,
           registeredFingerprint == fingerprint,
           state != nil {
            statusMessage = "Pointage GPS actif — \(zones.count) zone(s)"
            return
        }
        if registrationFingerprintInFlight == fingerprint {
            statusMessage = "Activation de \(zones.count) zone(s) GPS"
            return
        }
        guard !registrationSuspended else {
            statusMessage = "Pointage GPS suspendu — touche Réessayer"
            return
        }

        monitored.forEach { manager.stopMonitoring(for: $0) }
        clearRegistrationMarkers()
        clearInFlightRegistration()

        let reconciledState = state ?? GpsPersistedStateV2(
            fingerprint: fingerprint,
            activeZoneIds: [],
            pendingExitZoneIds: [],
            pendingEvents: [],
            confirmedSessionId: nil,
            eventQueueOverflowed: false
        )
        guard GpsStateStoreV2.write(reconciledState, defaults: defaults) else {
            statusMessage = "Impossible d'enregistrer l'état GPS"
            return
        }
        guard !reconciledState.eventQueueOverflowed else {
            registrationSuspended = true
            statusMessage = "Trop d'événements GPS à confirmer — automatisme suspendu"
            return
        }
        pendingEvent = reconciledState.pendingEvents.first

        let registrationId = UUID()
        let expectedIdentifiers = Set(zones.map {
            GpsZoneConfigurationV2.regionIdentifier(
                zoneId: $0.id,
                fingerprint: fingerprint,
                registrationId: registrationId
            )
        })
        registrationFingerprintInFlight = fingerprint
        registrationIdInFlight = registrationId
        registrationExpectedIdentifiers = expectedIdentifiers
        registrationStartedIdentifiers = []

        for zone in zones {
            let maximum = manager.maximumRegionMonitoringDistance
            let radius = maximum > 0 ? min(zone.radius, maximum) : zone.radius
            let region = CLCircularRegion(
                center: CLLocationCoordinate2D(
                    latitude: zone.latitude,
                    longitude: zone.longitude
                ),
                radius: radius,
                identifier: GpsZoneConfigurationV2.regionIdentifier(
                    zoneId: zone.id,
                    fingerprint: fingerprint,
                    registrationId: registrationId
                )
            )
            region.notifyOnEntry = true
            region.notifyOnExit = true
            manager.startMonitoring(for: region)
        }
        statusMessage = "Activation de \(zones.count) zone(s) GPS"
    }

    private func handle(
        region: CLRegion,
        transition: GpsPresenceTransitionV2.Transition,
        occurredAt: Date
    ) {
        guard registrationFingerprintInFlight == nil, !registrationSuspended else { return }
        guard configurationReliable,
              automaticEnabled,
              manager.authorizationStatus == .authorizedAlways,
              let fingerprint = GpsZoneConfigurationV2.fingerprint(
                enabled: automaticEnabled,
                zones: zones
              ),
              defaults.string(forKey: Self.registrationFingerprintKey) == fingerprint,
              let registrationId = defaults.string(forKey: Self.registrationIdKey).flatMap({
                  UUID(uuidString: $0)
              }),
              let zoneId = GpsZoneConfigurationV2.zoneId(
                fromRegionIdentifier: region.identifier,
                fingerprint: fingerprint,
                registrationId: registrationId
              ),
              zones.contains(where: { $0.id == zoneId }),
              let stored = validatedState(fingerprint: fingerprint) else {
            return
        }

        let next = GpsPresenceTransitionV2.plan(
            state: transitionState(from: stored),
            zoneId: zoneId,
            transition: transition,
            occurredAt: occurredAt
        )
        let persisted = persistedState(from: next, fingerprint: fingerprint)
        guard persisted != stored else { return }
        guard GpsStateStoreV2.write(persisted, defaults: defaults) else {
            suspendRegistration(message: "État GPS non fiable — automatisme suspendu")
            return
        }
        pendingEvent = next.pendingEvents.first
        if next.eventQueueOverflowed {
            suspendRegistration(
                message: "Trop d'événements GPS à confirmer — automatisme suspendu"
            )
        }
    }

    private func restoreState(expectedFingerprint: String?) -> String? {
        guard let expectedFingerprint else {
            pendingEvent = nil
            return nil
        }
        let startup = GpsStateValidationV2.startupState(
            read: GpsStateStoreV2.read(defaults.data(forKey: GpsStateStoreV2.key)),
            expectedFingerprint: expectedFingerprint,
            configuredZoneIds: Set(zones.map(\.id))
        )
        switch startup {
        case .resume(let state):
            pendingEvent = state.pendingEvents.first
            return nil
        case .initialize:
            pendingEvent = nil
            return nil
        case .suspend(let state):
            pendingEvent = state?.pendingEvents.first
            if state?.eventQueueOverflowed == true {
                return "Trop d'événements GPS à confirmer — automatisme suspendu"
            }
            return "État GPS à vérifier — automatisme suspendu"
        }
    }

    private func validatedState(fingerprint: String) -> GpsPersistedStateV2? {
        guard case .valid(let state) = GpsStateStoreV2.read(
            defaults.data(forKey: GpsStateStoreV2.key)
        ), state.fingerprint == fingerprint else {
            return nil
        }
        guard isValid(state: state, fingerprint: fingerprint) else { return nil }
        return state
    }

    private func isValid(state: GpsPersistedStateV2, fingerprint: String) -> Bool {
        GpsStateValidationV2.startupState(
            read: .valid(state),
            expectedFingerprint: fingerprint,
            configuredZoneIds: Set(zones.map(\.id))
        ) == .resume(state)
    }

    private func updateStateForCompletedEvent(
        eventId: UUID,
        alreadyCompleted: (GpsPersistedStateV2) -> Bool,
        mutate: (inout GpsPersistedStateV2, GpsPendingEventV2) -> Bool
    ) -> Bool {
        guard let fingerprint = GpsZoneConfigurationV2.fingerprint(
            enabled: automaticEnabled,
            zones: zones
        ) else { return false }

        for _ in 0 ..< 2 {
            guard var state = validatedState(fingerprint: fingerprint) else { break }
            guard let index = state.pendingEvents.firstIndex(where: { $0.id == eventId }) else {
                if alreadyCompleted(state) {
                    pendingEvent = state.pendingEvents.first
                    return true
                }
                break
            }
            let event = state.pendingEvents[index]
            state.pendingEvents.remove(at: index)
            guard mutate(&state, event) else { return false }
            if GpsStateStoreV2.write(state, defaults: defaults) {
                pendingEvent = state.pendingEvents.first
                return true
            }
        }
        suspendRegistration(message: "État GPS non fiable — automatisme suspendu")
        return false
    }

    private func isRegistered(region: CLRegion) -> Bool {
        guard let fingerprint = defaults.string(forKey: Self.registrationFingerprintKey),
              let registrationId = defaults.string(forKey: Self.registrationIdKey).flatMap({
                  UUID(uuidString: $0)
              }),
              let zoneId = GpsZoneConfigurationV2.zoneId(
                  fromRegionIdentifier: region.identifier,
                  fingerprint: fingerprint,
                  registrationId: registrationId
              ) else {
            return false
        }
        return zones.contains(where: { $0.id == zoneId })
    }

    private func invalidateRegistration(clearBusinessState: Bool) {
        manager.monitoredRegions
            .filter { $0.identifier.hasPrefix(Self.regionPrefix) }
            .forEach { manager.stopMonitoring(for: $0) }
        clearRegistrationMarkers()
        clearInFlightRegistration()
        if clearBusinessState {
            GpsStateStoreV2.clear(defaults: defaults)
            pendingEvent = nil
        }
    }

    private func transitionState(from state: GpsPersistedStateV2) -> GpsPresenceTransitionV2.State {
        .init(
            activeZoneIds: state.activeZoneIds,
            pendingExitZoneIds: state.pendingExitZoneIds,
            pendingEvents: state.pendingEvents,
            confirmedSessionId: state.confirmedSessionId,
            eventQueueOverflowed: state.eventQueueOverflowed
        )
    }

    private func persistedState(
        from state: GpsPresenceTransitionV2.State,
        fingerprint: String
    ) -> GpsPersistedStateV2 {
        .init(
            fingerprint: fingerprint,
            activeZoneIds: state.activeZoneIds,
            pendingExitZoneIds: state.pendingExitZoneIds,
            pendingEvents: state.pendingEvents,
            confirmedSessionId: state.confirmedSessionId,
            eventQueueOverflowed: state.eventQueueOverflowed
        )
    }

    private func suspendRegistration(message: String) {
        registrationSuspended = true
        manager.monitoredRegions
            .filter { $0.identifier.hasPrefix(Self.regionPrefix) }
            .forEach { manager.stopMonitoring(for: $0) }
        clearRegistrationMarkers()
        clearInFlightRegistration()
        statusMessage = message
    }

    private func clearRegistrationMarkers() {
        defaults.removeObject(forKey: Self.registrationFingerprintKey)
        defaults.removeObject(forKey: Self.registrationIdKey)
    }

    private func clearInFlightRegistration() {
        registrationFingerprintInFlight = nil
        registrationIdInFlight = nil
        registrationExpectedIdentifiers = []
        registrationStartedIdentifiers = []
    }

    private func refreshCelestialState(at now: Date = Date()) {
        let isAuthorized = authorizationStatus == .authorizedWhenInUse
            || authorizationStatus == .authorizedAlways
        let locationAge = celestialLocation.map { now.timeIntervalSince($0.timestamp) }
        let locationQuality = CelestialTrackingPolicyV2.classify(
            hasPermission: isAuthorized,
            hasLocation: celestialLocation != nil,
            locationAge: locationAge,
            accuracyMeters: celestialLocation?.horizontalAccuracy
        )

        let motionAge = latestMotionUptime.map {
            ProcessInfo.processInfo.systemUptime - $0
        }
        let headingAge = latestHeading.map { now.timeIntervalSince($0.timestamp) }
        let combinedHeadingAge: TimeInterval?
        if let headingAge, let motionAge {
            combinedHeadingAge = max(headingAge, motionAge)
        } else {
            combinedHeadingAge = nil
        }

        let trueHeading = latestHeading.flatMap { sample -> Double? in
            guard sample.trueHeading.isFinite, sample.trueHeading >= 0 else { return nil }
            return sample.trueHeading
        }
        let reportedUnreliable = latestHeading.map { $0.headingAccuracy < 0 } ?? true
        let headingQuality = CelestialHeadingPolicyV2.classify(
            hasOrientation: trueHeading != nil && latestMotion != nil,
            headingAge: combinedHeadingAge,
            sensorReportedUnreliable: reportedUnreliable,
            headingAccuracyDegrees: latestHeading?.headingAccuracy
        )

        var snapshot: CelestialSnapshotV2?
        if locationQuality == .valid, let location = celestialLocation {
            snapshot = try? DefaultCelestialEngineV2.snapshot(
                latitudeDegrees: location.coordinate.latitude,
                longitudeDegrees: location.coordinate.longitude,
                date: now,
                observerAltitudeMeters: location.verticalAccuracy >= 0 ? location.altitude : 0
            )
        }

        let radiansToDegrees = 180.0 / Double.pi
        celestialState = CelestialTrackingStateV2(
            snapshot: snapshot,
            locationQuality: locationQuality,
            locationAge: locationAge,
            locationAccuracyMeters: celestialLocation?.horizontalAccuracy,
            trueHeadingDegrees: CelestialHeadingPolicyV2.isUsable(headingQuality) ? trueHeading : nil,
            headingQuality: headingQuality,
            headingAge: combinedHeadingAge,
            pitchDegrees: latestMotion.map { $0.attitude.pitch * radiansToDegrees },
            rollDegrees: latestMotion.map { $0.attitude.roll * radiansToDegrees }
        )
    }
}
