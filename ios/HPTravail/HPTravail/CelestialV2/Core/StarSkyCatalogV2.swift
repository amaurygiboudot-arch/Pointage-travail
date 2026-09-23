import Foundation

struct ConstellationPathV2: Sendable {
    let abbreviation: String
    let hrNumbers: [Int]
}

struct StarSkyCatalogV2: Sendable {
    let stars: [(hr: Int, star: BrightStarV2)]
    let constellationPaths: [ConstellationPathV2]
}

enum StarSkyCatalogLoaderV2 {
    private static let lock = NSLock()
    nonisolated(unsafe) private static var cached: StarSkyCatalogV2?

    static func load(bundle: Bundle = .main) -> StarSkyCatalogV2? {
        lock.lock()
        defer { lock.unlock() }
        if let cached { return cached }

        guard let starsURL = resourceURL(
            bundle: bundle,
            name: "bsc5p_v2",
            extension: "tsv"
        ),
        let linesURL = resourceURL(
            bundle: bundle,
            name: "constellation_lines_v2",
            extension: "txt"
        ),
        let starsText = try? String(contentsOf: starsURL, encoding: .utf8),
        let linesText = try? String(contentsOf: linesURL, encoding: .utf8)
        else {
            return nil
        }

        let parsed = parse(starsText: starsText, linesText: linesText)
        guard !parsed.stars.isEmpty, !parsed.constellationPaths.isEmpty else { return nil }
        cached = parsed
        return parsed
    }

    static func parse(starsText: String, linesText: String) -> StarSkyCatalogV2 {
        let stars: [(Int, BrightStarV2)] = starsText
            .split(whereSeparator: \.isNewline)
            .compactMap { raw in
                if raw.isEmpty || raw.first == "#" { return nil }
                let parts = raw.split(separator: "\t", omittingEmptySubsequences: false)
                guard parts.count >= 4,
                      let hr = Int(parts[0]),
                      let ra = Double(parts[1]),
                      let dec = Double(parts[2]),
                      let magnitude = Double(parts[3]) else {
                    return nil
                }
                return (
                    hr,
                    BrightStarV2(
                        id: "HR\(hr)",
                        rightAscensionJ2000Degrees: ra,
                        declinationJ2000Degrees: dec,
                        visualMagnitude: magnitude,
                        constellation: nil,
                        commonName: nil
                    )
                )
            }

        let paths: [ConstellationPathV2] = linesText
            .split(whereSeparator: \.isNewline)
            .compactMap { raw in
                if raw.isEmpty || raw.first == "#" { return nil }
                let parts = raw.split(separator: "|", maxSplits: 1)
                guard parts.count == 2 else { return nil }
                let abbreviation = String(parts[0])
                let hrs = parts[1].split(separator: ",").compactMap { Int($0) }
                guard !abbreviation.isEmpty, hrs.count >= 2 else { return nil }
                return ConstellationPathV2(abbreviation: abbreviation, hrNumbers: hrs)
            }

        return StarSkyCatalogV2(stars: stars, constellationPaths: paths)
    }

    private static func resourceURL(
        bundle: Bundle,
        name: String,
        extension ext: String
    ) -> URL? {
        bundle.url(forResource: name, withExtension: ext)
            ?? bundle.url(
                forResource: name,
                withExtension: ext,
                subdirectory: "CelestialV2/Resources"
            )
    }
}
