import SwiftUI
import UIKit

/// Which history rows are on screen, kept apart from `ActivityView` so a scroll only redraws the scroller.
@MainActor
final class HistoryScrollTracker: ObservableObject {
    @Published private(set) var visible: Set<String> = []
    @Published private(set) var headerVisible = true

    func row(_ id: String, shown: Bool) {
        if shown { visible.insert(id) } else { visible.remove(id) }
    }

    func header(shown: Bool) {
        if headerVisible != shown { headerVisible = shown }
    }
}

/// Android's history fast scroller: a thin track with a notch where each day starts and a thumb
/// on the trailing edge, shown once the list is past the history header. Dragging widens the
/// thumb, shows the day in a bubble and ticks at each new day. Rows past the loaded pages are
/// estimated from the per-day counts so the thumb holds still as pages arrive; dropping there
/// loads through that row, then scrolls to it.
struct HistoryFastScroller: View {
    @Environment(\.colorScheme) private var colorScheme
    @ObservedObject var tracker: HistoryScrollTracker
    @ObservedObject var model: HistoryViewModel
    let proxy: ScrollViewProxy

    @State private var dragging = false
    @State private var dragFraction: CGFloat = 0
    @State private var lastLabel: String?
    /// Row dropped past the loaded pages, waiting for them to load.
    @State private var pending: Int?
    @State private var ticks = UISelectionFeedbackGenerator()

    private static let thumbHeight: CGFloat = 44

    var body: some View {
        let loaded = model.days.flatMap(\.items).map(\.id)
        let labels = Self.rows(days: model.days, activity: model.activity, estimateRest: !model.reachedEnd && model.searchText.isEmpty)
        let shown = labels.count >= 2 && (dragging || pending != nil || (!tracker.headerVisible && !tracker.visible.isEmpty))
        GeometryReader { geometry in
            let track = geometry.size.height
            let fraction = dragging || pending != nil ? dragFraction : scrolledFraction(loaded: loaded, rows: labels.count)
            let thumbY = (track - Self.thumbHeight) * fraction
            ZStack(alignment: .topTrailing) {
                if dragging, let label = labels[safe: Self.row(at: fraction, rows: labels.count)] {
                    Text(label)
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(.white)
                        .padding(.horizontal, 14)
                        .padding(.vertical, 10)
                        .background(WhisperColors.primary(colorScheme), in: Capsule())
                        .shadow(radius: 3)
                        .offset(x: -40, y: thumbY)
                        .allowsHitTesting(false)
                }
                ZStack(alignment: .top) {
                    notches(labels: labels, track: track)
                    Capsule()
                        .fill(dragging ? WhisperColors.primary(colorScheme) : Color.secondary)
                        .frame(width: dragging ? 10 : 5, height: Self.thumbHeight)
                        .offset(y: thumbY)
                        .animation(.easeOut(duration: 0.15), value: dragging)
                }
                .frame(width: 12, height: track)
                .padding(.trailing, 4)
                .frame(width: 28, height: track, alignment: .trailing)
                .contentShape(Rectangle())
                .gesture(drag(track: track, labels: labels, loaded: loaded))
            }
            .frame(width: geometry.size.width, height: track, alignment: .topTrailing)
        }
        .frame(width: dragging ? 260 : 28)
        .opacity(shown ? 1 : 0)
        .allowsHitTesting(shown)
        .animation(.easeInOut(duration: 0.2), value: shown)
        .accessibilityHidden(true)
        .onChange(of: model.items.count) { _, _ in settle(loaded: model.days.flatMap(\.items).map(\.id)) }
        .onChange(of: model.reachedEnd) { _, _ in settle(loaded: model.days.flatMap(\.items).map(\.id)) }
        // A new search replaces the rows: drop a pending jump.
        .onChange(of: model.searchText) { _, _ in pending = nil }
    }

    private func notches(labels: [String], track: CGFloat) -> some View {
        Canvas { context, size in
            let x = size.width / 2
            var line = Path()
            line.move(to: CGPoint(x: x, y: 0))
            line.addLine(to: CGPoint(x: x, y: size.height))
            context.stroke(line, with: .color(.secondary.opacity(0.35)), lineWidth: 2)
            // A notch where each day starts, skipped when too close to the last one.
            let usable = size.height - Self.thumbHeight
            var lastY: CGFloat = -100
            var marks = Path()
            for index in labels.indices.dropFirst() where labels[index] != labels[index - 1] {
                let y = Self.thumbHeight / 2 + usable * Self.fraction(of: index, rows: labels.count)
                guard y - lastY >= 6 else { continue }
                marks.move(to: CGPoint(x: x - 4, y: y))
                marks.addLine(to: CGPoint(x: x + 4, y: y))
                lastY = y
            }
            context.stroke(marks, with: .color(.secondary), lineWidth: 1.5)
        }
        .frame(height: track)
    }

    private func drag(track: CGFloat, labels: [String], loaded: [String]) -> some Gesture {
        DragGesture(minimumDistance: 0)
            .onChanged { value in
                if !dragging {
                    dragging = true
                    lastLabel = nil
                    ticks.prepare()
                }
                dragFraction = min(max((value.location.y - Self.thumbHeight / 2) / max(track - Self.thumbHeight, 1), 0), 1)
                let row = Self.row(at: dragFraction, rows: labels.count)
                if let label = labels[safe: row], label != lastLabel {
                    if lastLabel != nil { ticks.selectionChanged() }
                    lastLabel = label
                }
                // Loaded rows scroll live; the rest load on release.
                if row < loaded.count {
                    proxy.scrollTo(loaded[row], anchor: .top)
                    pending = nil
                } else {
                    pending = row
                }
            }
            .onEnded { _ in
                dragging = false
                guard let row = pending else { return }
                if row < loaded.count || model.reachedEnd || !model.loadMore(atLeast: row + 1 - loaded.count) {
                    settle(loaded: loaded)
                }
            }
    }

    /// Scroll to the dropped row once it's loaded, or to the last loaded row when there's no more.
    private func settle(loaded: [String]) {
        guard let row = pending, !dragging, !loaded.isEmpty else { return }
        guard row < loaded.count || model.reachedEnd || !model.loadingMore else { return }
        proxy.scrollTo(loaded[min(row, loaded.count - 1)], anchor: .top)
        pending = nil
    }

    private func scrolledFraction(loaded: [String], rows: Int) -> CGFloat {
        guard let first = loaded.firstIndex(where: tracker.visible.contains) else { return 0 }
        return Self.fraction(of: first, rows: rows)
    }

    /// Row under a thumb at `fraction` of the track.
    static func row(at fraction: CGFloat, rows: Int) -> Int {
        rows <= 1 ? 0 : Int((min(max(fraction, 0), 1) * CGFloat(rows - 1)).rounded())
    }

    /// Thumb fraction for the first visible `row`.
    static func fraction(of row: Int, rows: Int) -> CGFloat {
        rows <= 1 ? 0 : min(max(CGFloat(row) / CGFloat(rows - 1), 0), 1)
    }

    /// The day title of every history entry in list order, then the unloaded rest from per-day
    /// counts (Android `historyScrollerRows`). Loaded rows keep their index; only the tail is an estimate.
    static func rows(days: [HistoryDay], activity: [DailyBucket], estimateRest: Bool, calendar: Calendar = .current) -> [String] {
        var rows = days.flatMap { day in Array(repeating: day.title, count: day.items.count) }
        guard estimateRest, let last = days.last, let lastDay = last.id else { return rows }
        for bucket in activity.sorted(by: { $0.dayStart > $1.dayStart }) where bucket.utterances > 0 {
            let day = calendar.startOfDay(for: Date(timeIntervalSince1970: TimeInterval(bucket.dayStart)))
            if day == lastDay {
                rows += Array(repeating: last.title, count: max(bucket.utterances - last.items.count, 0))
            } else if day < lastDay {
                rows += Array(repeating: HistoryDay(id: day, items: []).title, count: bucket.utterances)
            }
        }
        return rows
    }
}

private extension Array {
    subscript(safe index: Int) -> Element? { indices.contains(index) ? self[index] : nil }
}
