import Foundation
import SwiftUI
import UIKit

struct CelestialHomeView: View {
    @Binding private var tabBarVisible: Bool
    @EnvironmentObject private var locationManager: LocationManager
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.openURL) private var openURL
    @AppStorage(CelestialGlobeModeV2.preferenceKey) private var globeModeRaw = CelestialGlobeModeV2.local.rawValue
    @State private var isVisible = false
    @State private var tabBarHideTask: Task<Void, Never>?
    @StateObject private var weather = CelestialWeatherClientV2()

    init(tabBarVisible: Binding<Bool> = .constant(true)) {
        _tabBarVisible = tabBarVisible
    }

    var body: some View {
        NavigationStack {
            ZStack {
                homeSkyBase
                    .ignoresSafeArea()

                CelestialStarFieldViewV2(
                    state: locationManager.celestialState,
                    presentation: .fullScreen,
                    renderState: celestialRenderState
                )
                .ignoresSafeArea()

                CelestialCloudLayerV2(
                    renderState: celestialRenderState
                )
                .ignoresSafeArea()

                GeometryReader { viewport in
                    ScrollView {
                        VStack(spacing: 18) {
                            ZStack {
                                skyPanel
                                    .frame(maxWidth: 470)
                                    .position(
                                        x: viewport.size.width / 2,
                                        y: viewport.size.height / 2
                                    )

                                Text(Date.now.formatted(date: .complete, time: .shortened))
                                    .font(.headline)
                                    .foregroundStyle(homeForegroundColor)
                                    .shadow(
                                        color: homeUsesDarkText
                                            ? .white.opacity(0.55)
                                            : .black.opacity(0.65),
                                        radius: 2,
                                        y: 1
                                    )
                                    .multilineTextAlignment(.center)
                                    .frame(maxWidth: .infinity, alignment: .top)
                                    .padding(.top, 8)
                            }
                            .frame(
                                width: viewport.size.width,
                                height: max(520, viewport.size.height)
                            )

                            detailsPanel
                                .frame(maxWidth: 600)
                        }
                        .frame(maxWidth: .infinity)
                        .padding()
                    }
                }
            }
            .navigationTitle("Accueil")
            .toolbar(tabBarVisible ? .visible : .hidden, for: .tabBar)
            .simultaneousGesture(
                DragGesture(minimumDistance: 0)
                    .onChanged { _ in
                        revealTabBarAndScheduleHide()
                    }
            )
            .onAppear {
                isVisible = true
                revealTabBarAndScheduleHide()
                if scenePhase == .active {
                    locationManager.startCelestialTracking()
                }
            }
            .onDisappear {
                isVisible = false
                tabBarHideTask?.cancel()
                tabBarHideTask = nil
                tabBarVisible = true
                locationManager.stopCelestialTracking()
            }
            .onChange(of: scenePhase) { phase in
                if phase == .active && isVisible {
                    locationManager.startCelestialTracking()
                } else {
                    locationManager.stopCelestialTracking()
                }
            }
            .task(id: weatherRequestKey) {
                guard isVisible,
                      let snapshot = locationManager.celestialState.snapshot else {
                    return
                }
                await weather.refreshIfNeeded(snapshot: snapshot)
            }
        }
    }

    private var qualifiedWeather: CelestialWeatherStateV2? {
        let state = locationManager.celestialState
        guard state.locationQuality == .valid,
              let snapshot = state.snapshot,
              let fresh = weather.freshState,
              fresh.matches(snapshot: snapshot) else {
            return nil
        }
        return fresh
    }

    private var celestialRenderState: CelestialRenderStateV2? {
        let state = locationManager.celestialState
        guard state.locationQuality == .valid,
              let snapshot = state.snapshot else {
            return nil
        }
        return CelestialRenderStateFactoryV2.build(
            snapshot: snapshot,
            weather: qualifiedWeather,
            ambient: CelestialAmbientLightV2.currentState,
            orientationQuality: state.headingQuality
        )
    }

    private var homeUsesDarkText: Bool {
        guard let render = celestialRenderState else { return false }
        return render.solarLightLevel >= 0.58 && render.nightLevel < 0.20
    }

    private var homeForegroundColor: Color {
        guard celestialRenderState != nil else { return .primary }
        return homeUsesDarkText ? Color.black.opacity(0.84) : .white
    }

    private var weatherRequestKey: String {
        guard let snapshot = locationManager.celestialState.snapshot else { return "none" }
        let bucket = Int(snapshot.date.timeIntervalSince1970 / (15 * 60))
        return String(
            format: "%.2f:%.2f:%d",
            snapshot.latitudeDegrees,
            snapshot.longitudeDegrees,
            bucket
        )
    }

    @ViewBuilder
    private var homeSkyBase: some View {
        if let render = celestialRenderState {
            ZStack {
                LinearGradient(
                    colors: [
                        Color(red: 0.004, green: 0.02, blue: 0.055),
                        Color(red: 0.0, green: 0.004, blue: 0.025)
                    ],
                    startPoint: .top,
                    endPoint: .bottom
                )
                .opacity(render.nightLevel)

                LinearGradient(
                    colors: [
                        Color(red: 0.20, green: 0.25, blue: 0.45),
                        Color(red: 0.92, green: 0.54, blue: 0.36)
                    ],
                    startPoint: .top,
                    endPoint: .bottom
                )
                .opacity(render.twilightLevel)

                LinearGradient(
                    colors: [
                        Color(red: 0.21, green: 0.55, blue: 0.88),
                        Color(red: 0.69, green: 0.87, blue: 0.97)
                    ],
                    startPoint: .top,
                    endPoint: .bottom
                )
                .opacity(render.solarLightLevel)
            }
        } else {
            // Fail-closed : sans position/éphéméride qualifiée, ne pas afficher
            // un faux ciel de jour ou de nuit.
            Color(uiColor: .systemBackground)
        }
    }

    private func revealTabBarAndScheduleHide() {
        tabBarHideTask?.cancel()
        if !tabBarVisible {
            withAnimation(.easeOut(duration: 0.16)) {
                tabBarVisible = true
            }
        }
        tabBarHideTask = Task { @MainActor in
            try? await Task.sleep(nanoseconds: HomeTabBarVisibilityPolicyV2.inactivityTimeoutNanoseconds)
            guard !Task.isCancelled, isVisible else { return }
            withAnimation(.easeInOut(duration: 0.22)) {
                tabBarVisible = false
            }
        }
    }

    private var skyPanel: some View {
        CelestialSkyDialV2(
            state: locationManager.celestialState,
            globeMode: CelestialGlobeModeV2(rawValue: globeModeRaw) ?? .local,
            renderState: celestialRenderState
        )
            .aspectRatio(1, contentMode: .fit)
            .frame(maxWidth: 470)
    }

    private var detailsPanel: some View {
        VStack(spacing: 18) {
            if shouldShowReliabilityCard {
                reliabilityCard
            }
            if let snapshot = locationManager.celestialState.snapshot {
                ephemerisCard(snapshot)
            }
        }
    }

    private var shouldShowReliabilityCard: Bool {
        let state = locationManager.celestialState
        guard state.locationQuality == .valid, state.snapshot != nil else { return true }
        return !CelestialHeadingPolicyV2.isUsable(state.headingQuality)
    }

    private var reliabilityCard: some View {
        VStack(alignment: .leading, spacing: 8) {
            Label(statusTitle, systemImage: statusSymbol)
                .font(.headline)
                .foregroundStyle(statusColor)
            Text(statusDetail)
                .font(.footnote)
                .foregroundStyle(.secondary)
            if locationManager.celestialState.locationQuality == .noPermission {
                Button {
                    if let settingsURL = URL(string: UIApplication.openSettingsURLString) {
                        openURL(settingsURL)
                    }
                } label: {
                    Label("Réglages", systemImage: "gearshape.fill")
                }
                .buttonStyle(.bordered)
                .accessibilityHint("Ouvre les réglages système pour autoriser la localisation")
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding()
        .background(.thinMaterial, in: RoundedRectangle(cornerRadius: 18))
    }

    private func ephemerisCard(_ snapshot: CelestialSnapshotV2) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(snapshot.isNight ? "NUIT LOCALE" : "JOUR LOCAL")
                .font(.caption.bold())
                .foregroundStyle(.secondary)
            HStack(alignment: .center, spacing: 14) {
                VStack(alignment: .leading, spacing: 10) {
                    bodyValue("Soleil", body: snapshot.sun, symbol: "sun.max.fill", color: .yellow)
                    bodyValue("Lune", body: snapshot.moon, symbol: "moon.fill", color: .indigo)
                }
                Spacer(minLength: 8)
                CelestialMoonPhaseViewV2(
                    phase: snapshot.moonPhase,
                    lunarEclipse: snapshot.lunarEclipse,
                    isAboveHorizon: snapshot.moon.altitudeDegrees >= AtmosphericRefractionV2.standardSolarDiskHorizonDegrees
                )
                    .frame(width: 86, height: 86)
            }
            Divider()
            Text("Phase lunaire : \(phaseName(snapshot.moonPhase)) • \(Int((snapshot.moonPhase.illuminatedFraction * 100).rounded())) % éclairée")
                .font(.footnote)
            if snapshot.lunarEclipse.stage != .none {
                Label(
                    lunarEclipseDescription(
                        snapshot.lunarEclipse,
                        isAboveHorizon: snapshot.moon.altitudeDegrees >= AtmosphericRefractionV2.standardSolarDiskHorizonDegrees
                    ),
                    systemImage: "moon.circle.fill"
                )
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(.red)
            }
            if let solarEclipse = try? SolarEclipseGeometryV2.evaluate(
                sun: snapshot.sun,
                moon: snapshot.moon
            ), solarEclipse.isEclipse {
                HStack(spacing: 12) {
                    CelestialSolarEclipseViewV2(
                        eclipse: solarEclipse,
                        moonDirectionRadians: solarEclipseDirection(snapshot),
                        isAboveHorizon: snapshot.sun.altitudeDegrees >= AtmosphericRefractionV2.standardSolarDiskHorizonDegrees
                    )
                        .frame(width: 72, height: 72)
                    VStack(alignment: .leading, spacing: 3) {
                        Text(solarEclipseDescription(
                            solarEclipse,
                            isAboveHorizon: snapshot.sun.altitudeDegrees >= AtmosphericRefractionV2.standardSolarDiskHorizonDegrees
                        ))
                            .font(.footnote.weight(.semibold))
                        Text("Occultation calculée avec les diamètres apparents réels")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }
            }
            Text(
                qualifiedWeather == nil
                    ? "La météo locale est indisponible : Céleste conserve uniquement le ciel astronomique."
                    : "La météo locale module les nuages et la visibilité sans modifier les positions astronomiques."
            )
                .font(.caption)
                .foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding()
        .background(.thinMaterial, in: RoundedRectangle(cornerRadius: 18))
    }

    private func bodyValue(_ title: String, body: CelestialBodyV2, symbol: String, color: Color) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Label(title, systemImage: symbol)
                .foregroundStyle(color)
                .fontWeight(.semibold)
            Text("Az. \(body.azimuthDegrees, specifier: "%.1f")°")
            Text("Alt. \(body.altitudeDegrees, specifier: "%.1f")°")
        }
        .font(.footnote)
    }

    private var statusTitle: String {
        let state = locationManager.celestialState
        if state.locationQuality != .valid || state.snapshot == nil { return "Ciel local indisponible" }
        if !CelestialHeadingPolicyV2.isUsable(state.headingQuality) { return "Direction du ciel indisponible" }
        return "Ciel local orienté"
    }

    private var statusDetail: String {
        let state = locationManager.celestialState
        switch state.locationQuality {
        case .noPermission:
            return "Autorise la localisation pour calculer le Soleil et la Lune à ta position réelle."
        case .unavailable:
            return "Aucune position GPS utilisable n’est encore disponible."
        case .stale:
            return "La dernière position GPS est trop ancienne et n’est pas présentée comme actuelle."
        case .inaccurate:
            return "La précision GPS est insuffisante pour présenter un ciel local fiable."
        case .valid:
            break
        }

        guard state.snapshot != nil else {
            return "Le calcul céleste n’a pas pu être produit à partir de cette position."
        }

        switch state.headingQuality {
        case .valid:
            return "Position GPS et cap vrai sont suffisamment récents pour orienter le cadran."
        case .unknownAccuracy:
            return "Le cap ne fournit pas d’incertitude numérique ; les astres directionnels restent masqués."
        case .inaccurate:
            return "Le cap dépasse le seuil de qualité de 15° ; les astres directionnels restent masqués."
        case .unreliable:
            return "Le capteur signale un cap non fiable ; les astres directionnels restent masqués."
        case .stale:
            return "Le cap n’a pas été rafraîchi depuis plus de cinq secondes."
        case .unavailable:
            return "Le cap vrai ou l’attitude Core Motion ne sont pas disponibles sur cet appareil."
        }
    }

    private var statusSymbol: String {
        locationManager.celestialState.hasRealDirectionalSky
            ? "location.north.circle.fill"
            : "exclamationmark.triangle.fill"
    }

    private var statusColor: Color {
        locationManager.celestialState.hasRealDirectionalSky ? .green : .orange
    }

    private func phaseName(_ phase: LunarPhaseV2) -> String {
        let fraction = phase.illuminatedFraction
        if fraction < 0.03 { return "nouvelle Lune" }
        if fraction > 0.97 { return "pleine Lune" }
        if phase.waxing { return fraction < 0.5 ? "croissant" : "gibbeuse croissante" }
        return fraction < 0.5 ? "dernier croissant" : "gibbeuse décroissante"
    }

    private func lunarEclipseDescription(
        _ eclipse: LunarEclipseV2,
        isAboveHorizon: Bool
    ) -> String {
        let type: String
        switch eclipse.stage {
        case .none:
            return "Aucune éclipse lunaire"
        case .penumbral:
            type = "Éclipse lunaire pénombrale"
        case .partial:
            type = "Éclipse lunaire partielle"
        case .total:
            type = "Éclipse lunaire totale"
        }
        let magnitude = String(format: "%.2f", eclipse.umbralMagnitude)
        let visibility = isAboveHorizon ? "visible ici" : "Lune sous l’horizon, non visible ici"
        return "\(type) • magnitude ombrale \(magnitude) • \(visibility)"
    }

    private func solarEclipseDescription(
        _ eclipse: SolarEclipseV2,
        isAboveHorizon: Bool
    ) -> String {
        let type: String
        switch eclipse.stage {
        case .none:
            return "Aucune éclipse solaire"
        case .partial:
            type = "Éclipse solaire partielle"
        case .annular:
            type = "Éclipse solaire annulaire"
        case .total:
            type = "Éclipse solaire totale"
        }
        let percentage = Int((eclipse.obscuredFraction * 100).rounded())
        let visibility = isAboveHorizon ? "visible ici" : "Soleil sous l’horizon, non visible ici"
        return "\(type) • \(percentage) % du Soleil masqué • \(visibility)"
    }

    private func solarEclipseDirection(_ snapshot: CelestialSnapshotV2) -> Double {
        if let sun = CelestialDialProjectionV2.project(
            azimuthDegrees: snapshot.sun.azimuthDegrees,
            altitudeDegrees: snapshot.sun.altitudeDegrees,
            trueHeadingDegrees: 0
        ), let moon = CelestialDialProjectionV2.project(
            azimuthDegrees: snapshot.moon.azimuthDegrees,
            altitudeDegrees: snapshot.moon.altitudeDegrees,
            trueHeadingDegrees: 0
        ) {
            return atan2(moon.y - sun.y, moon.x - sun.x)
        }

        // The dial intentionally rejects bodies below its civil horizon. Keep
        // the event card physically oriented there by comparing their local
        // horizontal unit vectors instead of falling back to a fixed crescent.
        func localProjection(_ body: CelestialBodyV2) -> (x: Double, y: Double) {
            let azimuth = body.azimuthDegrees * .pi / 180
            let altitude = body.altitudeDegrees * .pi / 180
            return (
                x: cos(altitude) * sin(azimuth),
                y: -cos(altitude) * cos(azimuth)
            )
        }
        let sun = localProjection(snapshot.sun)
        let moon = localProjection(snapshot.moon)
        return atan2(moon.y - sun.y, moon.x - sun.x)
    }
}

private struct CelestialSkyDialV2: View {
    let state: CelestialTrackingStateV2
    let globeMode: CelestialGlobeModeV2
    let renderState: CelestialRenderStateV2?

    var body: some View {
        GeometryReader { geometry in
            let size = min(geometry.size.width, geometry.size.height)
            let center = CGPoint(x: geometry.size.width / 2, y: geometry.size.height / 2)
            let horizonRadius = size * 0.40

            ZStack {
                Circle()
                    .fill(Color.clear)
                    .overlay {
                        dialAtmosphereBackground
                            .clipShape(Circle())
                    }
                    .opacity(0.58)
                Circle()
                    .stroke(.white.opacity(0.55), lineWidth: 2)
                    .padding(size * 0.08)

                cardinal("N", x: center.x, y: center.y - horizonRadius - 15)
                cardinal("E", x: center.x + horizonRadius + 15, y: center.y)
                cardinal("S", x: center.x, y: center.y + horizonRadius + 15)
                cardinal("O", x: center.x - horizonRadius - 15, y: center.y)

                if let snapshot = state.snapshot {
                    CelestialGlobeViewV2(snapshot: snapshot, mode: globeMode)
                        .frame(width: size * 0.29, height: size * 0.29)
                        .position(center)
                } else {
                    Circle()
                        .fill(.blue.opacity(0.42))
                        .overlay(Circle().stroke(.white.opacity(0.7), lineWidth: 1))
                        .frame(width: size * 0.22, height: size * 0.22)
                        .position(center)
                }

                if state.hasRealDirectionalSky,
                   let snapshot = state.snapshot,
                   let heading = state.trueHeadingDegrees {
                    let solarEclipse = try? SolarEclipseGeometryV2.evaluate(
                        sun: snapshot.sun,
                        moon: snapshot.moon
                    )
                    let baseSunOpacity = CelestialHorizonTransitionV2.diskOpacity(
                        altitudeDegrees: snapshot.sun.altitudeDegrees
                    )
                    let sunOpacity = renderState?.sunVisibility ?? baseSunOpacity
                    let moonOpacity = renderState?.moonVisibility ??
                        CelestialHorizonTransitionV2.diskOpacity(
                            altitudeDegrees: snapshot.moon.altitudeDegrees
                        )
                    let environmentFactor: Double = {
                        if baseSunOpacity > 0.001, let renderState {
                            return min(
                                1,
                                max(0, renderState.sunVisibility / baseSunOpacity)
                            )
                        }
                        return min(
                            1,
                            max(0, 1 - (renderState?.atmosphereOpacity ?? 0))
                        )
                    }()
                    let sunGlowOpacity = CelestialHorizonTransitionV2.sunGlowOpacity(
                        altitudeDegrees: snapshot.sun.altitudeDegrees
                    ) * (0.45 + 0.55 * environmentFactor)

                    if sunGlowOpacity > 0 {
                        Circle()
                            .fill(
                                RadialGradient(
                                    colors: [
                                        .orange.opacity(0.52 * sunGlowOpacity),
                                        .yellow.opacity(0.24 * sunGlowOpacity),
                                        .clear
                                    ],
                                    center: .center,
                                    startRadius: 0,
                                    endRadius: size * 0.12
                                )
                            )
                            .frame(width: size * 0.24, height: size * 0.24)
                            .position(
                                horizonPoint(
                                    for: snapshot.sun,
                                    heading: heading,
                                    center: center,
                                    radius: horizonRadius
                                )
                            )
                    }

                    if let solarEclipse, solarEclipse.isEclipse,
                       sunOpacity > 0,
                       moonOpacity > 0 {
                        let sunPoint = point(
                            for: snapshot.sun,
                            heading: heading,
                            center: center,
                            radius: horizonRadius
                        )
                        let moonPoint = point(
                            for: snapshot.moon,
                            heading: heading,
                            center: center,
                            radius: horizonRadius
                        )
                        let moonDirection = atan2(
                            Double(moonPoint.y - sunPoint.y),
                            Double(moonPoint.x - sunPoint.x)
                        )
                        CelestialSolarEclipseViewV2(
                            eclipse: solarEclipse,
                            moonDirectionRadians: moonDirection
                        )
                            .frame(width: size * 0.15, height: size * 0.15)
                            .scaleEffect(CGFloat(0.82 + 0.18 * min(sunOpacity, moonOpacity)))
                            .opacity(min(sunOpacity, moonOpacity))
                            .position(sunPoint)
                    } else {
                        if sunOpacity > 0 {
                            marker(symbol: "sun.max.fill", color: .yellow, size: size * 0.10)
                                .scaleEffect(
                                    CGFloat(
                                        CelestialHorizonTransitionV2.diskScale(
                                            altitudeDegrees: snapshot.sun.altitudeDegrees
                                        )
                                    )
                                )
                                .opacity(sunOpacity)
                                .position(
                                    point(
                                        for: snapshot.sun,
                                        heading: heading,
                                        center: center,
                                        radius: horizonRadius
                                    )
                                )
                        }
                        if moonOpacity > 0 {
                            marker(symbol: "moon.fill", color: .white, size: size * 0.085)
                                .scaleEffect(
                                    CGFloat(
                                        CelestialHorizonTransitionV2.diskScale(
                                            altitudeDegrees: snapshot.moon.altitudeDegrees
                                        )
                                    )
                                )
                                .opacity(moonOpacity)
                                .position(
                                    point(
                                        for: snapshot.moon,
                                        heading: heading,
                                        center: center,
                                        radius: horizonRadius
                                    )
                                )
                        }
                    }
                }
            }
            .frame(width: geometry.size.width, height: geometry.size.height)
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(accessibilityDescription)
    }

    @ViewBuilder
    private var dialAtmosphereBackground: some View {
        if let renderState {
            ZStack {
                LinearGradient(
                    colors: [.black, .indigo],
                    startPoint: .top,
                    endPoint: .bottom
                )
                .opacity(renderState.nightLevel)

                LinearGradient(
                    colors: [
                        Color(red: 0.20, green: 0.25, blue: 0.45),
                        Color(red: 0.92, green: 0.54, blue: 0.36)
                    ],
                    startPoint: .top,
                    endPoint: .bottom
                )
                .opacity(renderState.twilightLevel)

                LinearGradient(
                    colors: [.blue.opacity(0.85), .cyan.opacity(0.5)],
                    startPoint: .top,
                    endPoint: .bottom
                )
                .opacity(renderState.solarLightLevel)
            }
        } else {
            Color.clear
        }
    }

    private func cardinal(_ value: String, x: CGFloat, y: CGFloat) -> some View {
        Text(value)
            .font(.caption.bold())
            .foregroundStyle(.white)
            .position(x: x, y: y)
    }

    private func marker(symbol: String, color: Color, size: CGFloat) -> some View {
        Image(systemName: symbol)
            .font(.system(size: size))
            .foregroundStyle(color)
            .shadow(color: color.opacity(0.8), radius: 6)
    }

    private func point(
        for body: CelestialBodyV2,
        heading: Double,
        center: CGPoint,
        radius: CGFloat
    ) -> CGPoint {
        guard let projected = CelestialDialProjectionV2.project(
            azimuthDegrees: body.azimuthDegrees,
            altitudeDegrees: body.altitudeDegrees,
            trueHeadingDegrees: heading
        ) else {
            return center
        }
        return CGPoint(
            x: center.x + CGFloat(projected.x) * radius,
            y: center.y + CGFloat(projected.y) * radius
        )
    }

    private func horizonPoint(
        for body: CelestialBodyV2,
        heading: Double,
        center: CGPoint,
        radius: CGFloat
    ) -> CGPoint {
        let projectedAltitude = CelestialHorizonTransitionV2.altitudeForHorizonGlow(
            body.altitudeDegrees
        )
        guard let projected = CelestialDialProjectionV2.project(
            azimuthDegrees: body.azimuthDegrees,
            altitudeDegrees: projectedAltitude,
            trueHeadingDegrees: heading
        ) else {
            return center
        }
        return CGPoint(
            x: center.x + CGFloat(projected.x) * radius,
            y: center.y + CGFloat(projected.y) * radius
        )
    }

    private var accessibilityDescription: String {
        guard state.hasRealDirectionalSky, let snapshot = state.snapshot else {
            return "Cadran céleste. Globe local indisponible ou direction masquée car les capteurs ne sont pas assez fiables."
        }
        let daylight = snapshot.isNight ? "nuit locale" : "jour local"
        let globeDescription = globeMode == .local
            ? "globe centré sur la position GPS"
            : "globe monde montrant le terminateur jour nuit"
        return "Cadran céleste avec \(globeDescription), \(daylight). Soleil azimut \(Int(snapshot.sun.azimuthDegrees.rounded())) degrés, altitude \(Int(snapshot.sun.altitudeDegrees.rounded())) degrés. Lune azimut \(Int(snapshot.moon.azimuthDegrees.rounded())) degrés, altitude \(Int(snapshot.moon.altitudeDegrees.rounded())) degrés."
    }
}
