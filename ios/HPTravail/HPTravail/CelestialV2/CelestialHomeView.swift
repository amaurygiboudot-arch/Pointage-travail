import SwiftUI

struct CelestialHomeView: View {
    @EnvironmentObject private var locationManager: LocationManager
    @Environment(\.scenePhase) private var scenePhase
    @State private var isVisible = false

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 18) {
                    Text(Date.now.formatted(date: .complete, time: .shortened))
                        .font(.headline)
                        .multilineTextAlignment(.center)

                    CelestialSkyDialV2(state: locationManager.celestialState)
                        .aspectRatio(1, contentMode: .fit)
                        .frame(maxWidth: 430)

                    reliabilityCard
                    if let snapshot = locationManager.celestialState.snapshot {
                        ephemerisCard(snapshot)
                    }
                }
                .frame(maxWidth: .infinity)
                .padding()
            }
            .navigationTitle("Accueil")
            .onAppear {
                isVisible = true
                if scenePhase == .active {
                    locationManager.startCelestialTracking()
                }
            }
            .onDisappear {
                isVisible = false
                locationManager.stopCelestialTracking()
            }
            .onChange(of: scenePhase) { phase in
                if phase == .active && isVisible {
                    locationManager.startCelestialTracking()
                } else {
                    locationManager.stopCelestialTracking()
                }
            }
        }
    }

    private var reliabilityCard: some View {
        VStack(alignment: .leading, spacing: 8) {
            Label(statusTitle, systemImage: statusSymbol)
                .font(.headline)
                .foregroundStyle(statusColor)
            Text(statusDetail)
                .font(.footnote)
                .foregroundStyle(.secondary)
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
            HStack {
                bodyValue("Soleil", body: snapshot.sun, symbol: "sun.max.fill", color: .yellow)
                Spacer()
                bodyValue("Lune", body: snapshot.moon, symbol: "moon.fill", color: .indigo)
            }
            Divider()
            Text("Phase lunaire : \(phaseName(snapshot.moonPhase)) • \(Int((snapshot.moonPhase.illuminatedFraction * 100).rounded())) % éclairée")
                .font(.footnote)
            Text("Positions géométriques issues du modèle V2. La réfraction visuelle et la météo ne sont pas incluses.")
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
}

private struct CelestialSkyDialV2: View {
    let state: CelestialTrackingStateV2

    var body: some View {
        GeometryReader { geometry in
            let size = min(geometry.size.width, geometry.size.height)
            let center = CGPoint(x: geometry.size.width / 2, y: geometry.size.height / 2)
            let horizonRadius = size * 0.40

            ZStack {
                Circle()
                    .fill(backgroundGradient)
                Circle()
                    .stroke(.white.opacity(0.55), lineWidth: 2)
                    .padding(size * 0.08)

                cardinal("N", x: center.x, y: center.y - horizonRadius - 15)
                cardinal("E", x: center.x + horizonRadius + 15, y: center.y)
                cardinal("S", x: center.x, y: center.y + horizonRadius + 15)
                cardinal("O", x: center.x - horizonRadius - 15, y: center.y)

                Circle()
                    .fill(
                        LinearGradient(
                            colors: [.blue, .cyan.opacity(0.75), .green.opacity(0.7)],
                            startPoint: .topLeading,
                            endPoint: .bottomTrailing
                        )
                    )
                    .overlay(Circle().stroke(.white.opacity(0.7), lineWidth: 1))
                    .frame(width: size * 0.19, height: size * 0.19)
                    .position(center)

                if state.hasRealDirectionalSky,
                   let snapshot = state.snapshot,
                   let heading = state.trueHeadingDegrees {
                    if snapshot.sun.altitudeDegrees >= -0.833 {
                        marker(symbol: "sun.max.fill", color: .yellow, size: size * 0.10)
                            .position(point(for: snapshot.sun, heading: heading, center: center, radius: horizonRadius))
                    }
                    if snapshot.moon.altitudeDegrees >= -0.833 {
                        marker(symbol: "moon.fill", color: .white, size: size * 0.085)
                            .position(point(for: snapshot.moon, heading: heading, center: center, radius: horizonRadius))
                    }
                }
            }
            .frame(width: geometry.size.width, height: geometry.size.height)
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(accessibilityDescription)
    }

    private var backgroundGradient: LinearGradient {
        let night = state.locationQuality == .valid && (state.snapshot?.isNight ?? false)
        return LinearGradient(
            colors: night ? [.black, .indigo] : [.blue.opacity(0.85), .cyan.opacity(0.5)],
            startPoint: .top,
            endPoint: .bottom
        )
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

    private var accessibilityDescription: String {
        guard state.hasRealDirectionalSky, let snapshot = state.snapshot else {
            return "Cadran céleste. Direction masquée car les capteurs ne sont pas assez fiables."
        }
        return "Cadran céleste. Soleil azimut \(Int(snapshot.sun.azimuthDegrees.rounded())) degrés, altitude \(Int(snapshot.sun.altitudeDegrees.rounded())) degrés. Lune azimut \(Int(snapshot.moon.azimuthDegrees.rounded())) degrés, altitude \(Int(snapshot.moon.altitudeDegrees.rounded())) degrés."
    }
}
