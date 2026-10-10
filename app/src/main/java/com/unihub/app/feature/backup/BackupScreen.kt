package com.unihub.app.feature.backup

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.unihub.app.ui.components.Field
import com.unihub.app.ui.components.FeatureHeroCard
import com.unihub.app.ui.components.SectionHeader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun BackupScreen(
    onBack: () -> Unit,
    viewModel: BackupViewModel = hiltViewModel()
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val status by viewModel.status.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val isOnline by viewModel.isOnline.collectAsStateWithLifecycle()
    val isSyncing by viewModel.isSyncing.collectAsStateWithLifecycle()
    val cloudSettings by viewModel.cloudSettings.collectAsStateWithLifecycle()
    val authSession by viewModel.authSession.collectAsStateWithLifecycle()
    val currentUser = (authSession as? com.unihub.app.data.auth.AuthSessionState.Authenticated)?.user
    val isAdmin = currentUser?.isAdmin == true
    val canPushMetadata = currentUser?.effectivePermissions?.let { it.canUpload && it.canModify } ?: false
    // جولة تعليمات.md: أزيلت نقاط فتح السحابة من هذه الشاشة (الزر العلوي والبانر
    // وزر الاختيار) — التصفح والتنزيل والتحكم محصورة في شاشة السحابة المستقلة
    // CloudFilesScreen، وتُفتح من زرّي الشريط العلوي في الرئيسية والملفات فقط.

    val isWorking = busy || isSyncing

    var showServerFields by remember { mutableStateOf(false) }
    var accountId by remember { mutableStateOf("") }
    var endpointUrl by remember { mutableStateOf("") }
    var bucketName by remember { mutableStateOf("") }
    var accessKeyId by remember { mutableStateOf("") }
    var secretAccessKey by remember { mutableStateOf("") }

    LaunchedEffect(cloudSettings?.credentials) {
        cloudSettings?.credentials?.let { creds ->
            accountId = creds.accountId
            endpointUrl = creds.endpointUrl
            bucketName = creds.bucketName
            // Never prefill long-lived credentials into editable UI fields.
            // Empty fields mean “keep the current encrypted value”.
            accessKeyId = ""
            secretAccessKey = ""
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri -> uri?.let(viewModel::exportTo) }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let(viewModel::importFrom) }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("النسخ الاحتياطي والمزامنة") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "رجوع")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp)
                .padding(bottom = 24.dp)
        ) {
            FeatureHeroCard(
                title = "حماية بياناتك",
                subtitle = "إدارة النسخ الاحتياطي المحلي ومزامنة السحابة من مكان واحد.",
                icon = Icons.Outlined.CloudSync,
                badge = if (isOnline) "متصل" else "دون اتصال",
                modifier = Modifier.padding(top = 8.dp)
            )
            Spacer(Modifier.height(8.dp))
            // ─── قسم خادم Cloudflare R2 (أونلاين / أوفلاين + سحب اختياري) ────
            SectionHeader(title = "خادم Cloudflare R2 (أونلاين / أوفلاين)")
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        text = if (isOnline) {
                            "الإنترنت متوفر — " + if (cloudSettings?.autoSyncEnabled == true) "الفحص التلقائي مفعّل" else "الفحص التلقائي متوقف"
                        } else {
                            "بدون إنترنت (أوفلاين) — يعمل بالتخزين المحلي"
                        },
                        style = MaterialTheme.typography.labelLarge,
                        color = if (isOnline) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.secondary
                        }
                    )

                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "يكتشف التطبيق تلقائياً أي ملفات جديدة على الخادم ويعرض لك إشعاراً باسم الملف وحجمه مع إضاءة زر السحابة لتختار بنفسك ما تريد سحبه دون ملء ذاكرة الهاتف.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    if (cloudSettings?.pendingUpload == true) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "• توجد تعديلات محلية محفوظة بانتظار الإرسال للخادم",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.tertiary
                        )
                    }

                    Spacer(Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("مستشعر المزامنة التلقائية", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "فحص الملفات الجديدة وإرسال التعديلات تلقائياً عند توفر الإنترنت",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = cloudSettings?.autoSyncEnabled ?: true,
                            onCheckedChange = viewModel::setAutoSyncEnabled
                        )
                    }

                    Spacer(Modifier.height(12.dp))
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilledTonalButton(
                            onClick = viewModel::pushToCloud,
                            enabled = !isWorking && canPushMetadata
                        ) {
                            Icon(Icons.Outlined.CloudUpload, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("مزامنة النصوص فقط")
                        }

                        OutlinedButton(
                            onClick = viewModel::syncWithCloud,
                            enabled = !isWorking
                        ) {
                            Icon(Icons.Outlined.CloudSync, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("فحص ومزامنة")
                        }
                    }

                    val lastSyncAt = cloudSettings?.lastSyncAt ?: 0L
                    if (lastSyncAt > 0L) {
                        val dateFormat = remember { SimpleDateFormat("yyyy/MM/dd h:mm a", Locale.getDefault()) }
                        Spacer(Modifier.height(10.dp))
                        Text(
                            text = "آخر فحص/مزامنة: ${dateFormat.format(Date(lastSyncAt))}" +
                                cloudSettings?.lastSyncMessage?.takeIf { it.isNotBlank() }?.let { " — $it" }.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (cloudSettings?.lastSyncSuccess == false) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }

                    if (isAdmin) {
                        Spacer(Modifier.height(8.dp))
                        TextButton(onClick = { showServerFields = !showServerFields }) {
                            Icon(Icons.Outlined.Settings, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text(if (showServerFields) "إخفاء إعدادات Cloudflare R2" else "إعدادات اتصال Cloudflare R2")
                        }

                        if (showServerFields) {
                            Spacer(Modifier.height(8.dp))
                            Field(
                                label = "Account ID (معرّف حساب Cloudflare)",
                                value = accountId,
                                onValueChange = { accountId = it }
                            )
                            Spacer(Modifier.height(8.dp))
                            Field(
                                label = "Endpoint URL",
                                value = endpointUrl,
                                onValueChange = { endpointUrl = it }
                            )
                            Spacer(Modifier.height(8.dp))
                            Field(
                                label = "Bucket Name (اسم الحاوية)",
                                value = bucketName,
                                onValueChange = { bucketName = it }
                            )
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("Access Key ID") },
                                placeholder = { Text("مفتاح محفوظ؛ اتركه فارغاً للإبقاء عليه") },
                                value = accessKeyId,
                                onValueChange = { accessKeyId = it },
                                visualTransformation = PasswordVisualTransformation(),
                                singleLine = true
                            )
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("Secret Access Key") },
                                placeholder = { Text("مفتاح محفوظ؛ اتركه فارغاً للإبقاء عليه") },
                                value = secretAccessKey,
                                onValueChange = { secretAccessKey = it },
                                visualTransformation = PasswordVisualTransformation(),
                                singleLine = true
                            )
                            Spacer(Modifier.height(10.dp))
                            Button(
                                onClick = {
                                    viewModel.saveCloudCredentials(
                                        accountId = accountId,
                                        endpointUrl = endpointUrl,
                                        bucketName = bucketName,
                                        accessKeyId = accessKeyId,
                                        secretAccessKey = secretAccessKey
                                    )
                                },
                                enabled = !isWorking
                            ) {
                                Text("حفظ إعدادات الخادم")
                            }
                        }
                    }
                }
            }

            // ─── قسم النسخ الاحتياطي المحلي (ملف ZIP على الجهاز) ─────────────
            SectionHeader(title = "تصدير نسخة محلية")
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        "احفظ نسخة كاملة من مجلداتك وملفاتك (بالمحتوى الفعلي نفسه) ومهامك وملاحظاتك " +
                            "وامتحاناتك وجدولك في أرشيف واحد على جهازك.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = { exportLauncher.launch("unihub_backup.zip") },
                        enabled = !isWorking
                    ) {
                        Icon(Icons.Outlined.FileDownload, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("تصدير النسخة")
                    }
                }
            }

            SectionHeader(title = "استعادة نسخة محلية")
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        "الاستيراد يستبدل كل البيانات الحالية بمحتوى النسخة (بما فيها الملفات نفسها) " +
                            "ويعيد جدولة التذكيرات تلقائياً. نُسخ JSON القديمة لا تزال مدعومة (بيانات وصفية فقط).",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = {
                            importLauncher.launch(arrayOf("application/zip", "application/json", "*/*"))
                        },
                        enabled = !isWorking
                    ) {
                        Icon(Icons.Outlined.FileUpload, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("اختيار ملف نسخة واستيرادها")
                    }
                }
            }

            if (isWorking) {
                Row(
                    modifier = Modifier.padding(top = 18.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("جارٍ التنفيذ…", style = MaterialTheme.typography.bodyMedium)
                }
            }

            status?.let { message ->
                Spacer(Modifier.height(18.dp))
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Spacer(Modifier.height(20.dp))
            Text(
                "ملاحظة: التطبيق يعمل بالكامل دون إنترنت (أوفلاين) بالتخزين المحلي، وعند توفر الإنترنت ينبهك بالملفات الجديدة وأحجامها لتسحب منها ما تشاء.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

    }
}
