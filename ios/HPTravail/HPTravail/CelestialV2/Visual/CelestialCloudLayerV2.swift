import Foundation
import SwiftUI
import CoreGraphics

struct CelestialCloudLayerV2: View {
    let renderState: CelestialRenderStateV2?
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var isVisible = false
    @StateObject private var model = CelestialCloudTextureModelV2()

    var body: some View {
        GeometryReader { geometry in
            TimelineView(.animation(minimumInterval: updateInterval, paused: !isVisible || scenePhase != .active)) { timeline in
                let request = request(size: geometry.size, date: timeline.date)
                Canvas { context, size in
                    guard isVisible, scenePhase == .active, let request,
                          let frame = model.current, frame.key.origin == request.origin else { return }
                    let progress = reduceMotion ? 1 : min(1, max(0,
                        (ProcessInfo.processInfo.systemUptime - model.switchedAt) / 6))
                    let rect = CGRect(origin: .zero, size: size)
                    if progress >= 1 {
                        context.draw(Image(decorative: frame.image, scale: 1), in: rect)
                    } else {
                        // An isolated premultiplied linear blend, not two source-over veils.
                        context.drawLayer { layer in
                            layer.blendMode = .plusLighter
                            if let old = model.previous {
                                layer.opacity = 1 - progress
                                layer.draw(Image(decorative: old.image, scale: 1), in: rect)
                            }
                            layer.opacity = progress
                            layer.draw(Image(decorative: frame.image, scale: 1), in: rect)
                        }
                    }
                }
                .task(id: request) { await model.update(request) }
            }
        }
        .allowsHitTesting(false)
        .accessibilityHidden(true)
        .onAppear { isVisible = true }
        .onDisappear { isVisible = false; model.clear() }
    }

    private var updateInterval: TimeInterval {
        if reduceMotion { return 1 }
        switch CelestialRenderQualityProviderV2.current {
        case .reduced: return 1
        case .balanced: return 0.5
        case .high: return 0.25
        }
    }

    private func request(size: CGSize, date: Date) -> CelestialCloudTextureKeyV2? {
        guard isVisible, scenePhase == .active, let state = renderState, let clouds = state.clouds,
              let expires = state.cloudsExpiresAt, let fetched = state.cloudsFetchedAt,
              Date() >= fetched, Date() <= expires,
              state.dataFreshness.weatherStatus == .fresh,
              state.dataFreshness.locationStatus == .fresh,
              size.width.isFinite, size.height.isFinite, size.width > 1, size.height > 1,
              [state.solarLightLevel, state.twilightLevel, state.nightLevel].allSatisfy(\.isFinite) else { return nil }
        let width: Int
        let octaves: Int
        switch CelestialRenderQualityProviderV2.current {
        case .reduced: width = 64; octaves = 2
        case .balanced: width = 96; octaves = 3
        case .high: width = 128; octaves = 4
        }
        let aspect = min(4, max(0.25, Double(size.height / size.width)))
        let height = min(256, max(32, Int(Double(width) * aspect)))
        func light(_ value: Double) -> Double { floor(min(1, max(0, value)) * 64) / 64 }
        return CelestialCloudTextureKeyV2(
            recipe: CloudTextureRecipeV2(atmosphere: clouds, weatherType: state.weatherType,
                width: width, height: height, octaves: octaves,
                seconds: reduceMotion ? 0 : floor(date.timeIntervalSince1970 / 20) * 20,
                day: light(state.solarLightLevel), twilight: light(state.twilightLevel), night: light(state.nightLevel)),
            origin: CelestialCloudTextureOriginV2(
                latitude: (state.latitudeDegrees * 100).rounded(.toNearestOrEven) / 100,
                longitude: (state.longitudeDegrees * 100).rounded(.toNearestOrEven) / 100,
                source: state.dataFreshness.weatherSource))
    }
}

private struct CelestialCloudTextureOriginV2: Equatable, Sendable {
    let latitude: Double
    let longitude: Double
    let source: String?
}

private struct CelestialCloudTextureKeyV2: Equatable, Sendable {
    let recipe: CloudTextureRecipeV2
    let origin: CelestialCloudTextureOriginV2
}

@MainActor
private final class CelestialCloudTextureModelV2: ObservableObject {
    struct Frame {
        let key: CelestialCloudTextureKeyV2
        let image: CGImage
    }
    @Published private(set) var current: Frame?
    private(set) var previous: Frame?
    private(set) var switchedAt: TimeInterval = 0
    private var desired: CelestialCloudTextureKeyV2?
    private var generation: UInt64 = 0

    func clear() {
        generation &+= 1
        desired = nil
        previous = nil
        current = nil
    }

    func update(_ key: CelestialCloudTextureKeyV2?) async {
        guard let key else { clear(); return }
        if current?.key == key { return }
        generation &+= 1
        let token = generation
        desired = key
        if current?.key.origin != key.origin { previous = nil; current = nil }
        let work = Task.detached(priority: .utility) {
            CelestialCloudTextureV2.rasterize(recipe: key.recipe) { Task.isCancelled }
        }
        let pixels = await withTaskCancellationHandler {
            await work.value
        } onCancel: {
            work.cancel()
        }
        guard !Task.isCancelled, token == generation, desired == key,
              let pixels, let image = Self.image(pixels, width: key.recipe.width, height: key.recipe.height) else { return }
        previous = current
        switchedAt = ProcessInfo.processInfo.systemUptime
        current = Frame(key: key, image: image)
    }

    private static func image(_ pixels: [UInt32], width: Int, height: Int) -> CGImage? {
        var rgba = [UInt8]()
        rgba.reserveCapacity(pixels.count * 4)
        for pixel in pixels {
            let alpha = (pixel >> 24) & 255
            // Explicit RGBA premultiplication; never depend on native endian byte layout.
            rgba.append(UInt8((((pixel >> 16) & 255) * alpha + 127) / 255))
            rgba.append(UInt8((((pixel >> 8) & 255) * alpha + 127) / 255))
            rgba.append(UInt8(((pixel & 255) * alpha + 127) / 255))
            rgba.append(UInt8(alpha))
        }
        guard let provider = CGDataProvider(data: Data(rgba) as CFData),
              let colorSpace = CGColorSpace(name: CGColorSpace.sRGB) else { return nil }
        return CGImage(width: width, height: height, bitsPerComponent: 8, bitsPerPixel: 32,
            bytesPerRow: width * 4, space: colorSpace,
            bitmapInfo: CGBitmapInfo(rawValue: CGImageAlphaInfo.premultipliedLast.rawValue),
            provider: provider, decode: nil, shouldInterpolate: true, intent: .defaultIntent)
    }
}
