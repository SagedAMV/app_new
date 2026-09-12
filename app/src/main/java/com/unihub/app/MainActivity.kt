package com.unihub.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.unihub.app.feature.settings.ThemeViewModel
import com.unihub.app.ui.navigation.AppNavHost
import com.unihub.app.ui.theme.UniHubTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* رفض الإذن لا يعطل التطبيق — التذكيرات فقط لن تظهر */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestNotificationPermissionIfNeeded()
        setContent { UniHubApp() }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                permissionLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
            }
        }
    }
}

/**
 * جذر الواجهة: يطبق السمة المحفوظة ثم يستضيف مخطط التنقل.
 * النشاط نفسه لا يحمل أي حالة تطبيق — كل الحالة في وجهات التنقل.
 */
@Composable
fun UniHubApp(themeViewModel: ThemeViewModel = hiltViewModel()) {
    val themeMode by themeViewModel.themeMode.collectAsStateWithLifecycle()
    val useDynamicColor by themeViewModel.useDynamicColor.collectAsStateWithLifecycle()

    UniHubTheme(mode = themeMode, useDynamicColor = useDynamicColor) {
        AppNavHost()
    }
}
