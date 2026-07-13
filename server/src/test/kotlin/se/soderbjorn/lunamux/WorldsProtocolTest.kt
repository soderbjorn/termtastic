/**
 * Unit tests for the Worlds protocol plumbing: the version gate
 * ([clientSupportsWorlds]), the old-client flatten transform
 * ([flattenToFirstWorld]), and the `WorldConfig` JSON round-trip through
 * [windowJson].
 *
 * These guard the "Part E" boundary cases: a client reporting < 1.9 (or no
 * version) is treated as world-unaware and receives only the default world's
 * tabs with no `worlds` array; a >= 1.9 client is world-aware; and a
 * worlds-bearing config survives serialization while an old-shaped blob
 * (only `tabs`) still decodes.
 */
package se.soderbjorn.lunamux

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WorldsProtocolTest {

    private fun world(id: String, vararg tabIds: String, active: String? = tabIds.firstOrNull()) =
        WorldConfig(
            id = id,
            name = id,
            tabs = tabIds.map { TabConfig(id = it, title = it) },
            activeTabId = active,
        )

    @Test
    fun clientSupportsWorlds_boundaryCases() {
        assertFalse(clientSupportsWorlds(null), "null version → world-unaware")
        assertFalse(clientSupportsWorlds(""), "blank version → world-unaware")
        assertFalse(clientSupportsWorlds("1.8.0"))
        assertFalse(clientSupportsWorlds("1.8.9"))
        assertTrue(clientSupportsWorlds("1.9.0"))
        assertTrue(clientSupportsWorlds("1.9.0-beta"))
        assertTrue(clientSupportsWorlds("1.10.0"))
        assertTrue(clientSupportsWorlds("2.0.0"))
    }

    @Test
    fun flattenToFirstWorld_exposesDefaultWorldAndDropsWorlds() {
        val cfg = WindowConfig(
            // Legacy mirror already tracks the first world.
            tabs = listOf(TabConfig(id = "a1", title = "a1")),
            activeTabId = "a1",
            worlds = listOf(
                world("wA", "a1", "a2"),
                world("wB", "b1"),
            ),
            activeWorldId = "wB", // active is the SECOND world…
        )

        val flat = cfg.flattenToFirstWorld()

        // …but an old client is pinned to the FIRST world's tabs, never the active one.
        assertEquals(listOf("a1", "a2"), flat.tabs.map { it.id })
        assertEquals("a1", flat.activeTabId)
        assertTrue(flat.worlds.isEmpty(), "no worlds array reaches an old client")
        assertNull(flat.activeWorldId)
    }

    @Test
    fun worldConfig_roundTrips_throughWindowJson() {
        val cfg = WindowConfig(
            worlds = listOf(
                world("w-lastlight", "demo-t1"),
                WorldConfig(
                    id = "w-darknessirc",
                    name = "DarknessIRC",
                    tabs = listOf(TabConfig(id = "dirc-t1", title = "channels")),
                    activeTabId = "dirc-t1",
                    themeSelection = WorldThemeSelection(
                        darkThemeName = "Amber CRT",
                        lightThemeName = "Sepia",
                    ),
                ),
            ),
            activeWorldId = "w-darknessirc",
        )

        val json = windowJson.encodeToString(WindowConfig.serializer(), cfg)
        val back = windowJson.decodeFromString(WindowConfig.serializer(), json)

        assertEquals(2, back.worlds.size)
        assertEquals("w-darknessirc", back.activeWorldId)
        val irc = assertNotNull(back.worlds.firstOrNull { it.id == "w-darknessirc" })
        assertEquals("Amber CRT", irc.themeSelection?.darkThemeName)
        assertEquals("Sepia", irc.themeSelection?.lightThemeName)
        assertNull(back.worlds.first().themeSelection)
    }

    @Test
    fun oldShapedBlob_withOnlyTabs_stillDecodes() {
        // A pre-worlds (v3) config: only the flat `tabs`, no `worlds` array.
        val legacy = """{"tabs":[{"id":"t1","title":"Tab 1","panes":[]}],"activeTabId":"t1"}"""
        val cfg = windowJson.decodeFromString(WindowConfig.serializer(), legacy)
        assertEquals(1, cfg.tabs.size)
        assertEquals("t1", cfg.activeTabId)
        assertTrue(cfg.worlds.isEmpty())
        assertNull(cfg.activeWorldId)
    }
}
