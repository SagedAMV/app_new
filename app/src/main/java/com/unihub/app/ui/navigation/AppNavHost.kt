package com.unihub.app.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.unihub.app.feature.backup.BackupScreen
import com.unihub.app.feature.dashboard.DashboardScreen
import com.unihub.app.feature.files.FilesScreen
import com.unihub.app.feature.files.capture.CameraCaptureScreen
import com.unihub.app.feature.galaxy.GalaxyScreen
import com.unihub.app.feature.planner.PlannerScreen
import com.unihub.app.feature.settings.SettingsScreen

/**
 * جذر التنقل: سقالة واحدة بشريط سفلي يظهر في الوجهات الأربع الرئيسية فقط،
 * وواجهات الإعدادات/النسخ الاحتياطي تُفتح فوقها بشريط علوي وزر رجوع.
 */
@Composable
fun AppNavHost() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination

    val showBottomBar = bottomBarRoutes.any { routeClass ->
        currentDestination?.hasRoute(routeClass) == true
    }

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                AppBottomBar(navController, currentDestination)
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = DashboardRoute,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable<DashboardRoute> {
                DashboardScreen(
                    onOpenPlanner = { tab -> navController.navigate(PlannerRoute(tab)) },
                    onOpenFiles = { folderId -> navController.navigate(FilesRoute(folderId)) },
                    onOpenSettings = { navController.navigate(SettingsRoute) }
                )
            }

            composable<FilesRoute> { entry ->
                val route = entry.toRoute<FilesRoute>()
                FilesScreen(
                    folderId = route.folderId,
                    onOpenFolder = { id -> navController.navigate(FilesRoute(id)) },
                    onBack = { navController.popBackStack() },
                    onOpenCamera = { navController.navigate(CameraCaptureRoute(route.folderId)) }
                )
            }

            composable<CameraCaptureRoute> { entry ->
                val route = entry.toRoute<CameraCaptureRoute>()
                CameraCaptureScreen(
                    folderId = route.folderId,
                    onBack = { navController.popBackStack() }
                )
            }

            composable<PlannerRoute> { entry ->
                val route = entry.toRoute<PlannerRoute>()
                PlannerScreen(initialTab = route.tab)
            }

            composable<GalaxyRoute> {
                GalaxyScreen(
                    onOpenFolder = { id -> navController.navigate(FilesRoute(id)) }
                )
            }

            composable<SettingsRoute> {
                SettingsScreen(
                    onBack = { navController.popBackStack() },
                    onOpenBackup = { navController.navigate(BackupRoute) }
                )
            }

            composable<BackupRoute> {
                BackupScreen(onBack = { navController.popBackStack() })
            }
        }
    }
}
