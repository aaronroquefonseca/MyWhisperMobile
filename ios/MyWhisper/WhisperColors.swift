import SwiftUI

/// Android’s fixed teal accent and neutral light and dark surfaces.
enum WhisperColors {
    static let brandNavy = Color(red: 20 / 255, green: 34 / 255, blue: 52 / 255)
    static let brandInk = Color(red: 7 / 255, green: 14 / 255, blue: 24 / 255)
    static let brandCyan = Color(red: 92 / 255, green: 217 / 255, blue: 236 / 255)

    static func primary(_ scheme: ColorScheme) -> Color {
        scheme == .dark ? Color(red: 0x80 / 255, green: 0xD9 / 255, blue: 0xBD / 255)
            : Color(red: 0x17 / 255, green: 0x66 / 255, blue: 0x57 / 255)
    }

    /// Mirrors `preferences.black_background` so every screen can read it without the settings.
    static let pureBlackKey = "pureBlackBackground"
    static var pureBlack: Bool { UserDefaults.standard.bool(forKey: pureBlackKey) }

    static func background(_ scheme: ColorScheme, pureBlack: Bool = WhisperColors.pureBlack) -> Color {
        if scheme == .dark, pureBlack { return .black }
        return scheme == .dark ? Color(red: 0x12 / 255, green: 0x14 / 255, blue: 0x16 / 255)
            : Color(red: 0xF6 / 255, green: 0xF7 / 255, blue: 0xF8 / 255)
    }

    static func surface(_ scheme: ColorScheme) -> Color {
        scheme == .dark ? Color(red: 27 / 255, green: 30 / 255, blue: 33 / 255) : .white
    }

    static func primaryContainer(_ scheme: ColorScheme) -> Color {
        scheme == .dark ? Color(red: 34 / 255, green: 58 / 255, blue: 51 / 255)
            : Color(red: 234 / 255, green: 244 / 255, blue: 239 / 255)
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

    @ViewBuilder
    func whisperTabBarBackground(_ scheme: ColorScheme) -> some View {
        #if compiler(>=6.2)
        if #available(iOS 26, *) {
            self
        } else {
            fallbackTabBarBackground(scheme)
        }
        #else
        fallbackTabBarBackground(scheme)
        #endif
    }

    private func fallbackTabBarBackground(_ scheme: ColorScheme) -> some View {
        toolbarBackground(WhisperColors.surface(scheme), for: .tabBar)
            .toolbarBackground(.visible, for: .tabBar)
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
