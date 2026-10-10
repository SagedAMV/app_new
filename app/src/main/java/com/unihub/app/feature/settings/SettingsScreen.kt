package com.unihub.app.feature.settings

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.outlined.AdminPanelSettings
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.unihub.app.core.prefs.AutoBackupPreferences
import com.unihub.app.core.prefs.AutoBackupSettings
import com.unihub.app.core.prefs.ThemeMode
import com.unihub.app.data.auth.AuthSessionState
import com.unihub.app.data.auth.DeviceApprovalStatus
import com.unihub.app.ui.components.ChoiceChips
import com.unihub.app.ui.components.ConfirmDialog
import com.unihub.app.ui.components.Field
import com.unihub.app.ui.components.FeatureHeroCard
import com.unihub.app.ui.components.UiMessagesHost
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenBackup: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val snackbarHostState = remember { SnackbarHostState() }
    UiMessagesHost(viewModel.messenger, snackbarHostState)

    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val useDynamicColor by viewModel.useDynamicColor.collectAsStateWithLifecycle()
    val authSession by viewModel.authSession.collectAsStateWithLifecycle()
    val authRegistry by viewModel.authRegistry.collectAsStateWithLifecycle()
    val authBusy by viewModel.authBusy.collectAsStateWithLifecycle()
    val autoBackup by viewModel.autoBackupSettings.collectAsStateWithLifecycle()

    var showClearConfirm by remember { mutableStateOf(false) }

    // حفظ الصنف المفتوح عبر rememberSaveable حتى لا يضيع عند تدوير الشاشة أو فتح منتقي المجلدات (SAF)
    var selectedCategoryName by rememberSaveable { mutableStateOf<String?>(null) }
    val selectedCategory = SettingsCategory.fromSavedName(selectedCategoryName)

    val currentUser = (authSession as? AuthSessionState.Authenticated)?.user
    LaunchedEffect(currentUser?.username) {
        if (currentUser != null) {
            viewModel.refreshAuthRegistry()
        }
    }

    val handleBackNavigation: () -> Unit = {
        when (SettingsCatalogRules.handleBack(selectedCategory)) {
            SettingsBackOutcome.ReturnToCategoriesRoot -> {
                selectedCategoryName = null
            }
            SettingsBackOutcome.ExitSettingsScreen -> {
                onBack()
            }
        }
    }

    // اعتراض زر الرجوع في نظام أندرويد عندما يكون المستخدم داخل واجهة صنف فرعي
    BackHandler(enabled = selectedCategory != null) {
        handleBackNavigation()
    }

    val backupFolderLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri -> uri?.let(viewModel::onBackupFolderSelected) }

    val pendingRequestsCount = authRegistry?.pendingDeviceRequests
        ?.count { it.status == DeviceApprovalStatus.PENDING }
        ?: 0
    val totalUsersCount = authRegistry?.users?.size ?: 0

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    AnimatedContent(
                        targetState = selectedCategory?.title ?: "الإعدادات",
                        transitionSpec = {
                            (fadeIn(tween(220)) + slideInVertically(tween(220)) { it / 4 })
                                .togetherWith(fadeOut(tween(160)) + slideOutVertically(tween(160)) { -it / 5 })
                        },
                        label = "SettingsTopBarTitle"
                    ) { titleText ->
                        Text(
                            text = titleText,
                            maxLines = 1,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = handleBackNavigation) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "رجوع"
                        )
                    }
                }
            )
        }
    ) { padding ->
        AnimatedContent(
            targetState = selectedCategory,
            transitionSpec = {
                val enteringSubScreen = targetState != null
                if (enteringSubScreen) {
                    (fadeIn(tween(320, easing = FastOutSlowInEasing)) +
                        slideInVertically(
                            animationSpec = tween(320, easing = FastOutSlowInEasing),
                            initialOffsetY = { it / 10 }
                        ) +
                        scaleIn(
                            animationSpec = tween(320, easing = FastOutSlowInEasing),
                            initialScale = 0.96f
                        )).togetherWith(
                        fadeOut(tween(220)) +
                            scaleOut(tween(220), targetScale = 0.95f)
                    )
                } else {
                    (fadeIn(tween(300, easing = FastOutSlowInEasing)) +
                        scaleIn(
                            animationSpec = tween(300, easing = FastOutSlowInEasing),
                            initialScale = 0.96f
                        )).togetherWith(
                        fadeOut(tween(220)) +
                            slideOutVertically(
                                animationSpec = tween(220),
                                targetOffsetY = { it / 12 }
                            )
                    )
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            label = "SettingsScreenContentTransition"
        ) { category ->
            if (category == null) {
                SettingsCategoriesHome(
                    isAdmin = currentUser?.isAdmin == true,
                    currentUsername = currentUser?.username,
                    totalUsersCount = totalUsersCount,
                    pendingRequestsCount = pendingRequestsCount,
                    themeMode = themeMode,
                    useDynamicColor = useDynamicColor,
                    isBackupFolderConfigured = autoBackup?.isFolderConfigured == true,
                    backupIntervalDays = autoBackup?.intervalDays
                        ?: AutoBackupPreferences.DEFAULT_INTERVAL_DAYS,
                    onSelectCategory = { picked ->
                        selectedCategoryName = SettingsCatalogRules.openCategory(picked).name
                    }
                )
            } else {
                SettingsCategoryDetailScreen(
                    category = category,
                    authSession = authSession,
                    authRegistry = authRegistry,
                    authBusy = authBusy,
                    themeMode = themeMode,
                    useDynamicColor = useDynamicColor,
                    autoBackup = autoBackup,
                    viewModel = viewModel,
                    onPickBackupFolder = { backupFolderLauncher.launch(null) },
                    onOpenBackup = onOpenBackup,
                    onRequestClearAllData = { showClearConfirm = true }
                )
            }
        }

        if (showClearConfirm) {
            ConfirmDialog(
                title = "مسح جميع البيانات؟",
                message = "سيُحذف كل شيء نهائياً. لا يمكن التراجع.",
                confirmLabel = "مسح الكل",
                onConfirm = {
                    viewModel.clearAllData()
                    showClearConfirm = false
                },
                onDismiss = { showClearConfirm = false }
            )
        }
    }
}

/**
 * الواجهة الرئيسية للإعدادات: تعرض فقط الأصناف الخمسة المعتمدة بتصميم منظم وأنيميشن دخول متدرج.
 */
@Composable
private fun SettingsCategoriesHome(
    isAdmin: Boolean,
    currentUsername: String?,
    totalUsersCount: Int,
    pendingRequestsCount: Int,
    themeMode: ThemeMode,
    useDynamicColor: Boolean,
    isBackupFolderConfigured: Boolean,
    backupIntervalDays: Int,
    onSelectCategory: (SettingsCategory) -> Unit
) {
    val categories = remember { SettingsCatalogRules.orderedCategories() }
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        entered = true
    }

    val pulseTransition = rememberInfiniteTransition(label = "PendingBadgePulse")
    val badgePulseScale by pulseTransition.animateFloat(
        initialValue = 0.94f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "BadgePulseScale"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        FeatureHeroCard(
            title = "مركز إعدادات UniHub",
            subtitle = "خصّص المظهر، النسخ الاحتياطي، البيانات وإدارة الحساب.",
            icon = Icons.Outlined.Info,
            badge = "${categories.size} فئات"
        )
        Text(
            text = "اختر الصنف لعرض وضبط إعداداته الخاصة",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
        )

        categories.forEachIndexed { index, category ->
            val cardAnimProgress by animateFloatAsState(
                targetValue = if (entered) 1f else 0f,
                animationSpec = tween(
                    durationMillis = 420,
                    delayMillis = index * 60,
                    easing = FastOutSlowInEasing
                ),
                label = "CategoryCardEntrance_$index"
            )

            val subtitle = SettingsCatalogRules.buildCategoryLiveSubtitle(
                category = category,
                isAdmin = isAdmin,
                currentUsername = currentUsername,
                totalUsersCount = totalUsersCount,
                pendingDeviceRequestsCount = pendingRequestsCount,
                themeMode = themeMode,
                useDynamicColor = useDynamicColor,
                isBackupFolderConfigured = isBackupFolderConfigured,
                backupIntervalDays = backupIntervalDays
            )

            val showPendingBadge =
                category == SettingsCategory.USER_MANAGEMENT && isAdmin && pendingRequestsCount > 0

            SettingsCategoryCard(
                category = category,
                subtitle = subtitle,
                pendingBadgeCount = if (showPendingBadge) pendingRequestsCount else 0,
                badgeScale = badgePulseScale,
                onClick = { onSelectCategory(category) },
                modifier = Modifier.graphicsLayer {
                    alpha = cardAnimProgress
                    translationY = (1f - cardAnimProgress) * 32f
                    scaleX = 0.96f + (0.04f * cardAnimProgress)
                    scaleY = 0.96f + (0.04f * cardAnimProgress)
                }
            )
        }

        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun SettingsCategoryCard(
    category: SettingsCategory,
    subtitle: String,
    pendingBadgeCount: Int,
    badgeScale: Float,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val (icon, containerColor, contentColor) = categoryVisualStyle(category)

    ElevatedCard(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 3.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = containerColor,
                modifier = Modifier.size(52.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = contentColor,
                        modifier = Modifier.size(26.dp)
                    )
                }
            }

            Spacer(Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = category.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (pendingBadgeCount > 0) {
                        Spacer(Modifier.width(8.dp))
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.graphicsLayer {
                                scaleX = badgeScale
                                scaleY = badgeScale
                            }
                        ) {
                            Text(
                                text = pendingBadgeCount.toString(),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onError,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.width(8.dp))

            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
                modifier = Modifier.size(34.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowLeft,
                        contentDescription = "فتح الصنف",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun categoryVisualStyle(category: SettingsCategory): Triple<ImageVector, Color, Color> =
    when (category) {
        SettingsCategory.USER_MANAGEMENT -> Triple(
            Icons.Outlined.AdminPanelSettings,
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.onPrimaryContainer
        )
        SettingsCategory.APPEARANCE -> Triple(
            Icons.Outlined.Palette,
            MaterialTheme.colorScheme.secondaryContainer,
            MaterialTheme.colorScheme.onSecondaryContainer
        )
        SettingsCategory.AUTO_BACKUP -> Triple(
            Icons.Outlined.CloudSync,
            MaterialTheme.colorScheme.tertiaryContainer,
            MaterialTheme.colorScheme.onTertiaryContainer
        )
        SettingsCategory.DATA -> Triple(
            Icons.Outlined.Storage,
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.75f),
            MaterialTheme.colorScheme.onPrimaryContainer
        )
        SettingsCategory.ABOUT -> Triple(
            Icons.Outlined.Info,
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

/**
 * واجهة عرض الصنف المفتوح والإعدادات الخاصة به فقط.
 */
@Composable
private fun SettingsCategoryDetailScreen(
    category: SettingsCategory,
    authSession: AuthSessionState,
    authRegistry: com.unihub.app.data.auth.CloudAuthRegistry?,
    authBusy: Boolean,
    themeMode: ThemeMode,
    useDynamicColor: Boolean,
    autoBackup: AutoBackupSettings?,
    viewModel: SettingsViewModel,
    onPickBackupFolder: () -> Unit,
    onOpenBackup: () -> Unit,
    onRequestClearAllData: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 12.dp)
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        CategoryDetailBanner(category = category)

        when (category) {
            SettingsCategory.USER_MANAGEMENT -> {
                UserManagementSection(
                    authSession = authSession,
                    authRegistry = authRegistry,
                    authBusy = authBusy,
                    viewModel = viewModel
                )
                if ((authSession as? AuthSessionState.Authenticated)?.user?.isAdmin == true) {
                    com.unihub.app.feature.auth.OwnerCloudDistributionSection()
                }
            }

            SettingsCategory.APPEARANCE -> {
                AppearanceCategoryContent(
                    themeMode = themeMode,
                    useDynamicColor = useDynamicColor,
                    onSelectThemeMode = viewModel::setThemeMode,
                    onToggleDynamicColor = viewModel::setDynamicColor
                )
            }

            SettingsCategory.AUTO_BACKUP -> {
                AutoBackupCategoryContent(
                    autoBackup = autoBackup,
                    viewModel = viewModel,
                    onPickBackupFolder = onPickBackupFolder
                )
            }

            SettingsCategory.DATA -> {
                DataCategoryContent(
                    onOpenBackup = onOpenBackup,
                    onRequestClearAllData = onRequestClearAllData
                )
            }

            SettingsCategory.ABOUT -> {
                AboutCategoryContent()
            }
        }
    }
}

@Composable
private fun CategoryDetailBanner(category: SettingsCategory) {
    val (icon, containerColor, contentColor) = categoryVisualStyle(category)
    Surface(
        shape = MaterialTheme.shapes.large,
        color = containerColor.copy(alpha = 0.55f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(containerColor),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = contentColor,
                    modifier = Modifier.size(24.dp)
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = category.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = category.defaultSubtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun AppearanceCategoryContent(
    themeMode: ThemeMode,
    useDynamicColor: Boolean,
    onSelectThemeMode: (ThemeMode) -> Unit,
    onToggleDynamicColor: (Boolean) -> Unit
) {
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
            Text(
                text = "نمط الإضاءة والألوان",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("معاينة لوحة UniHub", style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(
                            MaterialTheme.colorScheme.primary,
                            MaterialTheme.colorScheme.secondary,
                            MaterialTheme.colorScheme.tertiary,
                            MaterialTheme.colorScheme.surfaceVariant
                        ).forEach { color ->
                            Surface(
                                modifier = Modifier.weight(1f).height(24.dp),
                                shape = MaterialTheme.shapes.small,
                                color = color
                            ) { }
                        }
                    }
                    Text(
                        "ألوان هادئة وتباين واضح للقراءة الطويلة.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            ThemeMode.entries.forEach { mode ->
                val isSelected = themeMode == mode
                val rowBg by animateColorAsState(
                    targetValue = if (isSelected) {
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                    } else {
                        Color.Transparent
                    },
                    animationSpec = tween(220),
                    label = "ThemeRowBg_${mode.name}"
                )

                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = rowBg,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.medium)
                        .selectable(
                            selected = isSelected,
                            onClick = { onSelectThemeMode(mode) },
                            role = Role.RadioButton
                        )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = isSelected, onClick = null)
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = mode.label(),
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                        )
                    }
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.medium)
                    .clickable(enabled = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        onToggleDynamicColor(!useDynamicColor)
                    }
                    .padding(vertical = 8.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Outlined.Palette,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = "ألوان Material You الديناميكية",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium
                        )
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            "استخدم ألواناً مشتقة من خلفية جهازك (أندرويد 12+)"
                        } else {
                            "متوفرة على أندرويد 12 فما فوق فقط"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = useDynamicColor,
                    onCheckedChange = onToggleDynamicColor,
                    enabled = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                )
            }
        }
    }
}

@Composable
private fun AutoBackupCategoryContent(
    autoBackup: AutoBackupSettings?,
    viewModel: SettingsViewModel,
    onPickBackupFolder: () -> Unit
) {
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
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "مجلد النسخ التلقائي",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = if (autoBackup?.isFolderConfigured == true) {
                            "تم تحديد مجلد نسخ الاحتياطية ✓"
                        } else {
                            "لم يتم تحديد مجلد نسخ الاحتياطية"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (autoBackup?.isFolderConfigured == true) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
                if (autoBackup?.isFolderConfigured == true) {
                    TextButton(onClick = viewModel::clearBackupFolder) { Text("إلغاء") }
                }
                FilledTonalButton(onClick = onPickBackupFolder) {
                    Text(if (autoBackup?.isFolderConfigured == true) "تغيير" else "اختيار مجلد")
                }
            }

            HorizontalDivider()

            val intervalDays = autoBackup?.intervalDays
                ?: AutoBackupPreferences.DEFAULT_INTERVAL_DAYS
            val chipIndex = SettingsCatalogRules.computeBackupChipIndex(intervalDays)

            Text(
                text = "كل كم يُعمل النسخ التلقائي؟",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium
            )
            ChoiceChips(
                labels = listOf("يومي", "كل 3 أيام", "أسبوعي", "شهري", "مخصص"),
                selectedIndex = chipIndex,
                onSelect = { index ->
                    when (index) {
                        0 -> viewModel.setIntervalDays(1)
                        1 -> viewModel.setIntervalDays(3)
                        2 -> viewModel.setIntervalDays(7)
                        3 -> viewModel.setIntervalDays(30)
                        else -> {
                            // تحليل آمن بلا force-unwrap (بوابة «صفر !!» في تعليمات.md):
                            // دالة التحليل تُعيد القيمة الصالحة أو null، فلا افتراض عدم-صفريّة
                            // يظل معلقاً على سلوك دالة تحقق منفصلة.
                            val customDays = SettingsCatalogRules.parseCustomBackupDays(autoBackup?.customDaysInput)
                            if (customDays != null) {
                                viewModel.setIntervalDays(customDays)
                            } else {
                                viewModel.messenger.notifyError("اكتب عدد الأيام في الخانة أولاً (1-365)")
                            }
                        }
                    }
                }
            )

            AnimatedVisibility(
                visible = chipIndex == 4,
                enter = expandVertically(tween(240)) + fadeIn(tween(240)),
                exit = shrinkVertically(tween(200)) + fadeOut(tween(200))
            ) {
                Field(
                    label = "عدد الأيام المخصص (1-365)",
                    value = autoBackup?.customDaysInput.orEmpty(),
                    onValueChange = viewModel::setCustomDaysInput,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
            }

            HorizontalDivider()

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "نسخة بعد كل عملية تعديل",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "للتجربة: تُحدَّث نسخة «آخر حفظ» بعد أي تعديل على بياناتك",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = autoBackup?.backupOnChange ?: false,
                    onCheckedChange = viewModel::setBackupOnChange
                )
            }

            OutlinedButton(
                onClick = viewModel::backupNow,
                enabled = autoBackup?.isFolderConfigured == true,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("نسخ احتياطي الآن")
            }

            val lastAt = autoBackup?.lastBackupAt ?: 0L
            AnimatedVisibility(visible = lastAt > 0L) {
                val dateFormat = remember { SimpleDateFormat("yyyy/MM/dd h:mm a", Locale.getDefault()) }
                Text(
                    text = "آخر محاولة: ${dateFormat.format(Date(lastAt))}" +
                        autoBackup?.lastBackupMessage?.takeIf { it.isNotBlank() }?.let { " — $it" }.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun DataCategoryContent(
    onOpenBackup: () -> Unit,
    onRequestClearAllData: () -> Unit
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.medium)
                    .clickable(onClick = onOpenBackup)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Outlined.Backup,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(26.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = "النسخ الاحتياطي والاستعادة",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "تصدير بياناتك إلى أرشيف نسخة احتياطية أو استعادتها",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowLeft,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.medium)
                    .clickable(onClick = onRequestClearAllData)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Outlined.DeleteForever,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(26.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = "مسح جميع البيانات",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.error
                        )
                        Text(
                            text = "يحذف كل المجلدات والملفات والمهام والملاحظات نهائياً",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AboutCategoryContent() {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "حول تطبيق UniHub",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = "UniHub — رفيقك الجامعي لإدارة الملفات والمحاضرات والمهام والملاحظات والامتحانات. " +
                    "يعمل بالكامل دون إنترنت وتبقى بياناتك على جهازك.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            Text(
                text = "الإصدار 1.3.1",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

private fun ThemeMode.label(): String = when (this) {
    ThemeMode.SYSTEM -> "تلقائي (حسب النظام)"
    ThemeMode.LIGHT -> "فاتح"
    ThemeMode.DARK -> "داكن"
}
