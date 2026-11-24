import Foundation

enum KeyboardKeyGeometry {
    static func nearestIndex(at point: CGPoint, in keys: [CGRect]) -> Int? {
        var nearest: Int?
        var bestDistance = CGFloat.infinity
        for (index, key) in keys.enumerated() {
            if key.contains(point) { return index }
            let dx = max(key.minX - point.x, point.x - key.maxX, 0)
            let dy = max(key.minY - point.y, point.y - key.maxY, 0)
            let distance = dx * dx + dy * dy
            if distance < bestDistance {
                bestDistance = distance
                nearest = index
            }
        }
        return nearest
    }
}
