import SwiftUI
import UIKit
import Client

/// Adaptive colour palette derived from the Termtastic semantic theme system.
///
/// Resolves colours from the user's selected theme via ``settings``. When
/// settings have not been loaded yet (e.g. before the first server fetch),
/// falls back to the default Tron theme. Sidebar-specific overrides are
/// respected via `sectionTheme(section: "sidebar")`.
///
/// ``settings`` is set at connect time (in `HostsView`) so that all views
/// pick up the user's theme from the start.
///
/// - SeeAlso: `Client.ThemeResolverKt.resolve`
/// - SeeAlso: `Client.UiSettings`
enum Palette {
    /// The user's UI settings fetched from the server after connection.
    /// Set by `HostsView` before navigating to the tree. All colour
    /// accessors read from this when non-nil.
    static var settings: Client.UiSettings?

    /// Resolves the sidebar palette for the given appearance, using the
    /// user's selected theme if available, falling back to Tron.
    private static func sidebarPalette(isDark: Bool) -> Client.ResolvedPalette {
        let theme: Client.TerminalTheme
        if let s = settings {
            theme = s.sectionTheme(section: "sidebar")
        } else {
            let themes = Client.ThemesKt.recommendedThemes
            theme = themes.first { ($0 as! Client.TerminalTheme).name == Client.ThemesKt.DEFAULT_THEME_NAME } as! Client.TerminalTheme
        }
        let effectiveIsDark: Bool
        if let appearance = settings?.appearance {
            switch appearance {
            case .dark: effectiveIsDark = true
            case .light: effectiveIsDark = false
            default: effectiveIsDark = isDark
            }
        } else {
            effectiveIsDark = isDark
        }
        return Client.ThemeResolverKt.resolve(theme, isDark: effectiveIsDark)
    }

    /// Theme accent colour derived from the terminal foreground.
    static var headerAccent: Color {
        let pal = sidebarPalette(isDark: UITraitCollection.current.userInterfaceStyle == .dark)
        return Color(argb: pal.accent.primary).opacity(0.75)
    }

    // Adaptive accessors — resolve at render time based on system appearance
    static var background: Color {
        Color(UIColor { traitCollection in
            let isDark = traitCollection.userInterfaceStyle == .dark
            let pal = sidebarPalette(isDark: isDark)
            return UIColor(Color(argb: pal.sidebar.bg))
        })
    }
    static var surface: Color {
        Color(UIColor { traitCollection in
            let isDark = traitCollection.userInterfaceStyle == .dark
            let pal = sidebarPalette(isDark: isDark)
            return UIColor(Color(argb: pal.surface.raised))
        })
    }
    static var textPrimary: Color {
        Color(UIColor { traitCollection in
            let isDark = traitCollection.userInterfaceStyle == .dark
            let pal = sidebarPalette(isDark: isDark)
            return UIColor(Color(argb: pal.sidebar.text))
        })
    }
    static var textSecondary: Color {
        Color(UIColor { traitCollection in
            let isDark = traitCollection.userInterfaceStyle == .dark
            let pal = sidebarPalette(isDark: isDark)
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
