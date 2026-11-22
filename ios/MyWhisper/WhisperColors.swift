import SwiftUI

/// Brand color tokens ported from Android `WhisperTheme` (teal/ink), so the
/// iPhone app reads as the same app, not system-blue SwiftUI.
///
/// Android mapping (`WhisperTheme.kt`):
/// - Light primary `#176657`, dark primary `#80D9BD`.
/// - Light background `#F6F7F8` / surface white; dark background/surface
///   `#121416` (the neutral override — wallpaper dynamic color has no iOS
///   equivalent, so both modes use the fixed spec).
/// - Error `#B3261E` (`mw_error`) on both modes.
///
/// System coherence stays: system fonts/Dynamic Type, SF Symbols,
/// `.secondary` copy, standard lists/cards. When the toolchain moves to
/// the iOS 26 SDK, surfaces should adopt Liquid Glass in one place
/// (see `whisperSurface`) — Xcode 15.4 cannot compile glass APIs.
enum WhisperColors {
    static func primary(_ scheme: ColorScheme) -> Color {
        scheme == .dark ? Color(red: 0x80 / 255, green: 0xD9 / 255, blue: 0xBD / 255)
            : Color(red: 0x17 / 255, green: 0x66 / 255, blue: 0x57 / 255)
    }

    static func background(_ scheme: ColorScheme) -> Color {
        scheme == .dark ? Color(red: 0x12 / 255, green: 0x14 / 255, blue: 0x16 / 255)
            : Color(red: 0xF6 / 255, green: 0xF7 / 255, blue: 0xF8 / 255)
    }

    static func surface(_ scheme: ColorScheme) -> Color {
        scheme == .dark ? Color(red: 0x12 / 255, green: 0x14 / 255, blue: 0x16 / 255)
            : .white
    }

    static func onSurface(_ scheme: ColorScheme) -> Color {
        scheme == .dark ? Color(red: 0xE7 / 255, green: 0xE9 / 255, blue: 0xEC / 255)
            : Color(red: 0x20 / 255, green: 0x29 / 255, blue: 0x2B / 255)
    }

    static func onSurfaceVariant(_ scheme: ColorScheme) -> Color {
        scheme == .dark ? Color(red: 0xBA / 255, green: 0xC1 / 255, blue: 0xC8 / 255)
            : Color(red: 0x5F / 255, green: 0x6F / 255, blue: 0x70 / 255)
    }

    /// Android `mw_error`, both modes.
    static let error = Color(red: 0xB3 / 255, green: 0x26 / 255, blue: 0x1E / 255)
}

extension View {
    /// Card surface: system material in a rounded rect today (coherent on
    /// iOS 17+), the single swap point for Liquid Glass once the SDK allows.
    func whisperSurface(cornerRadius: CGFloat = 16) -> some View {
        background(.regularMaterial, in: RoundedRectangle(cornerRadius: cornerRadius, style: .continuous))
    }
}
