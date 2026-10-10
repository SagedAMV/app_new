package com.unihub.app.feature.auth

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.unihub.app.data.auth.AuthSessionState
import com.unihub.app.data.auth.DeviceApprovalStatus
import com.unihub.app.data.auth.DeviceChangeRequest
import com.unihub.app.data.cloud.CloudflareR2Config
import kotlinx.coroutines.delay

/**
 * البوابة الأمنية الرئيسية للتطبيق:
 * - عند أول تشغيل أو عدم وجود جلسة موثقة: تعرض شاشة تسجيل الدخول السحابي بأنيميشن متكامل
 *   ودون إظهار نص نوع الجهاز أو إصدار الأندرويد.
 * - عند تسجيل الدخول من جهاز مختلف: تعرض شاشة انتظار المشرف مع الرسالة النصية
 *   المطلوبة حرفياً («سيرد لك مشرف») وفحص تلقائي ويدوي لرد المشرف.
 * - عند توثيق الجلسة والجهاز: تفتح التطبيق تلقائياً وتعرض للمشرف (saged)
 *   حوار موافقة/رفض فوري لأي طلب تسجيل دخول من جهاز جديد.
 */
@Composable
fun AuthGateScreen(
    viewModel: AuthViewModel = hiltViewModel(),
    content: @Composable () -> Unit
) {
    val session by viewModel.sessionState.collectAsStateWithLifecycle()
    val registry by viewModel.registry.collectAsStateWithLifecycle()
    val isBusy by viewModel.isBusy.collectAsStateWithLifecycle()
    val statusMessage by viewModel.statusMessage.collectAsStateWithLifecycle()
    val errorMessage by viewModel.errorMessage.collectAsStateWithLifecycle()
    val dismissedIds by viewModel.dismissedRequestIds.collectAsStateWithLifecycle()
    val cloudSettings by viewModel.cloudSettings.collectAsStateWithLifecycle()
    val isSavingCloudSettings by viewModel.isSavingCloudSettings.collectAsStateWithLifecycle()

    AnimatedContent(
        targetState = session,
        contentKey = { state ->
            when (state) {
                AuthSessionState.Initializing -> "Initializing"
                is AuthSessionState.Unauthenticated -> "Unauthenticated"
                is AuthSessionState.WaitingAdminApproval -> "WaitingAdminApproval"
                is AuthSessionState.Authenticated -> "Authenticated"
            }
        },
        transitionSpec = {
            (fadeIn(animationSpec = tween(340, easing = FastOutSlowInEasing)) +
                slideInVertically(
                    animationSpec = tween(340, easing = FastOutSlowInEasing),
                    initialOffsetY = { it / 14 }
                ) +
                scaleIn(
                    animationSpec = tween(340, easing = FastOutSlowInEasing),
                    initialScale = 0.97f
                )).togetherWith(
                fadeOut(animationSpec = tween(260)) +
                    slideOutVertically(
                        animationSpec = tween(260),
                        targetOffsetY = { -it / 18 }
                    ) +
                    scaleOut(
                        animationSpec = tween(260),
                        targetScale = 0.98f
                    )
            )
        },
        label = "AuthGateTransition"
    ) { current ->
        when (current) {
            AuthSessionState.Initializing -> {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator()
                            Spacer(Modifier.height(14.dp))
                            Text(
                                "جارٍ التحقق من الجلسة السحابية…",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            is AuthSessionState.Unauthenticated -> {
                CloudLoginScreen(
                    isBusy = isBusy,
                    initialError = errorMessage ?: current.message,
                    statusMessage = statusMessage,
                    cloudSettingsConfigured = cloudSettings?.isConfigured == true,
                    currentAccountId = cloudSettings?.credentials?.accountId ?: CloudflareR2Config.DEFAULT_ACCOUNT_ID,
                    currentBucketName = cloudSettings?.credentials?.bucketName ?: CloudflareR2Config.DEFAULT_BUCKET_NAME,
                    isSavingCloudSettings = isSavingCloudSettings,
                    onLogin = viewModel::login,
                    onSaveCloudCredentials = viewModel::saveInitialCloudCredentials
                )
            }

            is AuthSessionState.WaitingAdminApproval -> {
                LaunchedEffect(current.username) {
                    while (true) {
                        delay(8_000L)
                        viewModel.checkPendingStatus(silent = true)
                    }
                }
                PendingAdminApprovalScreen(
                    username = current.username,
                    deviceLabel = current.requestedDevice.summaryLabel,
                    message = statusMessage ?: current.message,
                    statusNote = current.statusNote,
                    errorMessage = errorMessage,
                    isBusy = isBusy,
                    onCheckNow = { viewModel.checkPendingStatus(silent = false) },
                    onSwitchAccount = viewModel::cancelPendingAndBackToLogin
                )
            }

            is AuthSessionState.Authenticated -> {
                val isAdmin = current.user.isAdmin
                val pendingForAdmin = if (isAdmin) {
                    registry.pendingDeviceRequests
                        .firstOrNull {
                            it.status == DeviceApprovalStatus.PENDING &&
                                it.requestId !in dismissedIds
                        }
                } else {
                    null
                }

                if (pendingForAdmin != null) {
                    AdminDeviceApprovalDialog(
                        request = pendingForAdmin,
                        isBusy = isBusy,
                        onApprove = { viewModel.approveDeviceRequest(pendingForAdmin) },
                        onReject = { viewModel.rejectDeviceRequest(pendingForAdmin) },
                        onDismiss = { viewModel.dismissDeviceRequestPopup(pendingForAdmin.requestId) }
                    )
                }

                content()
            }
        }
    }
}

@Composable
private fun CloudLoginScreen(
    isBusy: Boolean,
    initialError: String?,
    statusMessage: String?,
    cloudSettingsConfigured: Boolean,
    currentAccountId: String,
    currentBucketName: String,
    isSavingCloudSettings: Boolean,
    onLogin: (String, String) -> Unit,
    onSaveCloudCredentials: (String, String, String, String) -> Unit
) {
    var username by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordVisible by rememberSaveable { mutableStateOf(false) }
    var showCloudSetup by rememberSaveable { mutableStateOf(false) }
    var cloudAccountId by rememberSaveable { mutableStateOf(currentAccountId) }
    var cloudBucket by rememberSaveable { mutableStateOf(currentBucketName) }
    var cloudAccessKey by remember { mutableStateOf("") }
    var cloudSecretKey by remember { mutableStateOf("") }

    LaunchedEffect(currentAccountId, currentBucketName) {
        cloudAccountId = currentAccountId
        cloudBucket = currentBucketName
    }

    // أنيميشن الدخول المتدرج (Staggered Entrance) لعناصر واجهة تسجيل الدخول
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        entered = true
    }

    val headerProgress by animateFloatAsState(
        targetValue = if (entered) 1f else 0f,
        animationSpec = tween(durationMillis = 460, easing = FastOutSlowInEasing),
        label = "LoginHeaderProgress"
    )
    val cardProgress by animateFloatAsState(
        targetValue = if (entered) 1f else 0f,
        animationSpec = tween(durationMillis = 520, delayMillis = 90, easing = FastOutSlowInEasing),
        label = "LoginCardProgress"
    )
    val buttonScale by animateFloatAsState(
        targetValue = if (isBusy) 0.98f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "LoginButtonScale"
    )

    // أنيميشن نبض وهالة مستمرة حول أيقونة درع الحماية
    val infiniteTransition = rememberInfiniteTransition(label = "LoginHeroPulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.94f,
        targetValue = 1.12f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "ShieldPulseScale"
    )
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.18f,
        targetValue = 0.42f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "ShieldPulseAlpha"
    )
    val floatOffsetY by infiniteTransition.animateFloat(
        initialValue = -4f,
        targetValue = 4f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "ShieldFloatOffsetY"
    )

    val canSubmit = !isBusy && username.isNotBlank() && password.isNotBlank()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding(),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // قسم الشعار والترحيب المتحرك
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.graphicsLayer {
                        alpha = headerProgress
                        translationY = (1f - headerProgress) * -36f
                        scaleX = 0.9f + (0.1f * headerProgress)
                        scaleY = 0.9f + (0.1f * headerProgress)
                    }
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(104.dp)
                            .graphicsLayer {
                                translationY = floatOffsetY
                            }
                    ) {
                        // هالة خارجية نابضة
                        Box(
                            modifier = Modifier
                                .size(96.dp)
                                .graphicsLayer {
                                    scaleX = pulseScale
                                    scaleY = pulseScale
                                    alpha = pulseAlpha
                                }
                                .clip(CircleShape)
                                .background(
                                    Brush.radialGradient(
                                        colors = listOf(
                                            MaterialTheme.colorScheme.primary,
                                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.1f)
                                        )
                                    )
                                )
                        )

                        Surface(
                            shape = MaterialTheme.shapes.extraLarge,
                            color = MaterialTheme.colorScheme.primaryContainer,
                            shadowElevation = 6.dp,
                            modifier = Modifier.size(74.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Filled.Security,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.size(36.dp)
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(14.dp))
                    Text(
                        text = "تسجيل الدخول إلى UniHub",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "أدخل بيانات حسابك السحابي للمتابعة إلى مساحة عملك الجامعية",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }

                Spacer(Modifier.height(24.dp))

                // بطاقة حقول الإدخال المتحركة (بدون أي نص يعرض نوع الجهاز أو إصدار الأندرويد)
                ElevatedCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .graphicsLayer {
                            alpha = cardProgress
                            translationY = (1f - cardProgress) * 44f
                            scaleX = 0.95f + (0.05f * cardProgress)
                            scaleY = 0.95f + (0.05f * cardProgress)
                        },
                    shape = MaterialTheme.shapes.large,
                    elevation = CardDefaults.elevatedCardElevation(defaultElevation = 4.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        OutlinedTextField(
                            value = username,
                            onValueChange = { username = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("اسم المستخدم") },
                            leadingIcon = { Icon(Icons.Filled.Person, contentDescription = null) },
                            singleLine = true,
                            enabled = !isBusy,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Text,
                                imeAction = ImeAction.Next
                            )
                        )

                        OutlinedTextField(
                            value = password,
                            onValueChange = { password = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("كلمة المرور") },
                            leadingIcon = { Icon(Icons.Filled.Lock, contentDescription = null) },
                            trailingIcon = {
                                IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                    Crossfade(
                                        targetState = passwordVisible,
                                        animationSpec = tween(220),
                                        label = "PasswordVisibilityIcon"
                                    ) { visible ->
                                        Icon(
                                            imageVector = if (visible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                            contentDescription = if (visible) "إخفاء كلمة المرور" else "إظهار كلمة المرور"
                                        )
                                    }
                                }
                            },
                            visualTransformation = if (passwordVisible) {
                                VisualTransformation.None
                            } else {
                                PasswordVisualTransformation()
                            },
                            singleLine = true,
                            enabled = !isBusy,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Password,
                                imeAction = ImeAction.Done
                            ),
                            keyboardActions = KeyboardActions(
                                onDone = {
                                    if (canSubmit) {
                                        onLogin(username, password)
                                    }
                                }
                            )
                        )

                        AnimatedVisibility(
                            visible = !initialError.isNullOrBlank(),
                            enter = expandVertically(animationSpec = tween(260)) + fadeIn(tween(260)),
                            exit = shrinkVertically(animationSpec = tween(200)) + fadeOut(tween(200))
                        ) {
                            Surface(
                                color = MaterialTheme.colorScheme.errorContainer,
                                shape = MaterialTheme.shapes.small,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = initialError.orEmpty(),
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(12.dp)
                                )
                            }
                        }

                        AnimatedVisibility(
                            visible = !statusMessage.isNullOrBlank(),
                            enter = expandVertically(animationSpec = tween(260)) + fadeIn(tween(260)),
                            exit = shrinkVertically(animationSpec = tween(200)) + fadeOut(tween(200))
                        ) {
                            Surface(
                                color = MaterialTheme.colorScheme.secondaryContainer,
                                shape = MaterialTheme.shapes.small,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = statusMessage.orEmpty(),
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(12.dp)
                                )
                            }
                        }

                        Text(
                            text = "لأول تشغيل بلا سجل مصادقة: استخدم اسم المشرف saged واختر كلمة مرور قوية من 12 خانة على الأقل.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Button(
                            onClick = { onLogin(username, password) },
                            enabled = canSubmit,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp)
                                .graphicsLayer {
                                    scaleX = buttonScale
                                    scaleY = buttonScale
                                }
                        ) {
                            AnimatedContent(
                                targetState = isBusy,
                                transitionSpec = {
                                    (fadeIn(tween(220)) + scaleIn(initialScale = 0.92f))
                                        .togetherWith(fadeOut(tween(180)) + scaleOut(targetScale = 0.92f))
                                },
                                label = "LoginButtonContent"
                            ) { busy ->
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center
                                ) {
                                    if (busy) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(20.dp),
                                            strokeWidth = 2.dp,
                                            color = MaterialTheme.colorScheme.onPrimary
                                        )
                                        Spacer(Modifier.width(10.dp))
                                        Text("جارٍ التحقق من السحابة…")
                                    } else {
                                        Icon(Icons.Filled.CloudQueue, contentDescription = null)
                                        Spacer(Modifier.width(8.dp))
                                        Text("تسجيل الدخول")
                                    }
                                }
                            }
                        }

                        OutlinedButton(
                            onClick = { showCloudSetup = true },
                            enabled = !isBusy && !isSavingCloudSettings,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                if (cloudSettingsConfigured) "إصلاح أو تغيير اتصال السحابة"
                                else "إعداد اتصال السحابة — أول تشغيل"
                            )
                        }
                    }
                }
            }
        }
    }

    if (showCloudSetup) {
        AlertDialog(
            onDismissRequest = { if (!isSavingCloudSettings) showCloudSetup = false },
            title = { Text(if (cloudSettingsConfigured) "إصلاح اتصال Cloudflare R2" else "إعداد اتصال Cloudflare R2") },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 480.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        "أدخل بيانات الحاوية ومفاتيح R2 من لوحة Cloudflare. تستخدم هذه الشاشة قبل تسجيل الدخول لإعداد الاتصال أو إصلاحه، وتُحفظ المفاتيح مشفّرة على هذا الجهاز فقط.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = cloudAccountId,
                        onValueChange = { cloudAccountId = it.trim() },
                        label = { Text("Cloudflare Account ID") },
                        singleLine = true,
                        enabled = !isSavingCloudSettings,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = cloudBucket,
                        onValueChange = { cloudBucket = it.trim() },
                        label = { Text("اسم الحاوية (Bucket)") },
                        singleLine = true,
                        enabled = !isSavingCloudSettings,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = cloudAccessKey,
                        onValueChange = { cloudAccessKey = it },
                        label = { Text("R2 Access Key ID") },
                        singleLine = true,
                        enabled = !isSavingCloudSettings,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = cloudSecretKey,
                        onValueChange = { cloudSecretKey = it },
                        label = { Text("R2 Secret Access Key") },
                        singleLine = true,
                        enabled = !isSavingCloudSettings,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (!initialError.isNullOrBlank()) {
                        Text(
                            text = initialError,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = { onSaveCloudCredentials(cloudAccountId, cloudBucket, cloudAccessKey, cloudSecretKey) },
                    enabled = !isSavingCloudSettings && cloudAccountId.isNotBlank() &&
                        cloudBucket.isNotBlank() &&
                        (cloudSettingsConfigured || (cloudAccessKey.isNotBlank() && cloudSecretKey.isNotBlank()))
                ) {
                    if (isSavingCloudSettings) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Text("حفظ الإعدادات")
                    }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showCloudSetup = false },
                    enabled = !isSavingCloudSettings
                ) { Text("إلغاء") }
            }
        )
    }
}

@Composable
private fun PendingAdminApprovalScreen(
    username: String,
    deviceLabel: String,
    message: String,
    statusNote: String?,
    errorMessage: String?,
    isBusy: Boolean,
    onCheckNow: () -> Unit,
    onSwitchAccount: () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "PendingApprovalPulse")
    val iconPulse by infiniteTransition.animateFloat(
        initialValue = 0.92f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "HourglassPulse"
    )

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentAlignment = Alignment.Center
        ) {
            ElevatedCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                shape = MaterialTheme.shapes.large,
                colors = CardDefaults.elevatedCardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Surface(
                        shape = MaterialTheme.shapes.extraLarge,
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                        modifier = Modifier
                            .size(68.dp)
                            .graphicsLayer {
                                scaleX = iconPulse
                                scaleY = iconPulse
                            }
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Filled.HourglassTop,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onTertiaryContainer,
                                modifier = Modifier.size(34.dp)
                            )
                        }
                    }

                    Text(
                        text = message,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        textAlign = TextAlign.Center
                    )

                    Text(
                        text = "الحساب «$username» مرتبط بجهاز آخر سابقاً. تم إرسال طلب اعتماد الجهاز الجديد («$deviceLabel») إلى المشرف في السحابة، وسيتم فتح التطبيق تلقائياً فور موافقته.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )

                    AnimatedVisibility(visible = !statusNote.isNullOrBlank()) {
                        Text(
                            text = statusNote.orEmpty(),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.tertiary,
                            textAlign = TextAlign.Center
                        )
                    }

                    AnimatedVisibility(visible = !errorMessage.isNullOrBlank()) {
                        Surface(
                            color = MaterialTheme.colorScheme.errorContainer,
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = errorMessage.orEmpty(),
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(12.dp),
                                textAlign = TextAlign.Center
                            )
                        }
                    }

                    Spacer(Modifier.height(4.dp))

                    FilledTonalButton(
                        onClick = onCheckNow,
                        enabled = !isBusy,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (isBusy) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("جارٍ التحقق من رد المشرف…")
                        } else {
                            Icon(Icons.Filled.Refresh, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("التحقق من رد المشرف الآن")
                        }
                    }

                    OutlinedButton(
                        onClick = onSwitchAccount,
                        enabled = !isBusy,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("العودة لتسجيل الدخول بحساب آخر")
                    }
                }
            }
        }
    }
}

@Composable
private fun AdminDeviceApprovalDialog(
    request: DeviceChangeRequest,
    isBusy: Boolean,
    onApprove: () -> Unit,
    onReject: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("طلب دخول من جهاز جديد") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "المستخدم «${request.username}» قام بتسجيل دخول على جهاز غير الذي سجل فيه سابقاً."
                )
                Text(
                    "الجهاز السابق: ${request.currentBoundDevice?.summaryLabel ?: "غير محدد"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "الجهاز الجديد: ${request.requestedDevice.summaryLabel}",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    "هل تسمح له بتسجيل الدخول من هذا الجهاز أو ترفض؟",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        },
        confirmButton = {
            Button(onClick = onApprove, enabled = !isBusy) {
                Text("سماح بالدخول")
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onReject, enabled = !isBusy) {
                    Text("رفض", color = MaterialTheme.colorScheme.error)
                }
                TextButton(onClick = onDismiss, enabled = !isBusy) {
                    Text("لاحقاً")
                }
            }
        }
    )
}
