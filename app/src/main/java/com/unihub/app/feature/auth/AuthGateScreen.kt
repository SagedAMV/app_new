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
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.autofill.AutofillType
import androidx.compose.ui.platform.testTag
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
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.unihub.app.data.auth.AuthSessionState
import com.unihub.app.data.auth.DeviceApprovalStatus
import com.unihub.app.data.auth.DeviceChangeRequest
import com.unihub.app.data.cloud.CloudflareR2Config
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import com.unihub.app.data.auth.CloudAuthRules

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
    val setupSuccessVersion by viewModel.setupSuccessVersion.collectAsStateWithLifecycle()
    val pendingNote by viewModel.pendingNote.collectAsStateWithLifecycle()
    val sessionMessageDismissed by viewModel.sessionMessageDismissed.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current

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
                    initialError = errorMessage ?: cloudSettings?.credentialError ?: current.message.takeUnless { sessionMessageDismissed },
                    statusMessage = statusMessage,
                    cloudSettingsConfigured = cloudSettings?.isConfigured == true,
                    currentAccountId = cloudSettings?.credentials?.accountId ?: CloudflareR2Config.DEFAULT_ACCOUNT_ID,
                    currentBucketName = cloudSettings?.credentials?.bucketName ?: CloudflareR2Config.DEFAULT_BUCKET_NAME,
                    isSavingCloudSettings = isSavingCloudSettings,
                    serviceAvailable = viewModel.serviceAvailable,
                    setupSuccessVersion = setupSuccessVersion,
                    onLogin = { username, password, bootstrap, invitation -> viewModel.login(username, password, bootstrap, invitation) },
                    onInputsChanged = viewModel::clearMessages,
                    onRetry = viewModel::retryInitialization,
                    onCancelCloudOperation = viewModel::cancelConnectionOperation,
                    onSaveCloudCredentials = viewModel::saveInitialCloudCredentials
                )
            }

            is AuthSessionState.WaitingAdminApproval -> {
                LaunchedEffect(current.username, lifecycleOwner) {
                    lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                        val backoff = ApprovalPollingBackoff()
                        var wait = 15_000L
                        while (isActive) {
                            delay(wait)
                            wait = backoff.nextDelayMs(viewModel.awaitPendingCheck(silent = true))
                        }
                    }
                }
                PendingAdminApprovalScreen(
                    username = current.username,
                    deviceLabel = current.requestedDevice.summaryLabel,
                    message = statusMessage ?: current.message,
                    statusNote = pendingNote ?: current.statusNote,
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

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun CloudLoginScreen(
    isBusy: Boolean,
    initialError: String?,
    statusMessage: String?,
    cloudSettingsConfigured: Boolean,
    currentAccountId: String,
    currentBucketName: String,
    isSavingCloudSettings: Boolean,
    serviceAvailable: Boolean,
    setupSuccessVersion: Int,
    onLogin: (String, String, Boolean, String) -> Unit,
    onInputsChanged: () -> Unit,
    onRetry: () -> Unit,
    onCancelCloudOperation: () -> Unit,
    onSaveCloudCredentials: (String, String, String, String) -> Unit
) {
    var username by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordVisible by rememberSaveable { mutableStateOf(false) }
    var showCloudSetup by rememberSaveable { mutableStateOf(false) }
    var invitation by remember { mutableStateOf("") }
    var ownerBootstrap by remember { mutableStateOf(false) }
    var confirmBootstrap by remember { mutableStateOf(false) }
    ProtectAuthWindow()
    val submit: () -> Unit = {
        if (ownerBootstrap && CloudAuthRules.normalizeUsername(username) == CloudAuthRules.ADMIN_USERNAME) confirmBootstrap = true
        else onLogin(username, password, false, invitation)
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

    val canSubmit = !isBusy && !isSavingCloudSettings && AuthInteractionRules.inputError(username, password) == null &&
        (cloudSettingsConfigured || (serviceAvailable && invitation.trim().matches(Regex("[A-Za-z0-9_-]{43}"))))

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
                            onValueChange = { if (it.length <= CloudAuthRules.MAX_USERNAME_LENGTH) { username = it; onInputsChanged() } },
                            modifier = Modifier.fillMaxWidth().testTag("auth.username")
                                .authAutofill(AutofillType.Username) { if (it.length <= CloudAuthRules.MAX_USERNAME_LENGTH) { username = it; onInputsChanged() } },
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
                            onValueChange = { if (it.length <= CloudAuthRules.MAX_PASSWORD_LENGTH) { password = it; onInputsChanged() } },
                            modifier = Modifier.fillMaxWidth().testTag("auth.password")
                                .authAutofill(AutofillType.Password) { if (it.length <= CloudAuthRules.MAX_PASSWORD_LENGTH) { password = it; onInputsChanged() } },
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
                                        submit()
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
                            text = if (cloudSettingsConfigured) "الاتصال جاهز. لاستعادة كلمة المرور أو اعتماد جهاز جديد تواصل مع مالك التطبيق."
                                else "فعّل الاتصال برمز المالك أو باركود مشفر؛ لا تحتاج إعداد حساب Cloudflare.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        if (!cloudSettingsConfigured && serviceAvailable) {
                            OutlinedTextField(invitation, { if (it.length <= 64) { invitation = it; onInputsChanged() } },
                                label = { Text("رمز التفعيل من المالك") }, singleLine = true,
                                visualTransformation = PasswordVisualTransformation(), enabled = !isBusy,
                                modifier = Modifier.fillMaxWidth().testTag("auth.invitation"))
                        }
                        if (CloudAuthRules.normalizeUsername(username) == CloudAuthRules.ADMIN_USERNAME) {
                            ConfirmationCheckbox(ownerBootstrap, { ownerBootstrap = it },
                                "أنا المالك وأريد تهيئة حساب المالك إذا لم يوجد سجل حسابات")
                        }

                        Button(
                            onClick = { submit() },
                            enabled = canSubmit,
                            modifier = Modifier
                                .testTag("auth.login")
                                .fillMaxWidth()
                                .heightIn(min = 50.dp)
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
                            onClick = { onInputsChanged(); showCloudSetup = true },
                            enabled = !isBusy && !isSavingCloudSettings,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                if (cloudSettingsConfigured) "استعادة الاتصال أو استيراد باركود"
                                else "تفعيل الاتصال بالخدمة أو الباركود"
                            )
                        }
                        TextButton(onClick = onRetry, enabled = !isBusy, modifier = Modifier.fillMaxWidth()) {
                            Text("إعادة فحص الجلسة على هذا الجهاز")
                        }
                    }
                }
            }
        }
    }

    if (showCloudSetup) {
        CloudConnectionDialog(
            currentAccountId = currentAccountId,
            currentBucketName = currentBucketName,
            isSavingManual = isSavingCloudSettings,
            manualError = initialError,
            manualSuccessVersion = setupSuccessVersion,
            onSaveManual = onSaveCloudCredentials,
            onCancelManual = onCancelCloudOperation,
            onDismiss = { showCloudSetup = false }
        )
    }
    if (confirmBootstrap) {
        AlertDialog(onDismissRequest = { confirmBootstrap = false },
            title = { Text("تأكيد تهيئة حساب المالك") },
            text = { Text("هذه الخطوة للمالك فقط عندما لا يوجد سجل حسابات. لن تعيد ضبط سجل موجود. اختر كلمة مرور من 12 خانة على الأقل، ولا توزع الاتصال قبل إتمام التهيئة.") },
            confirmButton = { Button(onClick = { confirmBootstrap = false; onLogin(username, password, true, invitation) }, enabled = !isBusy) { Text("تأكيد تهيئة المالك") } },
            dismissButton = { TextButton(onClick = { confirmBootstrap = false }) { Text("إلغاء") } })
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
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(vertical = 16.dp),
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
                        enabled = true,
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
