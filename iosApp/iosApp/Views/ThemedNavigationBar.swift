//
//  ThemedNavigationBar.swift
//  iosApp
//
//  Navigation-bar title theming for the primary Lunamux screens.
//
//  Defines the `themedNavigationBar(generation:)` view modifier, which colours
//  the navigation bar's large- and inline-title text from the resolved theme so
//  the "Sessions" / "Hosts" headline stays legible on every theme.
//

import SwiftUI
import UIKit

extension View {
    /// Themes the enclosing navigation bar's title text from the resolved theme.
    ///
    /// **Why this exists.** SwiftUI's `.toolbarColorScheme` recolours only the
    /// *collapsed* inline title and the bar's default button chrome. The
    /// *expanded* large title is drawn by UIKit over a transparent bar and falls
    /// back to the system `label` colour — which stays white while the device is
    /// in dark mode even under a light theme, leaving the headline nearly
    /// invisible on a light surface (issue #49; the "Sessions" title reported as
    /// unreadable on a cream theme). Since the app deliberately avoids a
    /// `.toolbarBackground(.visible, …)` (which suppresses the large title
    /// entirely in SwiftUI), the title colour cannot be fixed with SwiftUI
    /// modifiers alone — so this reaches the live `UINavigationBar` and stamps
    /// the themed colour onto its appearance objects' title attributes.
    ///
    /// It only overrides the title *text colour*; the bar background is left to
    /// the existing `.toolbarBackground(…)` so the carefully-tuned large-title
    /// visibility behaviour is unchanged.
    ///
    /// Re-applied whenever `generation` changes — the dynamic `Palette` UIColors
    /// re-resolve on a trait (device light/dark) change on their own, but an
    /// in-app theme switch is not a trait change, so the caller passes
    /// `ThemeStore.shared.generation` to force a refresh.
    ///
    /// - Parameter generation: the theme generation; bump to re-apply after an
    ///   in-app theme/appearance change.
    /// - Returns: the view with a themed navigation-bar title.
    /// - SeeAlso: `Palette.textBrightUIColor`
    func themedNavigationBar(generation: Int) -> some View {
        background(NavigationBarTitleStyler(generation: generation))
    }
}

/// A zero-size representable that stamps the theme's title colour onto the live
/// `UINavigationBar`'s appearance objects. Hosted via `.background(…)` so it sits
/// in the same navigation hierarchy as the screen it themes.
///
/// - SeeAlso: `View/themedNavigationBar(generation:)`
private struct NavigationBarTitleStyler: UIViewControllerRepresentable {
    /// Theme generation; a change re-invokes `updateUIViewController` so the
    /// colour is re-stamped after an in-app theme switch.
    let generation: Int

    /// Creates the empty host controller whose `navigationController` is walked
    /// to reach the live bar.
    func makeUIViewController(context: Context) -> UIViewController { UIViewController() }

    /// Stamps the themed title colour onto the enclosing navigation bar's
    /// standard / scroll-edge / compact appearances.
    ///
    /// The write is deferred to the next runloop tick so it lands *after*
    /// SwiftUI has applied its own bar appearance for this update pass (which
    /// runs synchronously), otherwise SwiftUI would overwrite the colour.
    ///
    /// - Parameters:
    ///   - uiViewController: the host controller inserted by `makeUIViewController`.
    ///   - context: the representable context (unused).
    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {
        DispatchQueue.main.async {
            guard let bar = uiViewController.navigationController?.navigationBar else { return }
            let color = Palette.textBrightUIColor

            /// Stamps the themed title colour onto (a copy of) `base`, preserving
            /// its background so `.toolbarBackground` stays in charge of the fill.
            func themed(_ base: UINavigationBarAppearance) -> UINavigationBarAppearance {
                let a = base.copy() as! UINavigationBarAppearance
                a.titleTextAttributes[.foregroundColor] = color
                a.largeTitleTextAttributes[.foregroundColor] = color
                return a
            }

            // Reassign (not just mutate) so UIKit re-applies. The scroll-edge
            // appearance drives the *expanded* large title — the one that was
            // rendering near-white — so if SwiftUI left it unset, derive it from
            // the standard appearance (which carries the themed background).
            bar.standardAppearance = themed(bar.standardAppearance)
            bar.scrollEdgeAppearance = themed(bar.scrollEdgeAppearance ?? bar.standardAppearance)
            if let compact = bar.compactAppearance {
                bar.compactAppearance = themed(compact)
            }
        }
    }
}
