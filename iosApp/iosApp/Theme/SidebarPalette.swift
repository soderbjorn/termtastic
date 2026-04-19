import SwiftUI
import UIKit
import Client

/// Adaptive colour palette derived from the Termtastic semantic theme system.
///
/// Resolves the default theme (Tron) for the current system appearance.
/// Screens with access to the shared ViewModel should resolve from the
/// user's actual selected theme instead.
enum Palette {
    /// Resolves the default Tron palette for the given appearance.
    private static func defaultPalette(isDark: Bool) -> Client.ResolvedPalette {
        let themes = Client.ThemesKt.recommendedThemes
        let tron = themes.first { ($0 as! Client.TerminalTheme).name == Client.ThemesKt.DEFAULT_THEME_NAME } as! Client.TerminalTheme
        return Client.ThemeResolverKt.resolve(tron, isDark: isDark)
    }

    /// Theme accent colour derived from the terminal foreground.
    static var headerAccent: Color {
        let pal = defaultPalette(isDark: UITraitCollection.current.userInterfaceStyle == .dark)
        return Color(argb: pal.accent.primary).opacity(0.75)
    }

    // Adaptive accessors — resolve at render time based on system appearance
    static var background: Color {
        Color(UIColor { traitCollection in
            let isDark = traitCollection.userInterfaceStyle == .dark
            let pal = defaultPalette(isDark: isDark)
            return UIColor(Color(argb: pal.sidebar.bg))
        })
    }
    static var surface: Color {
        Color(UIColor { traitCollection in
            let isDark = traitCollection.userInterfaceStyle == .dark
            let pal = defaultPalette(isDark: isDark)
            return UIColor(Color(argb: pal.surface.raised))
        })
    }
    static var textPrimary: Color {
        Color(UIColor { traitCollection in
            let isDark = traitCollection.userInterfaceStyle == .dark
            let pal = defaultPalette(isDark: isDark)
            return UIColor(Color(argb: pal.sidebar.text))
        })
    }
    static var textSecondary: Color {
        Color(UIColor { traitCollection in
            let isDark = traitCollection.userInterfaceStyle == .dark
            let pal = defaultPalette(isDark: isDark)
            return UIColor(Color(argb: pal.sidebar.textDim))
        })
    }
}

extension Color {
    /// Create a Color from an ARGB UInt64 like `0xFF1C1C1E`.
    init(hex: UInt64) {
        let a = Double((hex >> 24) & 0xFF) / 255.0
        let r = Double((hex >> 16) & 0xFF) / 255.0
        let g = Double((hex >> 8) & 0xFF) / 255.0
        let b = Double(hex & 0xFF) / 255.0
        self.init(.sRGB, red: r, green: g, blue: b, opacity: a)
    }

    /// Create a Color from a Kotlin Long ARGB value (bridged as Int64).
    init(argb: Int64) {
        self.init(hex: UInt64(bitPattern: argb))
    }
}
