package com.unihub.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import javax.inject.Inject
import com.unihub.app.data.cloud.CloudSyncManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.unihub.app.feature.auth.AuthGateScreen
import com.unihub.app.feature.settings.ThemeViewModel
import com.unihub.app.ui.navigation.AppNavHost
import com.unihub.app.ui.theme.UniHubTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var cloudSyncManager: CloudSyncManager

    override fun onStart() {
        super.onStart()
        cloudSyncManager.setForeground(true)
    }

    override fun onStop() {
        cloudSyncManager.setForeground(false)
        super.onStop()
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* رفض الإذن لا يعطل التطبيق — التذكيرات فقط لن تظهر */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { UniHubApp(onRequestNotifications = ::requestNotificationPermissionIfNeeded) }
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
 *
 * جولة تعليمات.md: أزيل توجيه إشعار الملفات الجديدة إلى شاشة السحابة؛ فتح السحابة
 * محصور الآن في زرّي الشريط العلوي (الرئيسية والملفات)، والإشعار يفتح التطبيق فقط.
 */
@Composable
fun UniHubApp(
    themeViewModel: ThemeViewModel = hiltViewModel(),
    onRequestNotifications: () -> Unit = {}
) {
    val themeMode by themeViewModel.themeMode.collectAsStateWithLifecycle()
    val useDynamicColor by themeViewModel.useDynamicColor.collectAsStateWithLifecycle()

    UniHubTheme(mode = themeMode, useDynamicColor = useDynamicColor) {
        AuthGateScreen {
            NotificationPermissionExplanation(onRequestNotifications)
            AppNavHost()
        }
    }
}


/** Public UI preference only; no identifiers, sessions or credentials are stored here. */
@Composable
private fun NotificationPermissionExplanation(onRequest: () -> Unit) {
    val context = LocalContext.current
    val preferences = remember(context) { context.getSharedPreferences("permission_prompts", android.content.Context.MODE_PRIVATE) }
    var show by rememberSaveable {
        mutableStateOf(Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !preferences.getBoolean("notifications_explained", false) &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
    }
    val dismiss = { preferences.edit().putBoolean("notifications_explained", true).apply(); show = false }
    if (show) AlertDialog(
        onDismissRequest = dismiss,
        title = { Text("هل تريد تنبيهات التطبيق؟") },
        text = { Text("الإشعارات تفيد في التذكيرات وتقدم نقل الملفات. يمكنك رفضها والاستمرار، أو تفعيلها لاحقًا من إعدادات النظام.") },
        confirmButton = { TextButton(onClick = { dismiss(); onRequest() }) { Text("السماح بالإشعارات") } },
        dismissButton = { TextButton(onClick = dismiss) { Text("لاحقًا") } }
    )
}
