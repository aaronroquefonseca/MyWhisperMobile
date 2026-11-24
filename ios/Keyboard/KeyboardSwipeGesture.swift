import UIKit
import UIKit.UIGestureRecognizerSubclass

/// Remains possible during a tap or stationary hold. Once motion crosses
/// the threshold, UIKit cancels the originating key's button tracking.
final class KeyboardSwipeGesture: UIGestureRecognizer {
    private(set) var points: [CGPoint] = []
    private(set) var pauses: [CGPoint] = []
    private var slowSample: (point: CGPoint, time: TimeInterval)?
    private var activeTouch: UITouch?
    private let minimumDistance: CGFloat = 20

    override func touchesBegan(_ touches: Set<UITouch>, with event: UIEvent) {
        guard activeTouch == nil, touches.count == 1, let touch = touches.first else {
            state = state == .possible ? .failed : .cancelled
            return
        }
        activeTouch = touch
        let origin = touch.location(in: view)
        points.append(origin)
        slowSample = (origin, touch.timestamp)
    }

    override func touchesMoved(_ touches: Set<UITouch>, with event: UIEvent) {
        guard let touch = activeTouch, touches.contains(touch), let origin = points.first else { return }
        for sample in event.coalescedTouches(for: touch) ?? [touch] {
            let point = sample.location(in: view)
            recordPause(at: point, timestamp: sample.timestamp)
            append(point)
        }
        guard let current = points.last else { return }
        if state == .possible {
            let dx = current.x - origin.x
            let dy = current.y - origin.y
            if dx * dx + dy * dy >= minimumDistance * minimumDistance { state = .began }
        } else if state == .began || state == .changed {
            state = .changed
        }
    }

    override func touchesEnded(_ touches: Set<UITouch>, with event: UIEvent) {
        guard let touch = activeTouch, touches.contains(touch) else { return }
        append(touch.location(in: view))
        state = state == .possible ? .failed : .ended
    }

    override func touchesCancelled(_ touches: Set<UITouch>, with event: UIEvent) {
        state = state == .possible ? .failed : .cancelled
    }

    override func reset() {
        super.reset()
        activeTouch = nil
        points.removeAll(keepingCapacity: true)
        pauses.removeAll(keepingCapacity: true)
        slowSample = nil
    }

    private func recordPause(at point: CGPoint, timestamp: TimeInterval) {
        guard let sample = slowSample, squaredDistance(point, sample.point) <= 49 else {
            slowSample = (point, timestamp)
            return
        }
        if timestamp - sample.time >= 0.035, pauses.count < 12,
           pauses.last.map({ squaredDistance(point, $0) > 120 }) != false {
            pauses.append(point)
        }
    }

    private func squaredDistance(_ a: CGPoint, _ b: CGPoint) -> CGFloat {
        let dx = a.x - b.x
        let dy = a.y - b.y
        return dx * dx + dy * dy
    }

    private func append(_ point: CGPoint) {
        if let previous = points.last, squaredDistance(point, previous) < 4 { return }
        // Bound work for pathological gestures without dropping the endpoint.
        if points.count >= 256 { points.remove(at: 1) }
        points.append(point)
    }
}
