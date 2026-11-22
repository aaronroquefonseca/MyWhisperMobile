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
    /// Card surface: real Liquid Glass on the iOS 26 SDK, system material
    /// on older toolchains (the macOS VM still builds on Xcode 15.4).
    /// Single swap point for the whole app.
    func whisperSurface(cornerRadius: CGFloat = 16) -> some View {
        whisperGlass(cornerRadius: cornerRadius)
    }

    /// iOS 26 minimize-on-scroll tab bar; no-op on older SDKs (and on
    /// toolchains that can't see the API at all).
    @ViewBuilder
    func whisperTabBarMinimize() -> some View {
        #if compiler(>=6.2)
        if #available(iOS 26, *) {
            self.tabBarMinimizeBehavior(.automatic)
        } else {
            self
        }
        #else
        self
        #endif
    }
    /// Frosted glass in the given rounded shape: `.glassEffect` where the
    /// SDK has it, ultra-thin material + hairline where it doesn't.
    @ViewBuilder
    func whisperGlass(cornerRadius: CGFloat = 24) -> some View {
        #if compiler(>=6.2)
        if #available(iOS 26, *) {
            self.glassEffect(.regular, in: RoundedRectangle(cornerRadius: cornerRadius, style: .continuous))
        } else {
            self.modifier(GlassFallback(cornerRadius: cornerRadius))
        }
        #else
        self.modifier(GlassFallback(cornerRadius: cornerRadius))
        #endif
    }
}

/// Pre-glass fallback: frosted material + hairline, same shape language.
struct GlassFallback: ViewModifier {
    var cornerRadius: CGFloat = 24

    func body(content: Content) -> some View {
        content
            .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: cornerRadius, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
                    .stroke(.white.opacity(0.22), lineWidth: 1)
            )
    }
}
