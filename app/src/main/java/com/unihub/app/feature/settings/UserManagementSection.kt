package com.unihub.app.feature.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.AdminPanelSettings
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.UnfoldLess
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
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

    // حالة طي وتوسيع بطاقات المستخدمين وبحث المستخدمين (مطوية افتراضياً لجميع المستخدمين)
    var cardsState by remember { mutableStateOf(UserCardsExpansionState()) }
    val allUsers = authRegistry?.users.orEmpty()

    LaunchedEffect(currentUser.username) {
        viewModel.refreshAuthRegistry()
    }

    // مزامنة البطاقات المتوسعة مع السجل الكامل عند حذف/تحديث مستخدمين دون التأثر بالبحث (H3)
    LaunchedEffect(allUsers) {
        cardsState = SettingsCatalogRules.reconcileWithRegistry(cardsState, allUsers)
    }

    SectionHeader(title = "الحساب والجهاز المرتبط")
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(42.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = if (isAdmin) Icons.Outlined.AdminPanelSettings else Icons.Outlined.PersonOutline,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
                Spacer(Modifier.width(10.dp))
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
            shape = MaterialTheme.shapes.large
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
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = "إدارة الحسابات والصلاحيات في السحابة",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "انقر على أي مستخدم لتوسيع بطاقته وعرض خيارات التحكم الخاصة به",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
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

                AnimatedVisibility(
                    visible = pendingRequests.isNotEmpty(),
                    enter = expandVertically(tween(260)) + fadeIn(tween(260)),
                    exit = shrinkVertically(tween(200)) + fadeOut(tween(200))
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        HorizontalDivider()
                        Text(
                            text = "طلبات تسجيل دخول من أجهزة جديدة (${pendingRequests.size})",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.tertiary,
                            fontWeight = FontWeight.Bold
                        )
                        pendingRequests.forEach { req ->
                            key(req.requestId) {
                                PendingDeviceRequestCard(
                                    request = req,
                                    busy = authBusy,
                                    onApprove = { viewModel.resolveDeviceChangeRequest(req, approve = true) },
                                    onReject = { viewModel.resolveDeviceChangeRequest(req, approve = false) }
                                )
                            }
                        }
                    }
                }

                HorizontalDivider()

                val filteredUsers = remember(allUsers, cardsState.searchQuery) {
                    SettingsCatalogRules.filterAndSortUsers(allUsers, cardsState.searchQuery)
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "المستخدمون المسجلون (${allUsers.size})",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )

                    AnimatedVisibility(
                        visible = cardsState.expandedUsernames.isNotEmpty(),
                        enter = fadeIn(tween(200)),
                        exit = fadeOut(tween(160))
                    ) {
                        TextButton(
                            onClick = { cardsState = SettingsCatalogRules.collapseAll(cardsState) }
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.UnfoldLess,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text("طي الكل (${cardsState.expandedUsernames.size})")
                        }
                    }
                }

                // شريط بحث سريع لتسهيل الوصول لأي مستخدم عندما يكون هناك 50+ مستخدم
                if (allUsers.size > 3 || cardsState.searchQuery.isNotBlank()) {
                    OutlinedTextField(
                        value = cardsState.searchQuery,
                        onValueChange = { query ->
                            cardsState = SettingsCatalogRules.updateSearchQuery(cardsState, query)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("ابحث عن مستخدم بالاسم أو الجهاز…") },
                        leadingIcon = {
                            Icon(Icons.Outlined.Search, contentDescription = null)
                        },
                        trailingIcon = {
                            if (cardsState.searchQuery.isNotBlank()) {
                                IconButton(
                                    onClick = {
                                        cardsState = SettingsCatalogRules.updateSearchQuery(cardsState, "")
                                    }
                                ) {
                                    Icon(Icons.Outlined.Clear, contentDescription = "مسح البحث")
                                }
                            }
                        }
                    )
                }

                if (filteredUsers.isEmpty() && cardsState.searchQuery.isNotBlank()) {
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "لا يوجد مستخدم يطابق «${cardsState.searchQuery}»",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            TextButton(
                                onClick = {
                                    cardsState = SettingsCatalogRules.updateSearchQuery(cardsState, "")
                                }
                            ) {
                                Text("عرض جميع المستخدمين")
                            }
                        }
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    filteredUsers.forEach { account ->
                        key(account.username) {
                            val isExpanded = cardsState.isExpanded(account.username)
                            val isPasswordRevealed = cardsState.isPasswordRevealed(account.username)

                            AdminUserAccountCard(
                                account = account,
                                isExpanded = isExpanded,
                                isPasswordRevealed = isPasswordRevealed,
                                revealedPassword = viewModel.revealUserPassword(account),
                                busy = authBusy,
                                onToggleExpand = {
                                    cardsState = SettingsCatalogRules.toggleUserExpanded(
                                        cardsState,
                                        account.username
                                    )
                                },
                                onTogglePasswordReveal = {
                                    cardsState = SettingsCatalogRules.togglePasswordVisibility(
                                        cardsState,
                                        account.username
                                    )
                                },
                                onToggleActive = { active ->
                                    viewModel.setUserActive(account.username, active)
                                },
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

/**
 * بطاقة حساب المستخدم القابلة للطي والتوسيع بأنيميشن متكامل:
 * - في الوضع المطوي (الافتراضي): تعرض اسم المستخدم وشارة حالته وملخصاً مدمجاً وسهم توسيع متحرك.
 * - عند النقر على البطاقة: تتوسع بأنيميشن ناعم لتعرض جميع الخيارات (نشط/موقوف، الرمز وتغييره،
 *   الصلاحيات الثلاث، فك ربط الجهاز، وحذف المستخدم).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AdminUserAccountCard(
    account: CloudUserAccount,
    isExpanded: Boolean,
    isPasswordRevealed: Boolean,
    revealedPassword: String,
    busy: Boolean,
    onToggleExpand: () -> Unit,
    onTogglePasswordReveal: () -> Unit,
    onToggleActive: (Boolean) -> Unit,
    onUpdatePermissions: (UserPermissions) -> Unit,
    onChangePassword: () -> Unit,
    onResetDevice: () -> Unit,
    onDeleteUser: () -> Unit
) {
    val summary = remember(account) {
        SettingsCatalogRules.buildCollapsedUserSummary(account)
    }

    val arrowRotation by animateFloatAsState(
        targetValue = if (isExpanded) 180f else 0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "UserCardArrowRotation_${account.username}"
    )

    val containerColor by animateColorAsState(
        targetValue = when {
            isExpanded -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.28f)
            !summary.isStatusActive -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.22f)
            else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        },
        animationSpec = tween(260, easing = FastOutSlowInEasing),
        label = "UserCardBg_${account.username}"
    )

    val borderColor by animateColorAsState(
        targetValue = if (isExpanded) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.65f)
        } else {
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
        },
        animationSpec = tween(260),
        label = "UserCardBorder_${account.username}"
    )

    val tonalElevation by animateDpAsState(
        targetValue = if (isExpanded) 4.dp else 0.dp,
        animationSpec = tween(240),
        label = "UserCardElevation_${account.username}"
    )

    Surface(
        shape = MaterialTheme.shapes.medium,
        color = containerColor,
        tonalElevation = tonalElevation,
        border = BorderStroke(width = if (isExpanded) 1.5.dp else 1.dp, color = borderColor),
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMediumLow
                )
            )
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // رأس البطاقة القابل للنقر لتوسيع/طي نافذة المستخدم
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggleExpand)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = CircleShape,
                    color = when {
                        summary.isAdmin -> MaterialTheme.colorScheme.tertiaryContainer
                        summary.isStatusActive -> MaterialTheme.colorScheme.primaryContainer
                        else -> MaterialTheme.colorScheme.errorContainer
                    },
                    modifier = Modifier.size(40.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = if (summary.isAdmin) {
                                Icons.Outlined.AdminPanelSettings
                            } else {
                                Icons.Outlined.PersonOutline
                            },
                            contentDescription = null,
                            tint = when {
                                summary.isAdmin -> MaterialTheme.colorScheme.onTertiaryContainer
                                summary.isStatusActive -> MaterialTheme.colorScheme.onPrimaryContainer
                                else -> MaterialTheme.colorScheme.onErrorContainer
                            },
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }

                Spacer(Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = account.username,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.width(8.dp))
                        UserStatusBadge(
                            text = summary.statusBadgeText,
                            isAdmin = summary.isAdmin,
                            isActive = summary.isStatusActive
                        )
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "${summary.boundDeviceShortText} • ${summary.permissionsSummaryText}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }

                Spacer(Modifier.width(8.dp))

                Surface(
                    shape = CircleShape,
                    color = if (isExpanded) {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                    } else {
                        MaterialTheme.colorScheme.surface.copy(alpha = 0.6f)
                    },
                    modifier = Modifier.size(32.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Outlined.ExpandMore,
                            contentDescription = if (isExpanded) "طي نافذة المستخدم" else "توسيع نافذة المستخدم",
                            tint = if (isExpanded) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier
                                .size(20.dp)
                                .graphicsLayer {
                                    rotationZ = arrowRotation
                                }
                        )
                    }
                }
            }

            // القسم المتوسع الذي يظهر عند النقر على المستخدم ويعرض جميع خياراته بأنيميشن سلس
            AnimatedVisibility(
                visible = isExpanded,
                enter = expandVertically(
                    animationSpec = tween(260, easing = FastOutSlowInEasing)
                ) + fadeIn(animationSpec = tween(220)),
                exit = shrinkVertically(
                    animationSpec = tween(200, easing = FastOutSlowInEasing)
                ) + fadeOut(animationSpec = tween(160))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 14.dp, end = 14.dp, bottom = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    )

                    // تفاصيل الجهاز المرتبط الكاملة
                    Text(
                        text = "الجهاز المرتبط: ${account.boundDevice?.summaryLabel ?: "غير مرتبط بجهاز بعد"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    // خيار حالة الحساب (نشط أو موقوف) يظهر داخل النافذة المتوسعة لغير المشرف العام
                    if (SettingsCatalogRules.canShowDestructiveControlsForUser(account)) {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable(enabled = !busy) {
                                    onToggleActive(!account.isActive)
                                }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        text = if (account.isActive) "حالة الحساب: نشط" else "حالة الحساب: موقوف",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (account.isActive) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.error
                                        }
                                    )
                                    Text(
                                        text = if (account.isActive) {
                                            "يُسمح للمستخدم بتسجيل الدخول واستخدام السحابة"
                                        } else {
                                            "الحساب موقوف حالياً ولن يتمكن من الدخول"
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Switch(
                                    checked = account.isActive,
                                    onCheckedChange = onToggleActive,
                                    enabled = !busy
                                )
                            }
                        }
                    }

                    // عرض الرمز الحالي للمشرف مع إمكانية الإظهار/الإخفاء والتغيير
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Key,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.width(8.dp))
                            Crossfade(
                                targetState = isPasswordRevealed,
                                animationSpec = tween(200),
                                modifier = Modifier.weight(1f),
                                label = "PasswordTextCrossfade_${account.username}"
                            ) { revealed ->
                                Text(
                                    text = "الرمز الحالي: " + if (revealed) revealedPassword else "••••••",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                            IconButton(onClick = onTogglePasswordReveal) {
                                Icon(
                                    imageVector = if (isPasswordRevealed) {
                                        Icons.Outlined.VisibilityOff
                                    } else {
                                        Icons.Outlined.Visibility
                                    },
                                    contentDescription = if (isPasswordRevealed) "إخفاء الرمز" else "إظهار الرمز"
                                )
                            }
                            TextButton(onClick = onChangePassword, enabled = !busy) {
                                Text("تغيير الرمز")
                            }
                        }
                    }

                    // صلاحيات المستخدم في السحابة
                    if (SettingsCatalogRules.canShowDestructiveControlsForUser(account)) {
                        Text(
                            text = "صلاحيات المستخدم في السحابة:",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
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

                    // أزرار فك ربط الجهاز وحذف المستخدم
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (account.boundDevice != null) {
                            OutlinedButton(onClick = onResetDevice, enabled = !busy) {
                                Icon(
                                    Icons.Outlined.LinkOff,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text("فك ربط الجهاز")
                            }
                        }
                        if (SettingsCatalogRules.canShowDestructiveControlsForUser(account)) {
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
    }
}

@Composable
private fun UserStatusBadge(
    text: String,
    isAdmin: Boolean,
    isActive: Boolean
) {
    val bgColor: Color
    val contentColor: Color
    when {
        isAdmin -> {
            bgColor = MaterialTheme.colorScheme.tertiaryContainer
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer
        }
        isActive -> {
            bgColor = MaterialTheme.colorScheme.primaryContainer
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
        }
        else -> {
            bgColor = MaterialTheme.colorScheme.errorContainer
            contentColor = MaterialTheme.colorScheme.onErrorContainer
        }
    }

    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = contentColor,
        modifier = Modifier
            .background(bgColor, RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp)
    )
}

@Composable
private fun PermissionToggleRow(
    label: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
            .padding(vertical = 2.dp),
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
