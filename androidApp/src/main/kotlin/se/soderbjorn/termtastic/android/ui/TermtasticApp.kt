/**
 * Main app composable and navigation graph for the Termtastic Android app.
 *
 * Defines the [TermtasticApp] composable which sets up a Jetpack Navigation
 * [NavHost] with horizontal-slide transitions. Routes include hosts selection,
 * tree overview, terminal sessions, file browser (list and content), and git
 * (list and diff). All destinations are driven by URI-style route parameters
 * for pane IDs, session IDs, and file paths.
 *
 * On connection, fetches the user's [UiSettings] from the server and provides
 * them via [LocalUiSettings] so that all screens can access the selected theme
 * without independent network calls.
 *
 * Called from [se.soderbjorn.termtastic.android.MainActivity] as the root
 * composable inside the Material theme.
 *
 * @see se.soderbjorn.termtastic.android.MainActivity
 * @see LocalUiSettings
 */
package se.soderbjorn.termtastic.android.ui

import android.content.Context
import android.net.Uri
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.NavType
import androidx.navigation.navArgument
import se.soderbjorn.termtastic.android.net.ConnectionHolder
import se.soderbjorn.termtastic.client.UiSettings
import se.soderbjorn.termtastic.client.fetchUiSettings

/**
 * Top-level navigation host for the Termtastic Android app.
 *
 * Sets up a Jetpack Navigation [NavHost] with horizontal-slide enter/exit
 * transitions so forward navigation slides right-to-left and back pops
 * left-to-right, matching the native "push" feel. Routes include:
 * - `hosts` -- [HostsScreen] (start destination)
 * - `tree` -- [TreeScreen]
 * - `terminal/{sessionId}` -- [TerminalScreen]
 * - `files/{paneId}[/{dirRelPath}]` -- [FileBrowserListScreen]
 * - `file/{paneId}/{relPath}` -- [FileBrowserContentScreen]
 * - `git/{paneId}[/{filePath}]` -- [GitListScreen] / [GitDiffScreen]
 *
 * Fetches [UiSettings] from the server when a client connection is available
 * and provides them via [LocalUiSettings] so all descendant composables
 * (sidebar screens, terminal, diff, file browser) can access the user's
 * selected theme.
 *
 * @param applicationContext the Android application context, forwarded to
 *   [HostsScreen] for repository instantiation.
 * @see se.soderbjorn.termtastic.android.MainActivity
 * @see LocalUiSettings
 */
@Composable
fun TermtasticApp(applicationContext: Context) {
    val navController = rememberNavController()

    var uiSettings by remember { mutableStateOf<UiSettings?>(null) }
    // Incremented on each successful connection to trigger a UiSettings fetch.
    var connectionGeneration by remember { mutableStateOf(0) }

    LaunchedEffect(connectionGeneration) {
        if (connectionGeneration > 0) {
            uiSettings = ConnectionHolder.client()?.fetchUiSettings()
        }
    }

    CompositionLocalProvider(LocalUiSettings provides uiSettings) {
        NavHost(
            navController = navController,
            startDestination = "hosts",
            enterTransition = {
                slideInHorizontally(
                    initialOffsetX = { it },
                    animationSpec = tween(durationMillis = 260),
                )
            },
            exitTransition = {
                slideOutHorizontally(
                    targetOffsetX = { -it / 4 },
                    animationSpec = tween(durationMillis = 260),
                )
            },
            popEnterTransition = {
                slideInHorizontally(
                    initialOffsetX = { -it / 4 },
                    animationSpec = tween(durationMillis = 260),
                )
            },
            popExitTransition = {
                slideOutHorizontally(
                    targetOffsetX = { it },
                    animationSpec = tween(durationMillis = 260),
                )
            },
        ) {
            composable("hosts") {
                HostsScreen(
                    applicationContext = applicationContext,
                    onConnected = {
                        connectionGeneration++
                        navController.navigate("tree")
                    },
                )
            }
            composable("tree") {
                TreeScreen(
                    onOpenTerminal = { sessionId ->
                        navController.navigate("terminal/$sessionId")
                    },
                    onOpenFileBrowser = { paneId ->
                        navController.navigate("files/${Uri.encode(paneId)}")
                    },
                    onOpenGit = { paneId ->
                        navController.navigate("git/${Uri.encode(paneId)}")
                    },
                    onDisconnect = {
                        uiSettings = null
                        navController.popBackStack("hosts", inclusive = false)
                    },
                )
            }
            composable(
                route = "terminal/{sessionId}",
                arguments = listOf(navArgument("sessionId") { type = NavType.StringType }),
            ) { backStackEntry ->
                val sessionId = backStackEntry.arguments?.getString("sessionId") ?: return@composable
                TerminalScreen(
                    sessionId = sessionId,
                    onBack = { navController.popBackStack() },
                )
            }
            composable(
                route = "files/{paneId}",
                arguments = listOf(navArgument("paneId") { type = NavType.StringType }),
            ) { backStackEntry ->
                val paneId = backStackEntry.arguments?.getString("paneId") ?: return@composable
                FileBrowserListScreen(
                    paneId = paneId,
                    dirRelPath = "",
                    onOpenDir = { child ->
                        navController.navigate("files/${Uri.encode(paneId)}/${Uri.encode(child)}")
                    },
                    onOpenFile = { relPath ->
                        navController.navigate("file/${Uri.encode(paneId)}/${Uri.encode(relPath)}")
                    },
                    onBack = { navController.popBackStack() },
                )
            }
            composable(
                route = "files/{paneId}/{dirRelPath}",
                arguments = listOf(
                    navArgument("paneId") { type = NavType.StringType },
                    navArgument("dirRelPath") { type = NavType.StringType },
                ),
            ) { backStackEntry ->
                val paneId = backStackEntry.arguments?.getString("paneId") ?: return@composable
                val dirRelPath = backStackEntry.arguments?.getString("dirRelPath") ?: ""
                FileBrowserListScreen(
                    paneId = paneId,
                    dirRelPath = dirRelPath,
                    onOpenDir = { child ->
                        navController.navigate("files/${Uri.encode(paneId)}/${Uri.encode(child)}")
                    },
                    onOpenFile = { relPath ->
                        navController.navigate("file/${Uri.encode(paneId)}/${Uri.encode(relPath)}")
                    },
                    onBack = { navController.popBackStack() },
                )
            }
            composable(
                route = "file/{paneId}/{relPath}",
                arguments = listOf(
                    navArgument("paneId") { type = NavType.StringType },
                    navArgument("relPath") { type = NavType.StringType },
                ),
            ) { backStackEntry ->
                val paneId = backStackEntry.arguments?.getString("paneId") ?: return@composable
                val relPath = backStackEntry.arguments?.getString("relPath") ?: return@composable
                FileBrowserContentScreen(
                    paneId = paneId,
                    relPath = relPath,
                    onBack = { navController.popBackStack() },
                )
            }
            composable(
                route = "git/{paneId}",
                arguments = listOf(navArgument("paneId") { type = NavType.StringType }),
            ) { backStackEntry ->
                val paneId = backStackEntry.arguments?.getString("paneId") ?: return@composable
                GitListScreen(
                    paneId = paneId,
                    onOpenFile = { filePath ->
                        navController.navigate("git/${Uri.encode(paneId)}/${Uri.encode(filePath)}")
                    },
                    onBack = { navController.popBackStack() },
                )
            }
            composable(
                route = "git/{paneId}/{filePath}",
                arguments = listOf(
                    navArgument("paneId") { type = NavType.StringType },
                    navArgument("filePath") { type = NavType.StringType },
                ),
            ) { backStackEntry ->
                val paneId = backStackEntry.arguments?.getString("paneId") ?: return@composable
                val filePath = backStackEntry.arguments?.getString("filePath") ?: return@composable
                GitDiffScreen(
                    paneId = paneId,
                    filePath = filePath,
                    onBack = { navController.popBackStack() },
                )
            }
        }
    }
}
