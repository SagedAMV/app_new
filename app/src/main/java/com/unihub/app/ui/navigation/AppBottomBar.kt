package com.unihub.app.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Home
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavController
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import kotlin.reflect.KClass

data class BottomNavItem(
    val label: String,
    val routeClass: KClass<*>,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val destination: Any
)

private val items = listOf(
    BottomNavItem("الرئيسية", DashboardRoute::class, Icons.Filled.Home, Icons.Outlined.Home, DashboardRoute),
    BottomNavItem("الملفات", FilesRoute::class, Icons.Filled.Folder, Icons.Outlined.Folder, FilesRoute()),
    BottomNavItem("المخطط", PlannerRoute::class, Icons.Filled.Checklist, Icons.Outlined.Checklist, PlannerRoute()),
    BottomNavItem("المجرّة", GalaxyRoute::class, Icons.Filled.AutoAwesome, Icons.Outlined.AutoAwesome, GalaxyRoute)
)

/**
 * شريط التنقل السفلي. التحسين عن المرجع: اختيار العنصر يُفحص بمطابقة المسار
 * الآمن الأنواع بدل مقارنة نصوص، والتنقل يستعمل [NavController.navigate] بكائنات.
 */
@Composable
fun AppBottomBar(navController: NavController, currentDestination: NavDestination?) {
    NavigationBar(
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 6.dp,
    ) {
        items.forEach { item ->
            val selected = currentDestination?.hasRoute(item.routeClass) == true
            // تكبير ناعم للأيقونة المختارة بنابض هادئ
            val iconScale by animateFloatAsState(
                targetValue = if (selected) 1.08f else 1f,
                animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMediumLow),
                label = "nav-icon-scale"
            )
            NavigationBarItem(
                selected = selected,
                onClick = {
                    navController.navigate(item.destination) {
                        // لا نكدّس نسخاً مكررة من نفس الوجهة
                        popUpTo(navController.graph.startDestinationId) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                icon = {
                    Icon(
                        imageVector = if (selected) item.selectedIcon else item.unselectedIcon,
                        contentDescription = item.label,
                        modifier = Modifier.graphicsLayer {
                            scaleX = iconScale
                            scaleY = iconScale
                        }
                    )
                },
                label = { Text(item.label, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal) },
                colors = NavigationBarItemDefaults.colors(
                    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                    selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    selectedTextColor = MaterialTheme.colorScheme.primary
                )
            )
        }
    }
}
