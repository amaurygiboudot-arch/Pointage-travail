import SwiftUI

/// Apparent solar and lunar discs drawn from topocentric angular geometry.
/// Decorative sky-marker sizes never take part in this representation.
struct CelestialSolarEclipseViewV2: View {
    let eclipse: SolarEclipseV2
    /// Screen-space direction from the Sun centre to the Moon centre.
    /// Zero points right; positive values follow the screen's downward y axis.
    let moonDirectionRadians: Double
    var isAboveHorizon = true

    var body: some View {
        Canvas(opaque: false, colorMode: .linear, rendersAsynchronously: true) { context, size in
            drawEclipse(context: &context, size: size)
        }
        .aspectRatio(1, contentMode: .fit)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(solarEclipseName)
        .accessibilityValue(accessibilityValue)
    }

    private func drawEclipse(context: inout GraphicsContext, size: CGSize) {
        let diameter = min(size.width, size.height)
        guard diameter > 2,
              eclipse.sunAngularRadiusDegrees > 0,
              eclipse.moonAngularRadiusDegrees > 0 else { return }

        let sunRadius = diameter * 0.27
        let centre = CGPoint(x: size.width * 0.5, y: size.height * 0.5)
        let sunRect = CGRect(
            x: centre.x - sunRadius,
            y: centre.y - sunRadius,
            width: sunRadius * 2,
            height: sunRadius * 2
        )
        context.fill(
            Path(ellipseIn: sunRect.insetBy(dx: -diameter * 0.09, dy: -diameter * 0.09)),
            with: .radialGradient(
                Gradient(colors: [.yellow.opacity(0.52), .orange.opacity(0.18), .clear]),
                center: centre,
                startRadius: sunRadius * 0.65,
                endRadius: sunRadius * 1.45
            )
        )
        context.fill(
            Path(ellipseIn: sunRect),
            with: .radialGradient(
                Gradient(colors: [.white, .yellow, .orange]),
                center: CGPoint(x: centre.x - sunRadius * 0.25, y: centre.y - sunRadius * 0.25),
                startRadius: 0,
                endRadius: sunRadius
            )
        )

        let moonRadius = sunRadius * CGFloat(
            eclipse.moonAngularRadiusDegrees / eclipse.sunAngularRadiusDegrees
        )
        let separation = sunRadius * CGFloat(
            eclipse.angularSeparationDegrees / eclipse.sunAngularRadiusDegrees
        )
        let moonCentre = CGPoint(
            x: centre.x + separation * CGFloat(cos(moonDirectionRadians)),
            y: centre.y + separation * CGFloat(sin(moonDirectionRadians))
        )
        let moonRect = CGRect(
            x: moonCentre.x - moonRadius,
            y: moonCentre.y - moonRadius,
            width: moonRadius * 2,
            height: moonRadius * 2
        )
        context.fill(
            Path(ellipseIn: moonRect),
            with: .radialGradient(
                Gradient(colors: [Color(red: 0.055, green: 0.06, blue: 0.075), .black]),
                center: CGPoint(x: moonCentre.x - moonRadius * 0.2, y: moonCentre.y - moonRadius * 0.2),
                startRadius: 0,
                endRadius: moonRadius
            )
        )
        context.stroke(
            Path(ellipseIn: moonRect),
            with: .color(.white.opacity(0.22)),
            lineWidth: max(0.5, diameter * 0.008)
        )
    }

    private var solarEclipseName: String {
        switch eclipse.stage {
        case .none:
            return "Aucune éclipse solaire"
        case .partial:
            return "Éclipse solaire partielle"
        case .annular:
            return "Éclipse solaire annulaire"
        case .total:
            return "Éclipse solaire totale"
        }
    }

    private var accessibilityValue: String {
        let percentage = Int((eclipse.obscuredFraction * 100).rounded())
        let visibility = isAboveHorizon
            ? "visible au-dessus de l’horizon local"
            : "non visible ici car le Soleil est sous l’horizon"
        return "\(percentage) pour cent du disque solaire masqué, \(visibility)."
    }
}
