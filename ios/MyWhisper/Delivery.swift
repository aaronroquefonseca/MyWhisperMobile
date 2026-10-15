import UIKit

/// Result delivery for iOS.
///
/// - Clipboard: `UIPasteboard` copy, then the user pastes wherever they
///   want (Notes, Messages, …).
/// - Share: `UIActivityViewController` sheet (Messages, Mail, Files, …).
/// - In-app: `ResultView` renders the text directly.
/// - History: auto-appended by the core — `finish_utterance` writes the
///   history row (when `privacy.history_enabled`) BEFORE returning, so by
///   the time `transcriptionDidFinish` fires the row is already durable.
///   No separate call is needed; `recentHistory` below is just a reader.
///
/// NO direct-insert: iOS sandboxes apps — there is no API for one app to
/// commit text into another app's focused field (no
/// `InputConnection.commitText` equivalent outside a custom keyboard
/// extension, which this v1 does not ship). Copy/share + in-app result is
/// the whole delivery surface, by platform constraint rather than choice.
enum Delivery {
    /// Copy text to the general pasteboard. Returns false for blank input
    /// (nothing to copy) so callers can disable the button instead.
    @discardableResult
    static func copyToClipboard(_ text: String) -> Bool {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard trimmed.isEmpty == false else { return false }
        UIPasteboard.general.string = trimmed
        return true
    }

    /// Share-sheet items for a transcript (plain text only).
    static func shareItems(for text: String) -> [Any] {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard trimmed.isEmpty == false else { return [] }
        return [trimmed]
    }

    /// Present the share sheet from a bar button or source view (required
    /// anchor on iPad — without it the app crashes with "no source").
    static func presentShareSheet(
        for text: String,
        from presenter: UIViewController,
        sourceView: UIView? = nil,
        barButtonItem: UIBarButtonItem? = nil
    ) {
        let items = shareItems(for: text)
        guard items.isEmpty == false else { return }
        let sheet = UIActivityViewController(activityItems: items, applicationActivities: nil)
        if let popover = sheet.popoverPresentationController {
            if let anchor = barButtonItem {
                popover.barButtonItem = anchor
            } else if let view = sourceView ?? presenter.view {
                popover.sourceView = view
                popover.sourceRect = CGRect(
                    x: view.bounds.midX, y: view.bounds.midY, width: 0, height: 0
                )
            }
        }
        presenter.present(sheet, animated: true)
    }

    /// Read back recent history rows (core-owned, newest first). Throws on
    /// bridge errors; callers surface via `mapSttError`.
    static func recentHistory(limit: Int32 = 50) throws -> [[String: Any]] {
        try CoreBridge.history(limit: limit)
    }
}
