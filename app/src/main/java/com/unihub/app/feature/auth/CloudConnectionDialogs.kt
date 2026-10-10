package com.unihub.app.feature.auth

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun CloudConnectionDialog(
    currentAccountId: String,
    currentBucketName: String,
    isSavingManual: Boolean,
    manualError: String?,
    manualSuccessVersion: Int,
    onSaveManual: (String, String, String, String) -> Unit,
    onCancelManual: () -> Unit,
    onDismiss: () -> Unit,
    viewModel: CloudConnectionViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val busy = state.busy || isSavingManual
    var code by remember { mutableStateOf("") }
    var showReceiveQr by remember { mutableStateOf(false) }
    var scanner by remember { mutableStateOf(false) }
    var recovery by remember { mutableStateOf(false) }
    var recoveryConfirmed by remember { mutableStateOf(false) }
    var account by remember { mutableStateOf(currentAccountId) }
    var bucket by remember { mutableStateOf(currentBucketName) }
    var access by remember { mutableStateOf("") }
    var secret by remember { mutableStateOf("") }
    val initialManualVersion = remember { manualSuccessVersion }
    val dismiss = { viewModel.close(); onCancelManual(); onDismiss() }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { it?.let(viewModel::receiveImage) }
    LaunchedEffect(Unit) { viewModel.prepare() }
    LaunchedEffect(state.successVersion, manualSuccessVersion) {
        if (state.successVersion > 0 || manualSuccessVersion != initialManualVersion) dismiss()
    }
    DisposableEffect(Unit) { onDispose { viewModel.close(); onCancelManual() } }
    AlertDialog(
        onDismissRequest = dismiss,
        properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
        title = { Text("تفعيل اتصال السحابة") },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 540.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("لا تحتاج كتابة مفاتيح Cloudflare. استخدم رمز المالك أو استلم حزمة مشفرة موجهة لهذا الجهاز.")
                if (viewModel.serviceAvailable) {
                    OutlinedTextField(value = code, onValueChange = { if (it.length <= 64) { code = it; viewModel.clearError() } },
                        label = { Text("رمز التفعيل من المالك") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(), enabled = !busy, modifier = Modifier.fillMaxWidth())
                    Button(onClick = { viewModel.receiveCode(code) }, enabled = !busy && code.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                        Text("استلام الاتصال من الخدمة")
                    }
                } else Text("الخدمة غير مفعلة في هذه النسخة؛ يمكنك استخدام الباركود المشفر الآن.", style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = { showReceiveQr = !showReceiveQr }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Text(if (showReceiveQr) "إخفاء طلب الجهاز" else "١ — عرض طلب الاستقبال للمالك")
                }
                if (showReceiveQr) {
                    state.receiveQr?.let { ConnectionQrCard(it, "طلب استقبال هذا الجهاز — بيانات عامة فقط") }
                    state.receiverFingerprint?.let { Text("بصمة الطلب: $it", style = MaterialTheme.typography.bodySmall) }
                    Text("أرسل هذا الطلب للمالك. سيرسل لك باركودًا مشفرًا خاصًا بجهازك. طلب الاستقبال صالح 15 دقيقة؛ مفاتيح R2 المستلمة ليست مؤقتة.")
                    TextButton(onClick = { viewModel.prepare(forceNew = true) }, enabled = !busy) { Text("إنشاء طلب جديد وإبطال السابق") }
                }
                Button(onClick = { scanner = true }, enabled = !busy && state.receiveQr != null, modifier = Modifier.fillMaxWidth()) {
                    Text("٢ — مسح حزمة المالك أو رمز التفعيل")
                }
                OutlinedButton(onClick = { imagePicker.launch("image/*") }, enabled = !busy && state.receiveQr != null, modifier = Modifier.fillMaxWidth()) {
                    Text("استلام باركود من صورة")
                }
                if (busy) CircularProgressIndicator()
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = { recovery = !recovery }, enabled = !busy) {
                    Text("تهيئة المالك أو استعادة الاتصال يدويًا")
                }
                if (recovery) {
                    ConfirmationCheckbox(recoveryConfirmed, { recoveryConfirmed = it }, "أنا المالك وأدخل بيانات الاتصال الخاصة بي للتهيئة أو الاستعادة")
                    OutlinedTextField(account, { if (it.length <= 32) account = it }, label = { Text("معرّف الحساب") },
                        modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !busy)
                    OutlinedTextField(bucket, { if (it.length <= 63) bucket = it }, label = { Text("اسم الحاوية") },
                        modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !busy)
                    OutlinedTextField(access, { if (it.length <= 256) access = it }, label = { Text("Access Key ID") },
                        modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !busy, visualTransformation = PasswordVisualTransformation())
                    OutlinedTextField(secret, { if (it.length <= 512) secret = it }, label = { Text("Secret Access Key") },
                        modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !busy, visualTransformation = PasswordVisualTransformation())
                    manualError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    Button(onClick = { onSaveManual(account, bucket, access, secret) },
                        enabled = recoveryConfirmed && !busy && account.isNotBlank() && bucket.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                        Text("اختبار الاتصال وحفظه مشفرًا")
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = dismiss) { Text(if (busy) "إلغاء العملية وإغلاق" else "إغلاق") } }
    )
    if (scanner) QrScannerDialog(onScanned = { scanner = false; viewModel.receiveQr(it) }, onDismiss = { scanner = false })
}

/** Call only from the authenticated owner section; the manager also rechecks ownership on export. */
@Composable
internal fun OwnerCloudDistributionSection(viewModel: CloudConnectionViewModel = hiltViewModel()) {
    var show by remember { mutableStateOf(false) }
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.secondaryContainer) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("توزيع اتصال السحابة المشفر", style = MaterialTheme.typography.titleMedium)
            Text("استقبل طلب جهاز المستخدم ثم أنشئ له باركودًا يحتوي مفاتيح R2 الأصلية مشفرة لجهازه فقط.")
            Button(onClick = { show = true }, modifier = Modifier.fillMaxWidth()) { Text("إرسال الاتصال بالباركود") }
        }
    }
    if (show) OwnerDistributionDialog(onDismiss = { viewModel.close(); show = false }, viewModel = viewModel)
}

@Composable
private fun OwnerDistributionDialog(onDismiss: () -> Unit, viewModel: CloudConnectionViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var acknowledged by remember { mutableStateOf(false) }
    var request by remember { mutableStateOf("") }
    var scanner by remember { mutableStateOf(false) }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) {
        it?.let { uri -> viewModel.exportImage(uri, acknowledged) }
    }
    DisposableEffect(Unit) { onDispose { viewModel.close() } }
    AlertDialog(onDismissRequest = onDismiss, properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
        title = { Text("إرسال مفاتيح الاتصال الأصلية") },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 540.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("تحذير: المستلم سيملك صلاحيات مفتاح R2 الدائم نفسه. صلاحيات التطبيق لا تمنع عميلًا معدّلًا من استخدام المفتاح مباشرة.", color = MaterialTheme.colorScheme.error)
                ConfirmationCheckbox(acknowledged, { acknowledged = it }, "أفهم المخاطر وأسمح بإرسال مفاتيح الاتصال لهذا الجهاز")
                Button(onClick = { scanner = true }, enabled = acknowledged && !state.busy, modifier = Modifier.fillMaxWidth()) { Text("مسح طلب استقبال جهاز المستخدم") }
                OutlinedButton(onClick = { imagePicker.launch("image/*") }, enabled = acknowledged && !state.busy, modifier = Modifier.fillMaxWidth()) { Text("قراءة طلب الجهاز من صورة") }
                OutlinedTextField(request, { if (it.length <= 4_096) request = it }, label = { Text("أو الصق طلب الاستقبال العام") },
                    modifier = Modifier.fillMaxWidth(), enabled = !state.busy, maxLines = 4)
                OutlinedButton(onClick = { viewModel.export(request, acknowledged) }, enabled = acknowledged && !state.busy && request.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("تشفير الحزمة للجهاز") }
                if (state.busy) CircularProgressIndicator()
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (state.exportQr != null) state.receiverFingerprint?.let {
                    Text("بصمة جهاز المستلم: $it", style = MaterialTheme.typography.bodySmall)
                    Text("قارن هذه البصمة بالبصمة المعروضة على جهاز المستلم قبل المشاركة. لا ترسل الحزمة إذا اختلفتا.", color = MaterialTheme.colorScheme.error)
                }
                state.exportQr?.let {
                    Text("أرسل هذه الصورة إلى الجهاز الذي أصدر الطلب. الحزمة لا تفتح على جهاز آخر، ومهلة الاستلام هي مهلة طلب الجهاز.")
                    ConnectionQrCard(it, "حزمة اتصال مشفرة لجهاز المستلم")
                }
            }
        }, confirmButton = { TextButton(onClick = onDismiss) { Text("إغلاق") } })
    if (scanner) QrScannerDialog(onScanned = { scanner = false; request = it; viewModel.export(it, acknowledged) }, onDismiss = { scanner = false })
}

@Composable
internal fun ConfirmationCheckbox(checked: Boolean, onChecked: (Boolean) -> Unit, label: String) {
    Row(Modifier.fillMaxWidth().toggleable(value = checked, role = Role.Checkbox, onValueChange = onChecked), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = null)
        Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun ConnectionQrCard(qr: String, label: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var error by remember(qr) { mutableStateOf<String?>(null) }
    var sharing by remember { mutableStateOf(false) }
    var bitmap by remember(qr) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(qr) {
        try { bitmap = ConnectionQrImages.render(qr) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { error = "تعذّر إنشاء صورة الباركود؛ حاول إنشاء طلب جديد" }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        bitmap?.let { Image(it.asImageBitmap(), contentDescription = label, contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxWidth().heightIn(min = 180.dp, max = 300.dp).background(Color.White)) }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        OutlinedButton(onClick = {
            if (!sharing) {
                sharing = true
                scope.launch {
                    try { ConnectionQrImages.share(context, qr) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { error = "تعذّرت مشاركة الصورة؛ يمكنك مسحها مباشرة بالكاميرا" }
                    finally { sharing = false }
                }
            }
        }, enabled = bitmap != null && !sharing, modifier = Modifier.fillMaxWidth()) { Text("مشاركة صورة الباركود") }
    }
}
