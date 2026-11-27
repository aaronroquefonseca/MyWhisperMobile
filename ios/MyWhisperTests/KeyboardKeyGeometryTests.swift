import UIKit
import XCTest

final class KeyboardKeyGeometryTests: XCTestCase {
    func testGapChoosesClosestKeyOnEitherSide() {
        let keys = [
            CGRect(x: 0, y: 0, width: 32, height: 44),
            CGRect(x: 38, y: 0, width: 32, height: 44),
            CGRect(x: 19, y: 54, width: 32, height: 44),
        ]
        XCTAssertEqual(KeyboardKeyGeometry.nearestIndex(at: CGPoint(x: 34, y: 22), in: keys), 0)
        XCTAssertEqual(KeyboardKeyGeometry.nearestIndex(at: CGPoint(x: 36, y: 22), in: keys), 1)
        XCTAssertEqual(KeyboardKeyGeometry.nearestIndex(at: CGPoint(x: 25, y: 51), in: keys), 2)
    }

    func testClosestEdgeWinsForWideSpacebar() {
        let keys = [
            CGRect(x: 0, y: 0, width: 200, height: 44),
            CGRect(x: 206, y: 0, width: 45, height: 44),
        ]
        XCTAssertEqual(KeyboardKeyGeometry.nearestIndex(at: CGPoint(x: 202, y: 22), in: keys), 0)
        XCTAssertEqual(KeyboardKeyGeometry.nearestIndex(at: CGPoint(x: 220, y: 22), in: keys), 1)
    }

    @MainActor
    func testGapRoutesToButtonAndAcceptsRelease() {
        let area = KeyboardKeyArea(frame: CGRect(x: 0, y: 0, width: 120, height: 140))
        let first = KeyboardKeyButton(frame: CGRect(x: 4, y: 46, width: 33, height: 44))
        let second = KeyboardKeyButton(frame: CGRect(x: 43, y: 46, width: 33, height: 44))
        area.addKey(first)
        area.addKey(second)
        let gap = CGPoint(x: 41, y: 66)

        XCTAssertTrue(area.hitTest(gap, with: nil) === second)
        XCTAssertTrue(second.point(inside: second.convert(gap, from: area), with: nil))
        XCTAssertFalse(first.point(inside: first.convert(gap, from: area), with: nil))
        XCTAssertNil(area.nearestKey(at: CGPoint(x: 41, y: 14)))
    }

    @MainActor
    func testControllerLoadsTypingSurface() throws {
        let controller = KeyboardViewController()
        controller.loadViewIfNeeded()
        let input = try XCTUnwrap(controller.inputView as? KeyboardInputView)
        input.frame = CGRect(x: 0, y: 0, width: 390, height: 260)
        input.setNeedsLayout()
        input.layoutIfNeeded()
        let keys = try XCTUnwrap(input.subviews.compactMap { $0 as? KeyboardKeyArea }.first)
        let key = try XCTUnwrap(keys.subviews.first { $0.accessibilityIdentifier == "q" })

        controller.viewDidLayoutSubviews()

        XCTAssertTrue(keys.subviews.first { $0.accessibilityIdentifier == "q" } === key)
    }

    func testNoKeys() {
        XCTAssertNil(KeyboardKeyGeometry.nearestIndex(at: .zero, in: []))
    }
}
