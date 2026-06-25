// Adaptive (iPad vs iPhone) sizing helpers for the SwiftUI views.
//
// The iOS app was built phone-first: fonts, paddings, and sheet detents are all
// tuned for a ~390 pt-wide screen, so on a full-size iPad everything reads small
// and the modal sheets present as little floating cards. Rather than maintain a
// separate iPad layout, each view reads `horizontalSizeClass` and scales a
// handful of constants up when it is `.regular` (a full-screen iPad, or a wide
// multitasking split). A narrow iPad Split View window stays `.compact` and so
// keeps the phone sizing — which is what its width warrants.

import SwiftUI

/// Tunables shared by the adaptive-sizing helpers below.
enum AdaptiveMetrics {
    /// Multiplier applied to phone-tuned point sizes / paddings on iPad
    /// (`.regular` width). Picked to lift the dense, phone-sized terminal/overview
    /// chrome to a comfortable size on a 11"/13" canvas without overflowing the
    /// fractionally-sized mini-panes.
    static let padScale: CGFloat = 1.4
}

/// Adaptive helpers hung off the optional `UserInterfaceSizeClass` that
/// `@Environment(\.horizontalSizeClass)` vends. Kept on the `Optional` itself so
/// callers can write `hSize.scaled(9)` directly without unwrapping.
///
/// - SeeAlso: `AdaptiveMetrics`
extension Optional where Wrapped == UserInterfaceSizeClass {
    /// True on a roomy (`.regular`-width) canvas — i.e. iPad / wide split — that
    /// should adopt the larger, scaled-up sizing. False on compact iPhone-width.
    var isRegularWidth: Bool { self == .regular }

    /// Picks the `regular` (iPad) value on a roomy canvas, else the `compact`
    /// (iPhone) value. Used for non-numeric choices such as semantic `Font`s or
    /// sheet detent sets.
    ///
    /// - Parameters:
    ///   - compact: value used on iPhone-width (`.compact`) layouts.
    ///   - regular: value used on iPad-width (`.regular`) layouts.
    /// - Returns: `regular` when `isRegularWidth`, otherwise `compact`.
    func pick<T>(_ compact: T, _ regular: T) -> T { self == .regular ? regular : compact }

    /// Scales a phone-tuned point metric up by `AdaptiveMetrics.padScale` on
    /// iPad, leaving it untouched on iPhone.
    ///
    /// - Parameter base: the phone-tuned point size or padding.
    /// - Returns: `base * padScale` on a regular-width canvas, else `base`.
    func scaled(_ base: CGFloat) -> CGFloat {
        self == .regular ? base * AdaptiveMetrics.padScale : base
    }
}
