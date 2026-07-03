import SwiftUI
import Client

/// Appearance + theme picker sheet for the iOS sessions view.
///
/// Brings the Mac/Electron app's appearance toggle + theme manager to mobile in
/// a deliberately simple form: pick the appearance (Auto / Light / Dark) and tap
/// a theme thumbnail to assign it to the currently-active slot. Long-pressing a
/// theme opens a context menu to star / unstar it (issue #107); the catalog is a
/// single list with no "Dark"/"Light" headings, ordered starred dark → starred
/// light → unstarred dark → unstarred light. There is no semantic-colour editing
/// and no clone/delete.
///
/// Every choice routes through `AppearanceViewModel` → the shared
/// `ThemeBackingViewModel`, which writes the same canonical server selection the
/// desktop writes, so the change persists and syncs to every connected client.
/// The sheet stays open after a change so the user can preview the live repaint.
///
/// Mirrors the Android `AppearanceSheet` and the structure of the iOS
/// `LayoutSheet` (a sheet of native thumbnails). `ThemeThumbnail` ports the
/// token → region mapping of the web `buildThemeThumb` silhouette.
///
/// **Presentation (issue #99).** On iPhone the sheet keeps its phone-native
/// bottom detents (`.medium` / `.large`). On a full-screen iPad the default
/// `.sheet` would host the browser in a narrow, centred form-sheet card that
/// leaves most of a landscape canvas empty ("the theme browser is tiny"), so on
/// a regular-width presenter it is promoted to a large, page-sized presentation
/// that uses the available space — see ``AppearanceSheetSizing``. The device is
/// distinguished by the *presenting* view's `horizontalSizeClass`, passed in as
/// ``presentingSizeClass``, because the sheet's own environment always reports
/// `.compact` inside the iPad form-sheet container and so cannot tell the two
/// apart on its own.
///
/// - SeeAlso: `AppearanceViewModel`
/// - SeeAlso: `LayoutSheet`
/// - SeeAlso: `AppearanceSheetSizing`
struct AppearanceSheet: View {
    @Bindable var viewModel: AppearanceViewModel

    /// The **presenting** view's horizontal size class (`TreeView`'s), captured
    /// at the call site and passed in explicitly rather than read from this
    /// sheet's own `@Environment`. On iPad a modal sheet is hosted in a narrow
    /// (~540 pt) form-sheet container that always reports `.compact`, so an
    /// in-sheet size-class read can never tell an iPad apart from an iPhone;
    /// driving the presentation sizing off the presenter's class lets a
    /// full-screen iPad (`.regular`) get a roomy page-sized sheet while iPhone
    /// keeps its bottom detents (issue #99).
    let presentingSizeClass: UserInterfaceSizeClass?

    @Environment(\.dismiss) private var dismiss

    /// The system dark-mode flag; decides which slot is active while the
    /// appearance is Auto, and which colour scheme the nav bar needs.
    @Environment(\.colorScheme) private var colorScheme

    /// The theme grid's columns, chosen by the *presenting* device (issue #99).
    ///
    /// On a roomy iPad canvas (`presentingSizeClass == .regular`) the sheet is
    /// promoted to a large page-sized presentation, so a fixed 2-up layout would
    /// blow each thumbnail up to half the width and waste the space — the owner's
    /// "this wastes a lot of space, we could fit many more themes by making the
    /// thumbnails smaller" feedback. An `.adaptive` layout with a compact minimum
    /// therefore packs as many smaller cards per row as the width allows (roughly
    /// 6–9 across a landscape iPad), while iPhone keeps its familiar 2-up grid.
    private var columns: [GridItem] {
        presentingSizeClass == .regular
            ? [GridItem(.adaptive(minimum: 132), spacing: 12)]
            : [GridItem(.flexible(), spacing: 12),
               GridItem(.flexible(), spacing: 12)]
    }

    /// Guards the one-time "scroll to the active theme when the sheet opens"
    /// behaviour (issue #105). The theme catalog is populated asynchronously by
    /// `viewModel.start()`, so the scroll is triggered once — either at
    /// `onAppear` (if the list is already loaded) or when `orderedThemes` first
    /// arrives — and never again, so it does not fight the user scrolling away.
    @State private var didScrollToActive = false

    /// The appearance options shown in the segmented control, in display order.
    private let appearanceOptions: [(label: String, value: Client.Appearance)] = [
        ("Auto", .auto_), ("Light", .light), ("Dark", .dark),
    ]

    var body: some View {
        NavigationStack {
            ScrollViewReader { proxy in
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    VStack(alignment: .leading, spacing: 8) {
                        // Subheading, styled identically to the "Dark themes" /
                        // "Light themes" section headers.
                        Text("Dark mode")
                            .font(.headline)
                            .fontWeight(.semibold)
                            .foregroundStyle(Palette.textBright)
                        appearancePicker
                    }
                    themeGrid
                }
                .padding(16)
            }
            // Centre the active theme in view the first time the sheet's catalog
            // is available, so opening the picker reveals the current theme
            // rather than starting at the top (issue #105). `orderedThemes` loads
            // asynchronously, so trigger from whichever fires with data first.
            .onChange(of: viewModel.orderedThemes.count) { _, _ in
                scrollToActiveIfNeeded(proxy)
            }
            .onAppear { scrollToActiveIfNeeded(proxy) }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(Palette.background)
            .navigationTitle("Appearance")
            .navigationBarTitleDisplayMode(.inline)
            .toolbarBackground(Palette.background, for: .navigationBar)
            // The bar's content scheme must follow the *theme's* surface, not a
            // hard-coded `.dark` — otherwise the title renders white-on-light on
            // light themes (issue #95).
            .toolbarColorScheme(
                Palette.backgroundIsDark(systemIsDark: colorScheme == .dark) ? .dark : .light,
                for: .navigationBar
            )
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Done") { dismiss() }
                        .foregroundStyle(Palette.headerAccent)
                }
            }
        }
        .onAppear { viewModel.start() }
        .onDisappear { viewModel.stop() }
        // Size the presentation per device: bottom detents on iPhone, a large
        // page-sized sheet on iPad so the browser fills a landscape canvas
        // instead of a tiny centred card (issue #99).
        .modifier(AppearanceSheetSizing(isRegularWidth: presentingSizeClass == .regular))
    }

    /// The Auto / Light / Dark segmented control.
    private var appearancePicker: some View {
        HStack(spacing: 8) {
            ForEach(appearanceOptions, id: \.label) { option in
                let selected = viewModel.appearance == option.value
                Button {
                    viewModel.setAppearance(option.value)
                } label: {
                    Text(option.label)
                        .font(.subheadline)
                        .fontWeight(selected ? .semibold : .regular)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 8)
                        .background(
                            RoundedRectangle(cornerRadius: 8)
                                .fill(selected ? Palette.headerAccent : Palette.surface)
                        )
                        .foregroundStyle(selected ? Palette.background : Palette.textSecondary)
                }
                .buttonStyle(.plain)
            }
        }
    }

    /// The theme name assigned to the *active* slot (the one currently painted,
    /// per the appearance preference / system dark flag). Only this card gets
    /// the assigned-theme highlight, matching the Mac/Electron theme manager.
    private var activeThemeName: String {
        viewModel.activeSlotIsDark(systemIsDark: colorScheme == .dark)
            ? viewModel.darkThemeName
            : viewModel.lightThemeName
    }

    /// The single, unheaded grid of theme cards (issue #107). Tapping a card
    /// assigns that theme to the currently-active slot (issue #97); long-pressing
    /// opens the star / unstar context menu. The list order (starred first) is
    /// computed by the backing VM.
    private var themeGrid: some View {
        LazyVGrid(columns: columns, spacing: 12) {
            ForEach(viewModel.orderedThemes, id: \.name) { theme in
                ThemeCardView(
                    theme: theme,
                    selected: theme.name == activeThemeName,
                    favorite: viewModel.isFavorite(name: theme.name),
                    onTap: {
                        viewModel.setActiveTheme(
                            name: theme.name,
                            systemIsDark: colorScheme == .dark
                        )
                    },
                    onToggleFavorite: {
                        viewModel.toggleFavorite(name: theme.name)
                    }
                )
                // Stable scroll anchor so ScrollViewReader can centre the active
                // theme on open (issue #105); `\.name` is the ForEach identity.
                .id(theme.name)
            }
        }
    }

    /// Scrolls the active theme's card to the vertical centre of the grid, once
    /// per sheet presentation (issue #105).
    ///
    /// Called from `onAppear` and from `onChange(of: orderedThemes.count)` — the
    /// catalog loads asynchronously via `viewModel.start()`, so whichever fires
    /// first with a non-empty list performs the scroll; `didScrollToActive` then
    /// suppresses every later call so the user's own scrolling is left alone.
    ///
    /// - Parameter proxy: the enclosing `ScrollViewReader`'s scroll proxy.
    private func scrollToActiveIfNeeded(_ proxy: ScrollViewProxy) {
        guard !didScrollToActive, !viewModel.orderedThemes.isEmpty else { return }
        didScrollToActive = true
        let target = activeThemeName
        guard viewModel.orderedThemes.contains(where: { $0.name == target }) else { return }
        proxy.scrollTo(target, anchor: .center)
    }
}

/// Chooses how ``AppearanceSheet`` fills the screen, per device (issue #99).
///
/// Applied to the sheet's root. On an iPhone-width presenter it keeps the
/// phone-native bottom detents (`.medium` / `.large`) plus in-place grid
/// scrolling, so a half sheet still reveals the live theme repaint behind it. On
/// a roomy iPad canvas the default `.sheet` hosts the browser in a narrow,
/// centred form-sheet card — the "tiny in landscape" complaint — so this promotes
/// it to a large, page-sized presentation that uses the available space:
/// `.presentationSizing(.page)` on iOS 18+, with a generous minimum content frame
/// (which grows the form sheet toward the screen bounds) as the iOS 17 fallback.
///
/// Called by ``AppearanceSheet`` with the *presenting* view's size class, since
/// the sheet's own environment reports `.compact` inside the iPad form-sheet
/// container and so cannot be trusted to detect the device.
///
/// - SeeAlso: `AppearanceSheet`
private struct AppearanceSheetSizing: ViewModifier {
    /// True when the presenting view is regular-width (a full-screen iPad), and
    /// the sheet should therefore adopt the large, page-sized presentation.
    let isRegularWidth: Bool

    /// Applies the device-appropriate presentation-sizing modifiers.
    ///
    /// - Parameter content: the ``AppearanceSheet`` root being sized.
    /// - Returns: the content with iPad page sizing or iPhone detents applied.
    func body(content: Content) -> some View {
        if isRegularWidth {
            if #available(iOS 18.0, *) {
                content.presentationSizing(.page)
            } else {
                // iOS 17 fallback: an explicit large content frame expands the
                // iPad form sheet toward the screen bounds.
                content.frame(minWidth: 704, minHeight: 900)
            }
        } else {
            content
                .presentationDetents([.medium, .large])
                // Scroll the theme grid in place instead of letting a scroll
                // gesture expand the sheet to `.large` — so it stays at the
                // medium detent and the live theme preview behind it remains
                // visible while browsing. The grabber still resizes it manually.
                .presentationContentInteraction(.scrolls)
        }
    }
}

/// One theme card: the theme name above its ``ThemeThumbnail``. When `selected`
/// the thumbnail is encircled with an accent ring (the assigned-theme highlight);
/// when `favorite` a filled star badge sits in the thumbnail's top-right corner.
///
/// Tapping assigns the theme; long-pressing opens a context menu to star / unstar
/// it (issue #107).
private struct ThemeCardView: View {
    let theme: Client.Theme
    let selected: Bool
    let favorite: Bool
    let onTap: () -> Void
    let onToggleFavorite: () -> Void

    var body: some View {
        Button(action: onTap) {
            VStack(spacing: 4) {
                Text(theme.name)
                    .font(.system(size: 11))
                    .lineLimit(1)
                    .foregroundStyle(selected ? Palette.headerAccent : Palette.textPrimary)
                ThemeThumbnail(resolved: theme.resolve())
                    .aspectRatio(1.5, contentMode: .fit)
                    .clipShape(RoundedRectangle(cornerRadius: 6))
                    .overlay(
                        RoundedRectangle(cornerRadius: 6)
                            .stroke(selected ? Palette.headerAccent : Color.clear, lineWidth: 1.5)
                    )
                    // Filled star badge, top-right, so favorites are recognisable
                    // at a glance (they're also hoisted to the top of the list).
                    .overlay(alignment: .topTrailing) {
                        if favorite {
                            Image(systemName: "star.fill")
                                .font(.system(size: 10))
                                .foregroundStyle(Palette.headerAccent)
                                .padding(3)
                        }
                    }
            }
        }
        .buttonStyle(.plain)
        // Long-press context menu to star / unstar (issue #107).
        .contextMenu {
            Button {
                onToggleFavorite()
            } label: {
                Label(
                    favorite ? "Unstar theme" : "Star theme",
                    systemImage: favorite ? "star.slash" : "star"
                )
            }
        }
    }
}

/// Draws a miniature app silhouette coloured entirely from `resolved` — a tab
/// strip (with one active tab), a sidebar column, a focused pane with a titlebar
/// and a few syntax-coloured code lines, and a bottom accent strip. The token →
/// region mapping mirrors the web `buildThemeThumb` and the Android
/// `ThemeThumbnail`, so a card previews the real app chrome at a glance.
struct ThemeThumbnail: View {
    let resolved: Client.ResolvedTheme

    var body: some View {
        Canvas { context, size in
            let w = size.width
            let h = size.height

            func c(_ v: Int64) -> Color { Color(argb: v) }
            func fillRect(_ rect: CGRect, _ color: Color, radius: CGFloat = 0) {
                context.fill(RoundedRectangle(cornerRadius: radius).path(in: rect), with: .color(color))
            }
            func strokeRect(_ rect: CGRect, _ color: Color, radius: CGFloat, width: CGFloat) {
                context.stroke(RoundedRectangle(cornerRadius: radius).path(in: rect), with: .color(color), lineWidth: width)
            }

            // Canvas background.
            fillRect(CGRect(x: 0, y: 0, width: w, height: h), c(resolved.bg))

            // Tab strip.
            let tabH = h * 0.18
            fillRect(CGRect(x: 0, y: 0, width: w, height: tabH), c(resolved.surfaceAlt))
            let tabW = w * 0.22
            let tabPad = w * 0.02
            let tabInsetY = tabH * 0.22
            for i in 0..<2 {
                let x = tabPad + CGFloat(i) * (tabW + tabPad)
                fillRect(CGRect(x: x, y: tabInsetY, width: tabW, height: tabH - tabInsetY * 2),
                         c(resolved.textDim).opacity(0.5), radius: 2)
            }
            let activeX = tabPad + 2 * (tabW + tabPad)
            let activeTab = CGRect(x: activeX, y: tabInsetY, width: tabW, height: tabH - tabInsetY * 2)
            fillRect(activeTab, c(resolved.surface), radius: 2)
            strokeRect(activeTab, c(resolved.accent), radius: 2, width: 1.5)

            // Body region.
            let bodyTop = tabH
            let bodyH = h - tabH

            // Sidebar.
            let sidebarW = w * 0.24
            fillRect(CGRect(x: 0, y: bodyTop, width: sidebarW, height: bodyH), c(resolved.surface))
            let lineH = bodyH * 0.07
            for i in 0..<4 {
                let y = bodyTop + bodyH * 0.12 + CGFloat(i) * (lineH * 2)
                fillRect(CGRect(x: sidebarW * 0.18, y: y, width: sidebarW * 0.64, height: lineH),
                         c(resolved.textDim).opacity(0.7), radius: 1.5)
            }

            // Focused pane.
            let paneX = sidebarW + w * 0.05
            let paneY = bodyTop + bodyH * 0.12
            let paneW = w - paneX - w * 0.06
            let paneH = bodyH * 0.7
            let pane = CGRect(x: paneX, y: paneY, width: paneW, height: paneH)
            fillRect(pane, c(resolved.surface), radius: 3)
            // Titlebar.
            let titleH = paneH * 0.22
            fillRect(CGRect(x: paneX, y: paneY, width: paneW, height: titleH), c(resolved.surfaceAlt), radius: 3)
            fillRect(CGRect(x: paneX + paneW * 0.08, y: paneY + titleH * 0.32, width: paneW * 0.4, height: titleH * 0.36),
                     c(resolved.textBright), radius: 1.5)
            // Syntax-coloured code lines.
            let codeColors = [c(resolved.synKeyword), c(resolved.synString), c(resolved.text), c(resolved.synFunction)]
            let codeLineH = paneH * 0.09
            for i in 0..<4 {
                let y = paneY + titleH + paneH * 0.12 + CGFloat(i) * (codeLineH * 1.7)
                let lineW = paneW * (0.7 - CGFloat(i) * 0.1)
                fillRect(CGRect(x: paneX + paneW * 0.08, y: y, width: lineW, height: codeLineH),
                         codeColors[i], radius: 1.5)
            }
            // Accent focus ring.
            strokeRect(pane, c(resolved.accent), radius: 3, width: 1.5)

            // Bottom accent strip.
            fillRect(CGRect(x: 0, y: h - h * 0.05, width: w, height: h * 0.05), c(resolved.accent))
        }
    }
}
