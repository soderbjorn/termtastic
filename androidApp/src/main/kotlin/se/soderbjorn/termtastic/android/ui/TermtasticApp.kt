package se.soderbjorn.termtastic.android.ui

import android.content.Context
import android.net.Uri
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.NavType
import androidx.navigation.navArgument

/**
 * Top-level navigation host. Four destinations, all using horizontal-slide
 * enter/exit transitions so forward navigation slides right-to-left and
 * back pops left-to-right, matching the iOS/Android "push" feel the user
 * asked for.
 */
@Composable
fun TermtasticApp(applicationContext: Context) {
    val navController = rememberNavController()

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
