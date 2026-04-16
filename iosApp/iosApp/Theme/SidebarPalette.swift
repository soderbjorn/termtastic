import SwiftUI
import UIKit

/// Adaptive colour palette matching the web client's `:root` (dark) and
/// `body.appearance-light` CSS blocks. Follows the system appearance setting.
enum Palette {
    // Dark
    private static let backgroundDark   = UIColor(Color(hex: 0xFF1C1C1E))
    private static let surfaceDark      = UIColor(Color(hex: 0xFF2C2C2E))
    private static let textPrimaryDark  = UIColor(Color(hex: 0xFFF5F5F5))
    private static let textSecondaryDark = UIColor(Color(hex: 0xFF8E8E93))

    // Light — matches web `body.appearance-light`
    private static let backgroundLight   = UIColor(Color(hex: 0xFFF5F5F7))
    private static let surfaceLight      = UIColor(Color(hex: 0xFFFFFFFF))
    private static let textPrimaryLight  = UIColor(Color(hex: 0xFF1C1C1E))
    private static let textSecondaryLight = UIColor(Color(hex: 0xFF6E6E73))

    // Dots are the same in both themes
    static let dotWorking   = Color(hex: 0xFF5AC8FA)
    static let dotWaiting   = Color(hex: 0xFFFF6961)
    static let headerAccent = Color(red: 0xF4/255.0, green: 0xB8/255.0, blue: 0x69/255.0, opacity: 0.75)

    // Adaptive accessors — resolve at render time based on system appearance
    static var background: Color {
        Color(UIColor { $0.userInterfaceStyle == .dark ? backgroundDark : backgroundLight })
    }
    static var surface: Color {
        Color(UIColor { $0.userInterfaceStyle == .dark ? surfaceDark : surfaceLight })
    }
    static var textPrimary: Color {
        Color(UIColor { $0.userInterfaceStyle == .dark ? textPrimaryDark : textPrimaryLight })
    }
    static var textSecondary: Color {
        Color(UIColor { $0.userInterfaceStyle == .dark ? textSecondaryDark : textSecondaryLight })
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
}
