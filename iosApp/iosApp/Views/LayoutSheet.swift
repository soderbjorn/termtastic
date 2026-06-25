import SwiftUI
import Client

/// Layout-preset picker sheet for the iOS overview (issue #58).
///
/// Presents the same preset palette the Mac/web "Layout" dropdown and the
/// Android `LayoutSheet` offer as a scrolling 3-column grid of tiles. Each tile
/// draws a miniature of the preset's geometry for the active tab's current pane
/// count using the shared `LayoutPresetCatalog` — the *same* `computeBoxes`
/// the write path uses — so the preview matches the layout the user gets. Slot 0
/// (where the focused pane lands) is painted in the accent colour, the other
/// emphasized slots in a translucent accent, and the rest dim, mirroring the
/// web tiles' primary/secondary/other treatment. `LayoutPreset.Auto` shows a
/// wand glyph rather than a fixed geometry.
///
/// Selecting a tile routes to `OverviewBackingViewModel.applyLayoutByKey`
/// (via the hoisted `OverviewViewModel`), which authors the toolkit
/// `LAYOUT_STATE` blob.
///
/// - SeeAlso: `Client.LayoutPresetCatalog`
/// - SeeAlso: `OverviewViewModel.applyLayout(tabId:presetKey:)`
struct LayoutSheet: View {
    /// The active tab's on-canvas pane count, used to render each tile's
    /// miniature. When `0` the sheet shows an empty hint.
    let paneCount: Int
    /// The tab's persisted preset key, or `nil`. Only `Auto` is rendered as the
    /// encircled "current layout" (issue #59).
    let activePresetKey: String?
    /// Invoked with the chosen preset key; the host applies it.
    let onSelect: (String) -> Void

    @Environment(\.dismiss) private var dismiss
    @Environment(\.horizontalSizeClass) private var hSize

    /// Tiles for the current pane count, computed once per presentation by the
    /// shared catalogue (so iOS never navigates the bridged `LayoutPreset` enum).
    private var tiles: [Client.LayoutTile] {
        Client.LayoutPresetCatalog.shared.tiles(paneCount: Int32(paneCount))
    }

    /// Match the web dropdown: only Auto is ever encircled as "active".
    private var autoIsActive: Bool {
        Client.LayoutPresetCatalog.shared.isAutoKey(presetKey: activePresetKey)
    }

    private let columns = [GridItem(.flexible(), spacing: 10),
                           GridItem(.flexible(), spacing: 10),
                           GridItem(.flexible(), spacing: 10)]

    var body: some View {
        NavigationStack {
            Group {
                if paneCount <= 0 {
                    Text("No windows in this tab")
                        .font(.subheadline)
                        .foregroundStyle(Palette.textSecondary)
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                } else {
                    ScrollView {
                        LazyVGrid(columns: columns, spacing: 10) {
                            ForEach(tiles, id: \.key) { tile in
                                LayoutTileView(
                                    tile: tile,
                                    isActive: tile.isAuto && autoIsActive,
                                    onTap: {
                                        onSelect(tile.key)
                                        dismiss()
                                    }
                                )
                            }
                        }
                        .padding(16)
                    }
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(Palette.background)
            .navigationTitle("Layout")
            .navigationBarTitleDisplayMode(.inline)
            .toolbarBackground(Palette.background, for: .navigationBar)
            .toolbarColorScheme(.dark, for: .navigationBar)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Done") { dismiss() }
                        .foregroundStyle(Palette.headerAccent)
                }
            }
        }
        // iPad: present the full sheet (a `.medium` detent floats as a small
        // card there). iPhone keeps the half-sheet.
        .presentationDetents(hSize.pick([.medium, .large], [.large]))
    }
}

/// A single preset tile: a miniature preview (or a wand for Auto) plus the
/// preset's label. Mirrors the Android `LayoutTile`.
private struct LayoutTileView: View {
    let tile: Client.LayoutTile
    let isActive: Bool
    let onTap: () -> Void

    var body: some View {
        Button(action: onTap) {
            VStack(spacing: 4) {
                ZStack {
                    RoundedRectangle(cornerRadius: 6)
                        .fill(Palette.surface)
                    if tile.isAuto {
                        Image(systemName: "wand.and.stars")
                            .font(.system(size: 18))
                            .foregroundStyle(Palette.headerAccent)
                    } else {
                        PresetMiniature(boxes: tile.boxes, emphasized: Int(tile.emphasizedSlotCount))
                            .padding(6)
                    }
                }
                .aspectRatio(1.5, contentMode: .fit)
                .overlay(
                    RoundedRectangle(cornerRadius: 6)
                        .stroke(isActive ? Palette.headerAccent : Color.clear, lineWidth: 1.5)
                )
                Text(tile.label)
                    .font(.system(size: 10))
                    .lineLimit(1)
                    .foregroundStyle(isActive ? Palette.headerAccent : Palette.textSecondary)
            }
        }
        .buttonStyle(.plain)
    }
}

/// Draws a preset's geometry boxes as rounded rects, ranked by colour (slot 0
/// accent, other emphasized slots translucent accent, the rest dim) — the
/// SwiftUI analogue of the Android `PresetMiniature` Canvas / the web tiles' SVG.
private struct PresetMiniature: View {
    let boxes: [Client.LayoutBox]
    let emphasized: Int

    var body: some View {
        Canvas { context, size in
            let gap: CGFloat = 1.5
            for (i, b) in boxes.enumerated() {
                let color: Color = i == 0
                    ? Palette.headerAccent
                    : (i < emphasized ? Palette.headerAccent.opacity(0.45)
                                      : Palette.textSecondary.opacity(0.4))
                let x = CGFloat(b.x) * size.width + gap
                let y = CGFloat(b.y) * size.height + gap
                let w = max(2, CGFloat(b.width) * size.width - gap * 2)
                let h = max(2, CGFloat(b.height) * size.height - gap * 2)
                let rect = CGRect(x: x, y: y, width: w, height: h)
                context.fill(
                    RoundedRectangle(cornerRadius: 2).path(in: rect),
                    with: .color(color)
                )
            }
        }
    }
}
