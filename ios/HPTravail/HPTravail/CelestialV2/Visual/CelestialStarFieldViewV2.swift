import SwiftUI

enum CelestialStarFieldPresentationV2 {
    case dial
    case fullScreen
}

private struct PreparedStarSkyStarV2: Sendable {
    let hr: Int
    let magnitude: Double
    let position: LocalStarPositionV2
}

private struct PreparedStarSkyV2: Sendable {
    let key: String
    let stars: [PreparedStarSkyStarV2]
    let paths: [ConstellationPathV2]
}

@MainActor
private final class CelestialStarFieldModelV2: ObservableObject {
    @Published private(set) var prepared: PreparedStarSkyV2?
    private var requestedKey: String?

    func prepare(snapshot: CelestialSnapshotV2) {
        let bucket = Int(snapshot.date.timeIntervalSince1970 / 30)
        let key = String(
            format: "%.4f:%.4f:%d",
            snapshot.latitudeDegrees,
            snapshot.longitudeDegrees,
            bucket
        )
        guard key != requestedKey else { return }
        requestedKey = key

        let latitude = snapshot.latitudeDegrees
        let longitude = snapshot.longitudeDegrees
        let date = snapshot.date

        Task {
            let result = await Task.detached(priority: .utility) {
                guard let catalog = StarSkyCatalogLoaderV2.load() else {
                    return Optional<PreparedStarSkyV2>.none
                }
                let stars = catalog.stars.compactMap { item -> PreparedStarSkyStarV2? in
                    let position = StarSkyProjectionV2.horizontal(
                        star: item.star,
                        latitudeDegrees: latitude,
                        longitudeDegrees: longitude,
                        date: date
                    )
                    guard position.isAboveApparentHorizon else { return nil }
                    return PreparedStarSkyStarV2(
                        hr: item.hr,
                        magnitude: item.star.visualMagnitude,
                        position: position
                    )
                }
                return PreparedStarSkyV2(key: key, stars: stars, paths: catalog.constellationPaths)
            }.value

            guard let result, requestedKey == result.key else { return }
            prepared = result
        }
    }
}

struct CelestialStarFieldViewV2: View {
    let state: CelestialTrackingStateV2
    let presentation: CelestialStarFieldPresentationV2
    let renderState: CelestialRenderStateV2?
    @StateObject private var model = CelestialStarFieldModelV2()

    init(
        state: CelestialTrackingStateV2,
        presentation: CelestialStarFieldPresentationV2 = .dial,
        renderState: CelestialRenderStateV2? = nil
    ) {
        self.state = state
        self.presentation = presentation
        self.renderState = renderState
    }

    var body: some View {
        Canvas { context, size in
            guard state.locationQuality == .valid,
                  state.snapshot != nil,
                  let renderState,
                  let sky = model.prepared else {
                return
            }

            let starOpacity = renderState.starsVisibility
            let constellationOpacity = (
                presentation == .fullScreen ? 0.18 : 0.12
            ) * renderState.constellationsVisibility

            let center = CGPoint(x: size.width / 2, y: size.height / 2)
            let radius = min(size.width, size.height) * 0.50
            // En plein écran, la projection 360° doit utiliser exactement le
            // viewport : ±180° aux bords gauche/droit, horizon en bas et zénith en haut.
            let scaleX = presentation == .fullScreen ? size.width * 0.50 : radius
            let scaleY = presentation == .fullScreen ? size.height * 0.50 : radius
            var points: [Int: CGPoint] = [:]
            points.reserveCapacity(sky.stars.count / 2)
            var visible: [(PreparedStarSkyStarV2, CGPoint)] = []
            visible.reserveCapacity(sky.stars.count / 2)

            for star in sky.stars {
                let projected: StarDeviceProjectionV2?
                if presentation == .fullScreen {
                    let centerAzimuth = CelestialHeadingPolicyV2.isUsable(state.headingQuality)
                        ? (state.trueHeadingDegrees ?? 0)
                        : 0
                    projected = StarSkyProjectionV2.projectToPanorama(
                        position: star.position,
                        centerAzimuthDegrees: centerAzimuth
                    )
                } else if state.hasPhysicalStarSky, let frame = state.deviceFrame {
                    projected = StarSkyProjectionV2.projectToDevice(
                        position: star.position,
                        frame: frame
                    )
                } else {
                    projected = StarSkyProjectionV2.projectToZenithMap(
                        position: star.position
                    )
                }
                guard let projected else { continue }
                let point = CGPoint(
                    x: center.x + CGFloat(projected.x) * scaleX,
                    y: center.y + CGFloat(projected.y) * scaleY
                )
                if point.x < -24 || point.x > size.width + 24 ||
                    point.y < -24 || point.y > size.height + 24 {
                    continue
                }
                points[star.hr] = point
                visible.append((star, point))
            }

            var linePath = Path()
            for constellation in sky.paths {
                var previous: CGPoint?
                var visiblePoints: [CGPoint] = []
                for hr in constellation.hrNumbers {
                    guard let point = points[hr] else {
                        previous = nil
                        continue
                    }
                    if let previous, abs(previous.x - point.x) <= size.width * 0.50 {
                        linePath.move(to: previous)
                        linePath.addLine(to: point)
                    }
                    previous = point
                    visiblePoints.append(point)
                }

            }

            context.stroke(
                linePath,
                with: .color(.white.opacity(constellationOpacity)),
                lineWidth: presentation == .fullScreen ? 0.55 : 0.65
            )

            if starOpacity > 0.01 {
                for (star, point) in visible {
                    let brightness = min(1, max(0.08, (6.6 - star.magnitude) / 7.5))
                    let starRadius = presentation == .fullScreen
                        ? CGFloat(0.65 + brightness * 2.25)
                        : CGFloat(0.55 + brightness * 2.0)
                    let rect = CGRect(
                        x: point.x - starRadius,
                        y: point.y - starRadius,
                        width: starRadius * 2,
                        height: starRadius * 2
                    )
                    context.fill(
                        Path(ellipseIn: rect),
                        with: .color(.white.opacity(starOpacity * (0.34 + 0.66 * brightness)))
                    )
                }
            }
        }
        .allowsHitTesting(false)
        .accessibilityHidden(true)
        .task(id: preparationKey) {
            guard let snapshot = state.snapshot, state.locationQuality == .valid else { return }
            model.prepare(snapshot: snapshot)
        }
    }

    private var preparationKey: String {
        guard let snapshot = state.snapshot else { return "none" }
        let bucket = Int(snapshot.date.timeIntervalSince1970 / 30)
        return String(format: "%.4f:%.4f:%d", snapshot.latitudeDegrees, snapshot.longitudeDegrees, bucket)
    }
}

