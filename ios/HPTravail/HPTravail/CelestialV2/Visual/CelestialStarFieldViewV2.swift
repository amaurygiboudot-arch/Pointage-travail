import SwiftUI

enum CelestialStarFieldPresentationV2 { case dial, fullScreen }

private struct PreparedStarV2: Sendable {
    let hr: Int
    let magnitude: Double
    let position: LocalStarPositionV2
    let x: Double
    let y: Double
}

private struct PreparedSkyV2: Sendable {
    let place: String
    let date: Date
    let stars: [PreparedStarV2]
}

@MainActor
private final class CelestialStarFieldModelV2: ObservableObject {
    @Published private(set) var prepared: PreparedSkyV2?

    func clear() { prepared = nil }

    func prepare(snapshot: CelestialSnapshotV2) async {
        let latitude = snapshot.latitudeDegrees
        let longitude = snapshot.longitudeDegrees
        let date = snapshot.date
        let place = String(format: "%.4f:%.4f", latitude, longitude)
        let task = Task.detached(priority: .utility) { () -> PreparedSkyV2? in
            guard let catalog = StarSkyCatalogLoaderV2.load() else { return nil }
            var stars: [PreparedStarV2] = []
            for item in catalog.stars {
                if Task.isCancelled { return nil }
                if item.star.visualMagnitude > 4.5 { continue }
                let position = StarSkyProjectionV2.horizontal(star: item.star,
                    latitudeDegrees: latitude, longitudeDegrees: longitude, date: date)
                guard let p = CelestialPanoramaGeometryV2.normalized(position: position) else { continue }
                stars.append(PreparedStarV2(hr: item.hr, magnitude: item.star.visualMagnitude,
                    position: position, x: p.x01, y: p.y01))
            }
            return PreparedSkyV2(place: place, date: date, stars: stars)
        }
        let result = await withTaskCancellationHandler(operation: { await task.value }, onCancel: { task.cancel() })
        guard !Task.isCancelled else { return }
        if let result { prepared = result }
    }
}

/// Same style policy and catalogue on both platforms; no constellation paths.
struct CelestialStarFieldViewV2: View {
    let state: CelestialTrackingStateV2
    let presentation: CelestialStarFieldPresentationV2
    let renderState: CelestialRenderStateV2?
    @StateObject private var model = CelestialStarFieldModelV2()
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var isVisible = false

    init(state: CelestialTrackingStateV2, presentation: CelestialStarFieldPresentationV2 = .dial,
         renderState: CelestialRenderStateV2? = nil) {
        self.state = state; self.presentation = presentation; self.renderState = renderState
    }

    var body: some View {
        let quality = CelestialRenderQualityProviderV2.current
        let animate = isVisible && scenePhase == .active && !reduceMotion && quality != .reduced &&
            (renderState?.starsVisibility ?? 0) > 0.005
        ZStack {
            // Faint stars are outside the animation timeline.
            Canvas(rendersAsynchronously: quality != .high) { context, size in
                draw(context: context, size: size, bright: false, seconds: 0, animated: false)
            }
            TimelineView(.animation(minimumInterval: quality == .high ? 0.05 : 0.1, paused: !animate)) { _ in
                Canvas(rendersAsynchronously: quality != .high) { context, size in
                    draw(context: context, size: size, bright: true,
                         seconds: ProcessInfo.processInfo.systemUptime, animated: animate)
                }
            }
        }
        .allowsHitTesting(false)
        .accessibilityHidden(true)
        .onAppear { isVisible = true }
        .onDisappear { isVisible = false; model.clear() }
        .task(id: preparationKey) {
            guard isVisible, scenePhase == .active, state.locationQuality == .valid,
                  let snapshot = state.snapshot else { return }
            await model.prepare(snapshot: snapshot)
        }
    }

    private func draw(context: GraphicsContext, size: CGSize, bright: Bool,
                      seconds: Double, animated: Bool) {
        guard state.locationQuality == .valid, let snapshot = state.snapshot,
              let renderState, let sky = model.prepared,
              sky.place == String(format: "%.4f:%.4f", snapshot.latitudeDegrees, snapshot.longitudeDegrees),
              (0...60).contains(snapshot.date.timeIntervalSince(sky.date)) else { return }
        let heading = CelestialHeadingPolicyV2.renderingHeadingDegrees(
            headingDegrees: state.trueHeadingDegrees, quality: state.headingQuality)
        let center = CGPoint(x: size.width / 2, y: size.height / 2)
        let radius = min(size.width, size.height) * 0.4
        if presentation == .dial && !bright { drawDome(context: context, center: center, radius: radius, heading: heading) }
        let visibility = renderState.starsVisibility
        guard visibility > 0.005 else { return }
        let shift = CelestialPanoramaGeometryV2.headingFraction(centerAzimuthDegrees: heading)
        for star in sky.stars {
            guard (star.magnitude <= CelestialStarAppearanceV2.twinkleMaxMagnitude) == bright else { continue }
            if presentation == .fullScreen && star.magnitude > 4.2 { continue }
            guard let style = CelestialStarAppearanceV2.resolve(magnitude: star.magnitude,
                apparentAltitudeDegrees: star.position.apparentAltitudeDegrees, starId: star.hr,
                elapsedSeconds: seconds, visibility: visibility, animated: animated) else { continue }
            let point: CGPoint
            if presentation == .fullScreen {
                point = CGPoint(x: CelestialPanoramaGeometryV2.screenFraction(skyX01: star.x, heading: shift) * size.width,
                                y: star.y * size.height)
            } else {
                guard let p = CelestialDomeV2.project(azimuthDegrees: star.position.azimuthDegrees,
                    apparentAltitudeDegrees: star.position.apparentAltitudeDegrees, headingDegrees: heading) else { continue }
                point = CGPoint(x: center.x + p.x * radius, y: center.y + p.y * radius)
            }
            drawStar(context: context, point: point, style: style)
            if presentation == .fullScreen {
                if point.x < style.haloRadius { drawStar(context: context, point: CGPoint(x: point.x + size.width, y: point.y), style: style) }
                if point.x > size.width - style.haloRadius { drawStar(context: context, point: CGPoint(x: point.x - size.width, y: point.y), style: style) }
            }
        }
    }

    private func drawStar(context: GraphicsContext, point: CGPoint, style: CelestialStarStyleV2) {
        guard style.coreAlpha > 0 else { return }
        let cool = Color(red: 243.0/255, green: 247.0/255, blue: 1)
        func circle(_ r: Double) -> Path {
            Path(ellipseIn: CGRect(x: point.x-r, y: point.y-r, width: r*2, height: r*2))
        }
        if style.haloAlpha > 0 {
            context.fill(circle(style.haloRadius), with: .radialGradient(
                Gradient(stops: [.init(color: cool.opacity(style.haloAlpha), location: 0),
                    .init(color: cool.opacity(style.haloAlpha * 80/255), location: 0.3),
                    .init(color: .clear, location: 1)]), center: point, startRadius: 0, endRadius: style.haloRadius))
        }
        context.fill(circle(style.radius), with: .color(cool.opacity(style.coreAlpha)))
        context.fill(circle(style.radius * 0.4), with: .color(.white.opacity(style.coreAlpha * 230/255)))
    }

    private func drawDome(context: GraphicsContext, center: CGPoint, radius: CGFloat, heading: Double) {
        let r = radius * CelestialDomeV2.radiusFraction
        let disk = Path(ellipseIn: CGRect(x: center.x-r, y: center.y-r, width: r*2, height: r*2))
        context.fill(disk, with: .radialGradient(
            Gradient(stops: [.init(color: .clear, location: 0),
                .init(color: .clear, location: 0.72),
                .init(color: Color(red: 0.25, green: 0.40, blue: 0.62).opacity(0.26), location: 0.98),
                .init(color: .clear, location: 1)]), center: center, startRadius: 0, endRadius: r))
        // Coordinate graticule, not links between stars. Every vertex belongs
        // to the same oblique sphere used by Sun/Moon and the stars.
        func path(_ coordinates: [(Double, Double)]) -> Path {
            var result = Path()
            for (i, pair) in coordinates.enumerated() {
                guard let p = CelestialDomeV2.project(azimuthDegrees: pair.0,
                    apparentAltitudeDegrees: pair.1, headingDegrees: heading) else { continue }
                let q = CGPoint(x: center.x+p.x*radius, y: center.y+p.y*radius)
                if i == 0 { result.move(to: q) } else { result.addLine(to: q) }
            }
            return result
        }
        for altitude in [0.0, 30.0, 60.0] {
            let curve = path(stride(from: 0.0, through: 360.0, by: 5.0).map { ($0, altitude) })
            context.stroke(curve, with: .color(.white.opacity(altitude == 0 ? 0.20 : 0.07)), lineWidth: altitude == 0 ? 0.8 : 0.5)
        }
        for azimuth in stride(from: 0.0, to: 360.0, by: 60.0) {
            context.stroke(path(stride(from: 0.0, through: 90.0, by: 3.0).map { (azimuth, $0) }),
                           with: .color(.white.opacity(0.07)), lineWidth: 0.5)
        }
    }

    private var preparationKey: String {
        guard isVisible, scenePhase == .active, state.locationQuality == .valid,
              let snapshot = state.snapshot else { return "inactive" }
        return String(format: "%.4f:%.4f:%.0f", snapshot.latitudeDegrees, snapshot.longitudeDegrees,
                      floor(snapshot.date.timeIntervalSince1970 / 30))
    }
}
