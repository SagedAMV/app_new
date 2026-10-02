package com.unihub.app.feature.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PhoneAndroid
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import kotlinx.coroutines.delay

/**
 * البوابة الأمنية الرئيسية للتطبيق:
 * - عند أول تشغيل أو عدم وجود جلسة موثقة: تعرض شاشة تسجيل الدخول السحابي.
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
    val deviceLabel by viewModel.currentDeviceLabel.collectAsStateWithLifecycle()
    val statusMessage by viewModel.statusMessage.collectAsStateWithLifecycle()
    val errorMessage by viewModel.errorMessage.collectAsStateWithLifecycle()
    val dismissedIds by viewModel.dismissedRequestIds.collectAsStateWithLifecycle()

    when (val current = session) {
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
                            "جارٍ التحقق من الجلسة وبصمة الجهاز…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        is AuthSessionState.Unauthenticated -> {
            CloudLoginScreen(
                deviceLabel = deviceLabel,
                isBusy = isBusy,
                initialError = errorMessage ?: current.message,
                statusMessage = statusMessage,
                onLogin = viewModel::login
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

@Composable
private fun CloudLoginScreen(
    deviceLabel: String,
    isBusy: Boolean,
    initialError: String?,
    statusMessage: String?,
    onLogin: (String, String) -> Unit
) {
    var username by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var passwordVisible by rememberSaveable { mutableStateOf(false) }

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
                Surface(
                    shape = MaterialTheme.shapes.extraLarge,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(72.dp)
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

                Spacer(Modifier.height(16.dp))
                Text(
                    text = "تسجيل الدخول إلى UniHub",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "توثيق سحابي محمي ببصمة الجهاز — يتطلب الاتصال بالإنترنت عند أول تسجيل دخول",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )

                Spacer(Modifier.height(22.dp))

                ElevatedCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
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
                                    Icon(
                                        imageVector = if (passwordVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                        contentDescription = if (passwordVisible) "إخفاء كلمة المرور" else "إظهار كلمة المرور"
                                    )
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
                                    if (!isBusy && username.isNotBlank() && password.isNotBlank()) {
                                        onLogin(username, password)
                                    }
                                }
                            )
                        )

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(top = 2.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.PhoneAndroid,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = "الجهاز الحالي: $deviceLabel",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        if (!initialError.isNullOrBlank()) {
                            Surface(
                                color = MaterialTheme.colorScheme.errorContainer,
                                shape = MaterialTheme.shapes.small,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = initialError,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(12.dp)
                                )
                            }
                        }

                        if (!statusMessage.isNullOrBlank()) {
                            Surface(
                                color = MaterialTheme.colorScheme.secondaryContainer,
                                shape = MaterialTheme.shapes.small,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = statusMessage,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(12.dp)
                                )
                            }
                        }

                        Button(
                            onClick = { onLogin(username, password) },
                            enabled = !isBusy && username.isNotBlank() && password.isNotBlank(),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                        ) {
                            if (isBusy) {
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
            }
        }
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
                        modifier = Modifier.size(68.dp)
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

                    if (!statusNote.isNullOrBlank()) {
                        Text(
                            text = statusNote,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.tertiary,
                            textAlign = TextAlign.Center
                        )
                    }

                    if (!errorMessage.isNullOrBlank()) {
                        Surface(
                            color = MaterialTheme.colorScheme.errorContainer,
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = errorMessage,
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
