import SwiftUI

/// Spherical Moon phase. The illuminated limb is oriented with the engine's
/// celestial position angle instead of using a decorative left/right mask.
struct CelestialMoonPhaseViewV2: View {
    let phase: LunarPhaseV2
    let lunarEclipse: LunarEclipseV2?
    let isAboveHorizon: Bool

    init(
        phase: LunarPhaseV2,
        lunarEclipse: LunarEclipseV2? = nil,
        isAboveHorizon: Bool = true
    ) {
        self.phase = phase
        self.lunarEclipse = lunarEclipse
        self.isAboveHorizon = isAboveHorizon
    }

    var body: some View {
        Canvas(opaque: false, colorMode: .linear, rendersAsynchronously: true) { context, size in
            drawMoon(context: &context, size: size)
        }
        .aspectRatio(1, contentMode: .fit)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("Phase réelle de la Lune")
        .accessibilityValue(accessibilityValue)
    }

    private func drawMoon(context: inout GraphicsContext, size: CGSize) {
        let diameter = min(size.width, size.height)
        guard diameter > 2 else { return }
        let padding = diameter * 0.055
        let rect = CGRect(
            x: (size.width - diameter) * 0.5 + padding,
            y: (size.height - diameter) * 0.5 + padding,
            width: diameter - padding * 2,
            height: diameter - padding * 2
        )
        let disk = Path(ellipseIn: rect)
        context.clip(to: disk)
        context.fill(
            disk,
            with: .radialGradient(
                Gradient(colors: [
                    Color(red: 0.96, green: 0.95, blue: 0.88),
                    Color(red: 0.68, green: 0.68, blue: 0.64)
                ]),
                center: CGPoint(x: rect.midX - rect.width * 0.17, y: rect.midY - rect.height * 0.20),
                startRadius: 0,
                endRadius: rect.width * 0.68
            )
        )

        drawCraters(context: &context, rect: rect)

        let shadow = CelestialSphereLightingV2.nightPath(in: rect, sun: sunVector)
        context.fill(shadow, with: .color(Color(red: 0.015, green: 0.02, blue: 0.05).opacity(0.92)))
        drawEarthShadowIfNeeded(context: &context, rect: rect)
        context.stroke(disk, with: .color(.white.opacity(0.68)), lineWidth: max(0.8, diameter * 0.018))
    }

    private func drawEarthShadowIfNeeded(context: inout GraphicsContext, rect: CGRect) {
        guard let eclipse = lunarEclipse, eclipse.stage != .none else { return }
        let moonRadius = rect.width * 0.5
        let angle = eclipse.shadowPositionAngleDegrees * .pi / 180
        let shadowCenter = CGPoint(
            x: rect.midX + CGFloat(sin(angle) * eclipse.shadowAxisOffsetMoonRadii) * moonRadius,
            y: rect.midY - CGFloat(cos(angle) * eclipse.shadowAxisOffsetMoonRadii) * moonRadius
        )

        let penumbraRadius = CGFloat(max(0, eclipse.penumbraRadiusMoonRadii)) * moonRadius
        if penumbraRadius > 0 {
            let penumbra = CGRect(
                x: shadowCenter.x - penumbraRadius,
                y: shadowCenter.y - penumbraRadius,
                width: penumbraRadius * 2,
                height: penumbraRadius * 2
            )
            context.fill(
                Path(ellipseIn: penumbra),
                with: .color(Color(red: 0.22, green: 0.10, blue: 0.06).opacity(0.30))
            )
        }

        let umbraRadius = CGFloat(max(0, eclipse.umbraRadiusMoonRadii)) * moonRadius
        if umbraRadius > 0 {
            let umbra = CGRect(
                x: shadowCenter.x - umbraRadius,
                y: shadowCenter.y - umbraRadius,
                width: umbraRadius * 2,
                height: umbraRadius * 2
            )
            context.fill(
                Path(ellipseIn: umbra),
                with: .radialGradient(
                    Gradient(colors: [
                        Color(red: 0.24, green: 0.035, blue: 0.02).opacity(0.82),
                        Color(red: 0.05, green: 0.01, blue: 0.01).opacity(0.94)
                    ]),
                    center: shadowCenter,
                    startRadius: 0,
                    endRadius: umbraRadius
                )
            )
        }
    }

    private func drawCraters(context: inout GraphicsContext, rect: CGRect) {
        let craters: [(x: CGFloat, y: CGFloat, radius: CGFloat, opacity: Double)] = [
            (-0.28, -0.18, 0.11, 0.13),
            (0.20, -0.29, 0.075, 0.16),
            (0.29, 0.06, 0.13, 0.11),
            (-0.10, 0.24, 0.085, 0.15),
            (-0.33, 0.28, 0.050, 0.12),
            (0.08, 0.02, 0.045, 0.10)
        ]
        for crater in craters {
            let radius = rect.width * crater.radius
            let craterRect = CGRect(
                x: rect.midX + rect.width * crater.x - radius,
                y: rect.midY + rect.height * crater.y - radius,
                width: radius * 2,
                height: radius * 2
            )
            context.fill(Path(ellipseIn: craterRect), with: .color(.black.opacity(crater.opacity)))
            context.stroke(
                Path(ellipseIn: craterRect.offsetBy(dx: -radius * 0.10, dy: -radius * 0.10)),
                with: .color(.white.opacity(crater.opacity * 0.75)),
                lineWidth: max(0.35, rect.width * 0.006)
            )
        }
    }

    private var sunVector: CelestialSphereVectorV2 {
        let phaseAngle = phase.phaseAngleDegrees * .pi / 180
        let positionAngle = phase.brightLimbPositionAngleDegrees * .pi / 180
        let transverse = sin(phaseAngle)
        return CelestialSphereVectorV2(
            x: transverse * sin(positionAngle),
            y: -transverse * cos(positionAngle),
            z: cos(phaseAngle)
        )
    }

    private var accessibilityValue: String {
        let percentage = Int((phase.illuminatedFraction * 100).rounded())
        let eclipseDescription: String
        switch lunarEclipse?.stage {
        case .penumbral:
            eclipseDescription = eclipseAccessibility("Éclipse lunaire pénombrale")
        case .partial:
            eclipseDescription = eclipseAccessibility("Éclipse lunaire partielle")
        case .total:
            eclipseDescription = eclipseAccessibility("Éclipse lunaire totale")
        case .some(.none), nil:
            eclipseDescription = ""
        }
        return "\(phaseName), éclairée à \(percentage) pour cent.\(eclipseDescription)"
    }

    private func eclipseAccessibility(_ name: String) -> String {
        isAboveHorizon
            ? " \(name) en cours et visible au-dessus de l’horizon."
            : " \(name) en cours, mais la Lune est sous l’horizon local."
    }

    private var phaseName: String {
        let fraction = phase.illuminatedFraction
        if fraction < 0.03 { return "Nouvelle Lune" }
        if fraction > 0.97 { return "Pleine Lune" }
        if abs(fraction - 0.5) < 0.04 {
            return phase.waxing ? "Premier quartier" : "Dernier quartier"
        }
        if phase.waxing {
            return fraction < 0.5 ? "Croissant de Lune" : "Lune gibbeuse croissante"
        }
        return fraction < 0.5 ? "Dernier croissant" : "Lune gibbeuse décroissante"
    }
}
