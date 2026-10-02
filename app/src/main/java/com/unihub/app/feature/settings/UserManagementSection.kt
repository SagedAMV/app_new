package com.unihub.app.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.AdminPanelSettings
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.unihub.app.data.auth.AuthSessionState
import com.unihub.app.data.auth.CloudAuthRegistry
import com.unihub.app.data.auth.CloudUserAccount
import com.unihub.app.data.auth.DeviceApprovalStatus
import com.unihub.app.data.auth.DeviceChangeRequest
import com.unihub.app.data.auth.UserPermissions
import com.unihub.app.ui.components.ConfirmDialog
import com.unihub.app.ui.components.SectionHeader

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun UserManagementSection(
    authSession: AuthSessionState,
    authRegistry: CloudAuthRegistry?,
    authBusy: Boolean,
    viewModel: SettingsViewModel
) {
    val currentUser = (authSession as? AuthSessionState.Authenticated)?.user ?: return
    val isAdmin = currentUser.isAdmin

    var showChangeOwnPasswordDialog by remember { mutableStateOf(false) }
    var showAddUserDialog by remember { mutableStateOf(false) }
    var passwordTargetUser by remember { mutableStateOf<CloudUserAccount?>(null) }
    var deleteTargetUser by remember { mutableStateOf<CloudUserAccount?>(null) }

    LaunchedEffect(currentUser.username) {
        viewModel.refreshAuthRegistry()
    }

    SectionHeader(title = "الحساب والجهاز المرتبط")
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (isAdmin) Icons.Outlined.AdminPanelSettings else Icons.Outlined.PersonOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "المستخدم الحالي: ${currentUser.username}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = if (isAdmin) "الصلاحية: مشرف عام (تحكم كامل)" else "الصلاحية: مستخدم سحابي",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (authBusy) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                }
            }

            Text(
                text = "الجهاز الموثق: ${currentUser.boundDevice?.summaryLabel ?: "هذا الجهاز"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            val perms = currentUser.effectivePermissions
            val permSummary = buildList {
                add(if (perms.canDownload) "السحب: مفعّل ✓" else "السحب: موقوف ✗")
                add(if (perms.canUpload) "الرفع: مفعّل ✓" else "الرفع: موقوف ✗")
                add(if (perms.canModify) "التعديل/الحذف: مفعّل ✓" else "التعديل/الحذف: موقوف ✗")
            }.joinToString(" • ")

            Text(
                text = permSummary,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilledTonalButton(
                    onClick = { showChangeOwnPasswordDialog = true },
                    enabled = !authBusy
                ) {
                    Icon(Icons.Outlined.Key, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("تغيير الرمز الخاص بي")
                }

                OutlinedButton(
                    onClick = viewModel::logout,
                    enabled = !authBusy
                ) {
                    Icon(Icons.AutoMirrored.Outlined.Logout, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("تسجيل الخروج")
                }
            }
        }
    }

    if (isAdmin) {
        SectionHeader(title = "إعدادات المستخدمين وطلبات الأجهزة (للمشرف)")
        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "إدارة الحسابات والصلاحيات في السحابة",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    IconButton(
                        onClick = viewModel::refreshAuthRegistry,
                        enabled = !authBusy
                    ) {
                        Icon(Icons.Outlined.Refresh, contentDescription = "تحديث من السحابة")
                    }
                }

                Button(
                    onClick = { showAddUserDialog = true },
                    enabled = !authBusy,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Outlined.PersonAdd, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("إضافة مستخدم جديد")
                }

                val pendingRequests = authRegistry?.pendingDeviceRequests
                    ?.filter { it.status == DeviceApprovalStatus.PENDING }
                    .orEmpty()

                if (pendingRequests.isNotEmpty()) {
                    HorizontalDivider()
                    Text(
                        text = "طلبات تسجيل دخول من أجهزة جديدة (${pendingRequests.size})",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                        fontWeight = FontWeight.Bold
                    )
                    pendingRequests.forEach { req ->
                        PendingDeviceRequestCard(
                            request = req,
                            busy = authBusy,
                            onApprove = { viewModel.resolveDeviceChangeRequest(req, approve = true) },
                            onReject = { viewModel.resolveDeviceChangeRequest(req, approve = false) }
                        )
                    }
                }

                HorizontalDivider()
                val users = authRegistry?.users.orEmpty()
                Text(
                    text = "المستخدمون المسجلون (${users.size})",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )

                users.forEach { account ->
                    AdminUserAccountCard(
                        account = account,
                        revealedPassword = viewModel.revealUserPassword(account),
                        busy = authBusy,
                        onToggleActive = { active -> viewModel.setUserActive(account.username, active) },
                        onUpdatePermissions = { newPerms ->
                            viewModel.updateUserPermissions(account.username, newPerms)
                        },
                        onChangePassword = { passwordTargetUser = account },
                        onResetDevice = { viewModel.resetUserBoundDevice(account.username) },
                        onDeleteUser = { deleteTargetUser = account }
                    )
                }
            }
        }
    }

    if (showChangeOwnPasswordDialog) {
        UserChangeOwnPasswordDialog(
            username = currentUser.username,
            onConfirm = { currentPass, newPass ->
                viewModel.changeOwnPassword(currentPass, newPass)
                showChangeOwnPasswordDialog = false
            },
            onDismiss = { showChangeOwnPasswordDialog = false }
        )
    }

    passwordTargetUser?.let { target ->
        AdminChangePasswordDialog(
            title = "تغيير رمز المستخدم «${target.username}»",
            currentPasswordPlain = viewModel.revealUserPassword(target),
            onConfirm = { newPass ->
                viewModel.adminChangeUserPassword(target.username, newPass)
                passwordTargetUser = null
            },
            onDismiss = { passwordTargetUser = null }
        )
    }

    if (showAddUserDialog) {
        AddUserDialog(
            onConfirm = { username, password, perms ->
                viewModel.addUser(username, password, perms)
                showAddUserDialog = false
            },
            onDismiss = { showAddUserDialog = false }
        )
    }

    deleteTargetUser?.let { target ->
        ConfirmDialog(
            title = "حذف المستخدم «${target.username}»؟",
            message = "سيتم حذف حساب المستخدم «${target.username}» من السحابة وإلغاء جلسته فوراً.",
            confirmLabel = "حذف المستخدم",
            onConfirm = {
                viewModel.deleteUser(target.username)
                deleteTargetUser = null
            },
            onDismiss = { deleteTargetUser = null }
        )
    }
}

@Composable
private fun PendingDeviceRequestCard(
    request: DeviceChangeRequest,
    busy: Boolean,
    onApprove: () -> Unit,
    onReject: () -> Unit
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.55f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = "المستخدم «${request.username}» يطلب الدخول من جهاز جديد",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "الجهاز السابق: ${request.currentBoundDevice?.summaryLabel ?: "غير محدد"}",
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                text = "الجهاز الجديد: ${request.requestedDevice.summaryLabel}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 4.dp)
            ) {
                Button(onClick = onApprove, enabled = !busy) {
                    Icon(Icons.Outlined.CheckCircle, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("سماح بالدخول")
                }
                OutlinedButton(onClick = onReject, enabled = !busy) {
                    Text("رفض", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AdminUserAccountCard(
    account: CloudUserAccount,
    revealedPassword: String,
    busy: Boolean,
    onToggleActive: (Boolean) -> Unit,
    onUpdatePermissions: (UserPermissions) -> Unit,
    onChangePassword: () -> Unit,
    onResetDevice: () -> Unit,
    onDeleteUser: () -> Unit
) {
    var showPassword by remember { mutableStateOf(false) }

    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = account.username,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.width(8.dp))
                        AssistChip(
                            onClick = {},
                            label = {
                                Text(
                                    when {
                                        account.isAdmin -> "مشرف عام"
                                        account.isActive -> "نشط"
                                        else -> "موقوف"
                                    }
                                )
                            }
                        )
                    }
                    Text(
                        text = "الجهاز المرتبط: ${account.boundDevice?.summaryLabel ?: "غير مرتبط بجهاز بعد"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (!account.isAdmin) {
                    Switch(
                        checked = account.isActive,
                        onCheckedChange = onToggleActive,
                        enabled = !busy
                    )
                }
            }

            // عرض الرمز الحالي للمشرف (حتى لو غيّره المستخدم من جهازه)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Outlined.Key,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "الرمز الحالي: " + if (showPassword) revealedPassword else "••••••",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = { showPassword = !showPassword }) {
                    Icon(
                        imageVector = if (showPassword) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                        contentDescription = if (showPassword) "إخفاء الرمز" else "إظهار الرمز"
                    )
                }
                TextButton(onClick = onChangePassword, enabled = !busy) {
                    Text("تغيير الرمز")
                }
            }

            if (!account.isAdmin) {
                Text(
                    text = "صلاحيات المستخدم في السحابة:",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                val perms = account.permissions
                PermissionToggleRow(
                    label = "السماح بالسحب والتنزيل من السحابة",
                    checked = perms.canDownload,
                    enabled = !busy && account.isActive,
                    onCheckedChange = { checked ->
                        onUpdatePermissions(perms.copy(canDownload = checked))
                    }
                )
                PermissionToggleRow(
                    label = "السماح بالرفع إلى السحابة",
                    checked = perms.canUpload,
                    enabled = !busy && account.isActive,
                    onCheckedChange = { checked ->
                        onUpdatePermissions(perms.copy(canUpload = checked))
                    }
                )
                PermissionToggleRow(
                    label = "السماح بالتعديل أو الحذف في الحساب السحابي",
                    checked = perms.canModify,
                    enabled = !busy && account.isActive,
                    onCheckedChange = { checked ->
                        onUpdatePermissions(perms.copy(canModify = checked))
                    }
                )
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (account.boundDevice != null) {
                    OutlinedButton(onClick = onResetDevice, enabled = !busy) {
                        Icon(Icons.Outlined.LinkOff, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("فك ربط الجهاز")
                    }
                }
                if (!account.isAdmin) {
                    OutlinedButton(onClick = onDeleteUser, enabled = !busy) {
                        Icon(
                            Icons.Outlined.DeleteOutline,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("حذف المستخدم", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}

@Composable
private fun PermissionToggleRow(
    label: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun UserChangeOwnPasswordDialog(
    username: String,
    onConfirm: (String, String) -> Unit,
    onDismiss: () -> Unit
) {
    var currentPassword by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("تغيير الرمز الخاص بك ($username)") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = currentPassword,
                    onValueChange = { currentPassword = it },
                    label = { Text("الرمز الحالي") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = newPassword,
                    onValueChange = { newPassword = it },
                    label = { Text("الرمز الجديد") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(currentPassword, newPassword) },
                enabled = currentPassword.isNotBlank() && newPassword.trim().length >= 3
            ) {
                Text("حفظ الرمز")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("إلغاء")
            }
        }
    )
}

@Composable
private fun AdminChangePasswordDialog(
    title: String,
    currentPasswordPlain: String?,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var newPassword by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!currentPasswordPlain.isNullOrBlank()) {
                    Text(
                        text = "الرمز الحالي المسجل في السحابة: $currentPasswordPlain",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                OutlinedTextField(
                    value = newPassword,
                    onValueChange = { newPassword = it },
                    label = { Text("الرمز الجديد") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(newPassword) },
                enabled = newPassword.trim().length >= 3
            ) {
                Text("حفظ الرمز")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("إلغاء")
            }
        }
    )
}

@Composable
private fun AddUserDialog(
    onConfirm: (String, String, UserPermissions) -> Unit,
    onDismiss: () -> Unit
) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var canDownload by remember { mutableStateOf(true) }
    var canUpload by remember { mutableStateOf(true) }
    var canModify by remember { mutableStateOf(true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("إضافة مستخدم سحابي جديد") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("اسم المستخدم") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("رمز الدخول (كلمة المرور)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "صلاحيات المستخدم:",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold
                )
                PermissionToggleRow(
                    label = "السحب والتنزيل من السحابة",
                    checked = canDownload,
                    enabled = true,
                    onCheckedChange = { canDownload = it }
                )
                PermissionToggleRow(
                    label = "الرفع إلى السحابة",
                    checked = canUpload,
                    enabled = true,
                    onCheckedChange = { canUpload = it }
                )
                PermissionToggleRow(
                    label = "التعديل أو الحذف في الحساب السحابي",
                    checked = canModify,
                    enabled = true,
                    onCheckedChange = { canModify = it }
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onConfirm(
                        username,
                        password,
                        UserPermissions(
                            canDownload = canDownload,
                            canUpload = canUpload,
                            canModify = canModify
                        )
                    )
                },
                enabled = username.trim().length >= 2 && password.trim().length >= 3
            ) {
                Text("إضافة المستخدم")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("إلغاء")
            }
        }
    )
}
