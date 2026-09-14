package com.unihub.app.feature.files.capture

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material.icons.filled.FlipCameraAndroid
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.unihub.app.feature.files.FilesViewModel
import com.unihub.app.ui.components.UiMessagesHost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlin.math.roundToInt

/** مدخل حفظ صورة واحدة: الملف المؤقت في الكاش + الاسم الاختياري الذي أدخله المستخدم */
data class CapturedImageInput(val file: File, val name: String?)

/** صورة ملتقطة في الجلسة الحالية — ملف مؤقت في الكاش وصورة مصغّرة للعرض فقط */
data class CapturedShot(
    val id: Long,
    val file: File,
    val thumbnail: Bitmap
)

/** مرحلتا شاشة الكاميرا: الالتقاط المباشر ثم مراجعة المرفقات */
private enum class CapturePhase { CAPTURE, REVIEW }

/** ارتفاع صف المراجعة ثابت حتى تُحسب إزاحة السحب بدقة */
private val REVIEW_ROW_HEIGHT = 96.dp

/**
 * شاشة التقاط الصور — تُفتح كوجهة مستقلة فوق شاشة الملفات (كاميرا بملء الشاشة).
 *
 * الجلسة تمر بمرحلتين:
 *  1) [CapturePhase.CAPTURE]: معاينة حية + زر التقاط + تبديل الكاميرا + شريط مصغّرات.
 *     الكاميرا تبقى مفتوحة بعد كل لقطة لالتقاط عدة صور في الجلسة نفسها.
 *  2) [CapturePhase.REVIEW]: كل صورة لها اسم اختياري وزر حذف، وإعادة الترتيب بسحبة
 *     مطولة، ثم "إضافة المرفقات" تحفظ الكل دفعة واحدة في المجلد المفتوح.
 *
 * الصور تُحفظ مؤقتاً في كاش التطبيق ولا تُنقل لمكتبة الملفات إلا عند الحفظ الفعلي —
 * الإلغاء لا يترك أي أثر على القرص.
 */
@Composable
fun CameraCaptureScreen(
    folderId: Long?,
    onBack: () -> Unit,
    viewModel: FilesViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    UiMessagesHost(viewModel.messenger, snackbarHostState)

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasPermission = granted }
    LaunchedEffect(Unit) {
        if (!hasPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    var phase by remember { mutableStateOf(CapturePhase.CAPTURE) }
    val shots = remember { mutableStateListOf<CapturedShot>() }
    val names = remember { mutableStateMapOf<Long, String>() }
    var confirmCancelAll by remember { mutableStateOf(false) }

    // تنظيف مضمون عند مغادرة الشاشة بأي طريق: ملفات مؤقتة وصور مصغّرة
    DisposableEffect(Unit) {
        onDispose {
            shots.forEach { shot ->
                runCatching { shot.file.delete() }
                runCatching { shot.thumbnail.recycle() }
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = Color.Black
    ) { padding ->
        when {
            !hasPermission -> CameraPermissionContent(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize(),
                onRequestAgain = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                onClose = onBack
            )

            phase == CapturePhase.REVIEW -> ReviewCapturesContent(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize(),
                shots = shots,
                names = names,
                onBackToCamera = { phase = CapturePhase.CAPTURE },
                onRequestCancelAll = { confirmCancelAll = true },
                onSaveAll = {
                    viewModel.saveCapturedImages(
                        shots.map { shot ->
                            CapturedImageInput(
                                file = shot.file,
                                name = names[shot.id]?.trim()?.takeIf { it.isNotBlank() }
                            )
                        }
                    )
                    shots.clear()
                    onBack()
                }
            )

            else -> CaptureContent(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize(),
                shots = shots,
                onError = { viewModel.messenger.notifyError(it) },
                onClose = {
                    // إغلاق الكاميرا واللقطات موجودة = انتقال للمراجعة بدل ضياع الصور
                    if (shots.isEmpty()) onBack() else phase = CapturePhase.REVIEW
                },
                onProceedToReview = { phase = CapturePhase.REVIEW }
            )
        }

        if (confirmCancelAll) {
            AlertDialog(
                onDismissRequest = { confirmCancelAll = false },
                title = { Text("إلغاء الجلسة؟") },
                text = { Text("ستُحذف كل الصور الملتقطة في هذه الجلسة (${shots.size}) ولا يمكن التراجع.") },
                confirmButton = {
                    TextButton(onClick = {
                        shots.forEach { runCatching { it.file.delete() } }
                        shots.clear()
                        names.clear()
                        confirmCancelAll = false
                        onBack()
                    }) { Text("حذف الكل", color = MaterialTheme.colorScheme.error) }
                },
                dismissButton = {
                    TextButton(onClick = { confirmCancelAll = false }) { Text("متابعة المراجعة") }
                }
            )
        }
    }
}

// ─────────────────────────────── مرحلة الالتقاط ───────────────────────────────

/**
 * واجهة الكاميرا الحية: معاينة CameraX، زر التقاط (شاتر) بوميض، تبديل أمامية/خلفية،
 * زر إغلاق، وشريط مصغّرات يتجمع أسفل الشاشة مع استمرار الجلسة.
 */
@Composable
private fun CaptureContent(
    modifier: Modifier,
    shots: SnapshotStateList<CapturedShot>,
    onError: (String) -> Unit,
    onClose: () -> Unit,
    onProceedToReview: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val providerFuture = remember { ProcessCameraProvider.getInstance(context) }

    var lensFacing by remember { mutableStateOf(CameraSelector.LENS_FACING_BACK) }
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var cameraFailed by remember { mutableStateOf(false) }
    var capturing by remember { mutableStateOf(false) }
    val flashAlpha = remember { Animatable(0f) }

    Box(modifier.background(Color.Black)) {
        if (cameraFailed) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.CameraAlt,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.7f),
                    modifier = Modifier.size(48.dp)
                )
                Spacer(Modifier.height(14.dp))
                Text(
                    text = "تعذّر تشغيل الكاميرا",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "ربما يستخدمها تطبيق آخر الآن — أغلقه وحاول مجدداً",
                    color = Color.White.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center
                )
            }
        } else {
            AndroidView(
                factory = { ctx ->
                    PreviewView(ctx).apply {
                        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                        scaleType = PreviewView.ScaleType.FILL_CENTER
                    }
                },
                modifier = Modifier.fillMaxSize(),
                update = { previewView ->
                    // يُعاد الربط عند تغيّر الكاميرا المختارة (lensFacing مقروءة هنا)
                    providerFuture.addListener({
                        runCatching {
                            val provider = providerFuture.get()
                            val preview = Preview.Builder().build().also { p ->
                                p.setSurfaceProvider(previewView.surfaceProvider)
                            }
                            val capture = ImageCapture.Builder()
                                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                                .build()
                            val selector = CameraSelector.Builder()
                                .requireLensFacing(lensFacing)
                                .build()
                            provider.unbindAll()
                            provider.bindToLifecycle(lifecycleOwner, selector, preview, capture)
                            imageCapture = capture
                            cameraFailed = false
                        }.onFailure { cameraFailed = true }
                    }, ContextCompat.getMainExecutor(context))
                }
            )
        }

        // وميض الالتقاط فوق المعاينة
        if (flashAlpha.value > 0f) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.White.copy(alpha = flashAlpha.value))
            )
        }

        // الشريط العلوي: إغلاق + العنوان + تبديل الكاميرا
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.35f))
                .padding(horizontal = 6.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.Close, contentDescription = "إغلاق الكاميرا", tint = Color.White)
            }
            Text(
                text = "التقاط صور",
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center
            )
            IconButton(onClick = {
                runCatching {
                    val provider = providerFuture.get()
                    val other = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
                        CameraSelector.LENS_FACING_FRONT
                    } else {
                        CameraSelector.LENS_FACING_BACK
                    }
                    val otherSelector = CameraSelector.Builder().requireLensFacing(other).build()
                    if (provider.hasCamera(otherSelector)) {
                        lensFacing = other
                    } else {
                        onError(
                            if (other == CameraSelector.LENS_FACING_FRONT)
                                "هذا الجهاز لا يملك كاميرا أمامية"
                            else "هذا الجهاز لا يملك كاميرا خلفية"
                        )
                    }
                }.onFailure { onError("تعذّر تبديل الكاميرا") }
            }) {
                Icon(
                    Icons.Filled.FlipCameraAndroid,
                    contentDescription = "تبديل الكاميرا الأمامية/الخلفية",
                    tint = Color.White
                )
            }
        }

        // الشريط السفلي: مصغّرات الجلسة + الشاتر + زر المراجعة
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.45f))
        ) {
            if (shots.isNotEmpty()) {
                LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(62.dp)
                        .padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(shots, key = { it.id }) { shot ->
                        Image(
                            bitmap = shot.thumbnail.asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier
                                .size(56.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .border(1.dp, Color.White.copy(alpha = 0.5f), RoundedCornerShape(10.dp)),
                            contentScale = ContentScale.Crop
                        )
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // عدد اللقطات في بدء الجلسة
                Text(
                    text = if (shots.isEmpty()) "الجلسة فارغة"
                    else "${shots.size} ${if (shots.size == 1) "صورة" else "صور"}",
                    color = Color.White.copy(alpha = 0.85f),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.width(84.dp)
                )

                // الشاتر: حلقة بيضاء حول قرص داخلي
                Box(contentAlignment = Alignment.Center) {
                    Box(
                        Modifier
                            .size(76.dp)
                            .border(4.dp, Color.White, CircleShape)
                    )
                    Box(
                        Modifier
                            .size(60.dp)
                            .background(if (capturing) Color.White.copy(alpha = 0.5f) else Color.White, CircleShape)
                            .clickable(enabled = !capturing && !cameraFailed) {
                                val capture = imageCapture
                                if (capture == null) {
                                    onError("الكاميرا لم تجهز بعد — لحظة واحدة")
                                    return@clickable
                                }
                                capturing = true
                                scope.launch {
                                    flashAlpha.snapTo(0.7f)
                                    flashAlpha.animateTo(0f, tween(280))
                                }
                                takeShot(
                                    capture = capture,
                                    context = context,
                                    scope = scope,
                                    onShot = { shot ->
                                        capturing = false
                                        shots.add(shot)
                                    },
                                    onError = {
                                        capturing = false
                                        onError("تعذّر حفظ اللقطة — حاول مجدداً")
                                    }
                                )
                            }
                    )
                }

                // الانتقال لمراجعة المرفقات (يظهر فعلياً عند وجود لقطات)
                FilledTonalButton(
                    onClick = onProceedToReview,
                    enabled = shots.isNotEmpty(),
                    modifier = Modifier.width(84.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = 8.dp,
                        vertical = 10.dp
                    )
                ) {
                    Text("مراجعة", style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

/** التقاط لقطة واحدة: فكّ ترميز JPEG من ImageProxy، تدوير حسب اتجاه المستشعر، حفظ في الكاش */
private fun takeShot(
    capture: ImageCapture,
    context: Context,
    scope: CoroutineScope,
    onShot: (CapturedShot) -> Unit,
    onError: () -> Unit
) {
    capture.takePicture(ContextCompat.getMainExecutor(context), object : ImageCapture.OnImageCapturedCallback() {
        override fun onCaptureSuccess(image: ImageProxy) {
            val rotation = image.imageInfo.rotationDegrees
            val buffer = image.planes[0].buffer
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            image.close()
            scope.launch(Dispatchers.IO) {
                runCatching {
                    val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        ?: throw IOException("تعذّر فك ترميز الصورة")
                    val bitmap = if (rotation != 0) {
                        val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
                        Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
                    } else {
                        decoded
                    }
                    val file = File.createTempFile("shot_", ".jpg", context.cacheDir)
                    FileOutputStream(file).use { out ->
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
                    }
                    if (bitmap !== decoded) decoded.recycle()
                    val thumbnail = decodeThumbnail(file)
                    CapturedShot(id = System.nanoTime(), file = file, thumbnail = thumbnail)
                }.onSuccess(onShot)
                    .onFailure { onError() }
            }
        }

        override fun onError(exception: ImageCaptureException) {
            onError()
        }
    })
}

/** صورة مصغّرة بحجم مناسب للعرض فقط — لا نحمّل الصورة الكاملة في الذاكرة */
private fun decodeThumbnail(file: File): Bitmap {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) > 240) sample *= 2
    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    return BitmapFactory.decodeFile(file.absolutePath, options)
}

// ─────────────────────────────── مرحلة المراجعة ───────────────────────────────

/**
 * مراجعة اللقطات قبل الحفظ: اسم اختياري لكل صورة، حذف فردي، وإعادة ترتيب
 * بسحبة مطولة (الصفوف بارتفاع ثابت فتحسب الإزاحة بدقة)، ثم حفظ جماعي أو إلغاء.
 */
@Composable
private fun ReviewCapturesContent(
    modifier: Modifier,
    shots: SnapshotStateList<CapturedShot>,
    names: androidx.compose.runtime.snapshots.SnapshotStateMap<Long, String>,
    onBackToCamera: () -> Unit,
    onRequestCancelAll: () -> Unit,
    onSaveAll: () -> Unit
) {
    val density = LocalDensity.current.density
    val rowHeightPx = REVIEW_ROW_HEIGHT.value * density
    var draggedId by remember { mutableStateOf<Long?>(null) }
    var dragOffsetPx by remember { mutableStateOf(0f) }

    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.background
    ) {
        Column(Modifier.fillMaxSize()) {
            // ترويسة: رجوع للكاميرا + العنوان
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBackToCamera) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "عودة إلى الكاميرا"
                    )
                }
                Text(
                    text = "مراجعة الصور (${shots.size})",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.width(48.dp)) // توازن بصري مع زر الرجوع
            }

            Text(
                text = "اسحب الصورة مطولاً لإعادة ترتيبها — الحفظ يحفظ الكل دفعة واحدة",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp)
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                shots.forEach { shot ->
                    val dragging = draggedId == shot.id
                    Row(
                        modifier = Modifier
                            .height(REVIEW_ROW_HEIGHT)
                            .fillMaxWidth()
                            .zIndex(if (dragging) 1f else 0f)
                            .offset { IntOffset(0, if (dragging) dragOffsetPx.roundToInt() else 0) }
                            .clip(MaterialTheme.shapes.medium)
                            .background(
                                if (dragging) MaterialTheme.colorScheme.secondaryContainer
                                else MaterialTheme.colorScheme.surfaceVariant
                            )
                            .pointerInput(shot.id) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = {
                                        draggedId = shot.id
                                        dragOffsetPx = 0f
                                    },
                                    onDragEnd = {
                                        draggedId = null
                                        dragOffsetPx = 0f
                                    },
                                    onDragCancel = {
                                        draggedId = null
                                        dragOffsetPx = 0f
                                    },
                                    onDrag = { change, drag ->
                                        change.consume()
                                        dragOffsetPx += drag.y
                                        val positions = (dragOffsetPx / rowHeightPx).roundToInt()
                                        if (positions != 0) {
                                            val from = shots.indexOfFirst { it.id == shot.id }
                                            val to = (from + positions).coerceIn(0, shots.size - 1)
                                            if (to != from && from >= 0) {
                                                val moved = shots.removeAt(from)
                                                shots.add(to, moved)
                                                dragOffsetPx -= positions * rowHeightPx
                                            }
                                        }
                                    }
                                )
                            }
                            .padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Filled.DragIndicator,
                            contentDescription = "اسحب لإعادة الترتيب",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                        Spacer(Modifier.width(6.dp))
                        Image(
                            bitmap = shot.thumbnail.asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier
                                .size(72.dp)
                                .clip(RoundedCornerShape(10.dp)),
                            contentScale = ContentScale.Crop
                        )
                        Spacer(Modifier.width(10.dp))
                        OutlinedTextField(
                            value = names[shot.id] ?: "",
                            onValueChange = { names[shot.id] = it },
                            label = { Text("اسم الصورة (اختياري)") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                            shape = MaterialTheme.shapes.small
                        )
                        IconButton(onClick = {
                            runCatching { shot.file.delete() }
                            shots.remove(shot)
                            names.remove(shot.id)
                        }) {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = "حذف الصورة",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }

            // شريط الإجراءات السفلي
            Surface(tonalElevation = 6.dp, shadowElevation = 10.dp) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onRequestCancelAll) {
                        Text("إلغاء", color = MaterialTheme.colorScheme.error)
                    }
                    Spacer(Modifier.weight(1f))
                    Button(
                        onClick = onSaveAll,
                        enabled = shots.isNotEmpty()
                    ) {
                        Text(
                            if (shots.size == 1) "إضافة المرفق"
                            else "إضافة المرفقات (${shots.size})"
                        )
                    }
                }
            }
        }
    }
}

// ─────────────────────────────── رفض الإذن ───────────────────────────────

/** حالة رفض إذن الكاميرا: رسالة واضحة + إعادة طلب + فتح إعدادات النظام إن لزم */
@Composable
private fun CameraPermissionContent(
    modifier: Modifier,
    onRequestAgain: () -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Filled.CameraAlt,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.7f),
            modifier = Modifier.size(52.dp)
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = "مطلوب إذن الكاميرا",
            color = Color.White,
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "رفضت الوصول للكاميرا، ولن تعمل ميزة الالتقاط بدون هذا الإذن. " +
                "يمكنك السماح الآن أو من إعدادات النظام.",
            color = Color.White.copy(alpha = 0.75f),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = onRequestAgain,
                colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black)
            ) { Text("السماح الآن") }
            Button(
                onClick = {
                    runCatching {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.fromParts("package", context.packageName, null)
                            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.White.copy(alpha = 0.15f),
                    contentColor = Color.White
                )
            ) { Text("إعدادات النظام") }
        }
        Spacer(Modifier.height(10.dp))
        TextButton(onClick = onClose) {
            Text("إغلاق", color = Color.White.copy(alpha = 0.8f))
        }
    }
}
