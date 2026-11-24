import Foundation

/// A frequency-ranked word list indexed by its first and last physical keys.
struct KeyboardSwipeLexicon: Sendable {
    struct Entry: Sendable {
        let word: String
        let keys: [UInt8]
        let frequency: Double
    }

    fileprivate let byEndpoints: [Int: [Entry]]

    init(_ text: String) {
        var buckets: [Int: [Entry]] = [:]
        for line in text.split(separator: "\n") {
            let fields = line.split(separator: "\t", maxSplits: 1)
            guard fields.count == 2, let frequency = Double(fields[0]), frequency.isFinite else { continue }
            let word = String(fields[1])
            let keys = word.compactMap(Self.key(for:))
            guard let start = keys.first, let end = keys.last, keys.count >= 2,
                  keys.dropFirst().contains(where: { $0 != start }) else { continue }
            let entry = Entry(word: word, keys: keys, frequency: frequency)
            buckets[Self.bucket(start, end), default: []].append(entry)
        }
        byEndpoints = buckets
    }

    static func key(for character: Character) -> UInt8? {
        if let ascii = character.asciiValue {
            switch ascii {
            case 65...90: return ascii + 32
            case 97...122: return ascii
            default: return nil
            }
        }
        let letter = String(character).lowercased()
        if letter == "ñ" { return 241 }
        let folded = letter.folding(options: .diacriticInsensitive, locale: nil)
        return folded.utf8.first.flatMap { (97...122).contains($0) ? $0 : nil }
    }

    fileprivate static func bucket(_ first: UInt8, _ last: UInt8) -> Int {
        Int(first) * 256 + Int(last)
    }
}

enum KeyboardSwipeDecoder {
    private static let sampleCount = 20

    static func candidates(
        trace: [CGPoint], pauses: [CGPoint] = [], centers: [UInt8: CGPoint], pitch: CGFloat, lexicon: KeyboardSwipeLexicon
    ) -> [String] {
        guard pitch > 0, let start = trace.first, let end = trace.last,
              let input = resample(trace, minimumLength: pitch * 0.45) else { return [] }

        let endpointLimit = pitch * pitch * 1.25 * 1.25
        let startKeys = centers.filter { squaredDistance($0.value, start) <= endpointLimit }
        let endKeys = centers.filter { squaredDistance($0.value, end) <= endpointLimit }
        let scale = pitch * pitch
        var best: [(word: String, score: CGFloat)] = []

        for (first, firstCenter) in startKeys {
            for (last, lastCenter) in endKeys {
                let endpointPenalty = (squaredDistance(start, firstCenter) + squaredDistance(end, lastCenter)) / scale
                for entry in lexicon.byEndpoints[KeyboardSwipeLexicon.bucket(first, last), default: []] {
                    let path = entry.keys.compactMap { centers[$0] }
                    guard path.count == entry.keys.count, let template = resample(path, minimumLength: pitch * 0.35)
                    else { continue }
                    let lengthRatio = template.length / input.length
                    guard (0.48...1.85).contains(lengthRatio) else { continue }

                    let error = alignmentError(input.points, template.points) / scale
                    // Reject unrelated traces before word frequency can influence the result.
                    guard error < 0.73 else { continue }
                    // An intentional slowdown helps resolve straight runs across
                    // several adjacent keys with otherwise identical geometry.
                    let pausePenalty = pauses.reduce(CGFloat.zero) { penalty, pause in
                        let nearest = path.reduce(CGFloat.infinity) { min($0, squaredDistance($1, pause)) }
                        return penalty + min(nearest / scale, 1)
                    } / CGFloat(max(1, pauses.count))
                    let score = error + 0.36 * endpointPenalty + 0.15 * pausePenalty
                        + 0.25 * abs(log(lengthRatio)) - 0.0012 * entry.frequency
                    if best.count == 3 && score > best[2].score { continue }
                    best.append((entry.word, score))
                    best.sort { $0.score == $1.score ? $0.word < $1.word : $0.score < $1.score }
                    if best.count > 3 { best.removeLast() }
                }
            }
        }
        return best.map(\.word)
    }

    private static func alignmentError(_ input: [CGPoint], _ template: [CGPoint]) -> CGFloat {
        var error: CGFloat = 0
        for index in input.indices {
            let window = max(0, index - 1)...min(input.count - 1, index + 1)
            var inputError = CGFloat.infinity
            var templateError = CGFloat.infinity
            for offset in window {
                inputError = min(inputError, squaredDistance(input[index], template[offset]))
                templateError = min(templateError, squaredDistance(template[index], input[offset]))
            }
            error += inputError + templateError
        }
        return error / CGFloat(2 * input.count)
    }

    private static func resample(
        _ points: [CGPoint], minimumLength: CGFloat
    ) -> (points: [CGPoint], length: CGFloat)? {
        guard points.count >= 2 else { return nil }
        var distances: [CGFloat] = [0]
        distances.reserveCapacity(points.count)
        for index in 1..<points.count {
            distances.append(distances[index - 1] + sqrt(squaredDistance(points[index - 1], points[index])))
        }
        guard let length = distances.last, length.isFinite, length >= minimumLength else { return nil }

        var segment = 1
        var result = [points[0]]
        result.reserveCapacity(sampleCount)
        for index in 1..<(sampleCount - 1) {
            let target = length * CGFloat(index) / CGFloat(sampleCount - 1)
            while segment < points.count - 1, distances[segment] < target { segment += 1 }
            let start = points[segment - 1]
            let end = points[segment]
            let fraction = (target - distances[segment - 1]) / (distances[segment] - distances[segment - 1])
            result.append(CGPoint(x: start.x + fraction * (end.x - start.x), y: start.y + fraction * (end.y - start.y)))
        }
        result.append(points[points.count - 1])
        return (result, length)
    }

    private static func squaredDistance(_ a: CGPoint, _ b: CGPoint) -> CGFloat {
        let dx = a.x - b.x
        let dy = a.y - b.y
        return dx * dx + dy * dy
    }
}
