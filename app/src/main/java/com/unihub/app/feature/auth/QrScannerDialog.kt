package com.unihub.app.feature.auth

import android.Manifest
import android.content.pm.PackageManager
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import com.unihub.app.data.provision.ProvisioningProtocol
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

@Composable
internal fun QrScannerDialog(onScanned: (String) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var permitted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permitted = it }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn)) {
        Surface(shape = MaterialTheme.shapes.large) {
            Column(Modifier.fillMaxWidth().heightIn(max = 540.dp).verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("مسح باركود الاتصال", style = MaterialTheme.typography.titleLarge)
                if (permitted) CameraQrPreview(onScanned)
                else {
                    Text("نحتاج الكاميرا لمسح الباركود فقط؛ لا تُرفع صور الكاميرا ولا تُحفظ.")
                    Button(onClick = { launcher.launch(Manifest.permission.CAMERA) }) { Text("السماح بالكاميرا") }
                }
                Text("وجّه الكاميرا إلى باركود التطبيق. الروابط والباركود غير المعتمد لن يُفتحا.", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = onDismiss) { Text("إغلاق") }
            }
        }
    }
}

@Composable
private fun CameraQrPreview(onScanned: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val onResult by rememberUpdatedState(onScanned)
    var error by remember { mutableStateOf<String?>(null) }
    val previewView = remember(context) { PreviewView(context).apply { implementationMode = PreviewView.ImplementationMode.COMPATIBLE } }
    AndroidView(factory = { previewView }, modifier = Modifier.fillMaxWidth().height(320.dp))
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    DisposableEffect(lifecycleOwner, previewView) {
        val closed = AtomicBoolean(false)
        val delivered = AtomicBoolean(false)
        val executor = Executors.newSingleThreadExecutor()
        val main = ContextCompat.getMainExecutor(context)
        val future = ProcessCameraProvider.getInstance(context)
        val preview = Preview.Builder().build().apply { setSurfaceProvider(previewView.surfaceProvider) }
        val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setTargetResolution(Size(1280, 720)).build()
        val reader = MultiFormatReader().apply { setHints(mapOf(
            DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE), DecodeHintType.TRY_HARDER to true
        )) }
        var lastScan = 0L
        var luminance = ByteArray(0)
        analysis.setAnalyzer(executor) { image ->
            try {
                val now = android.os.SystemClock.elapsedRealtime()
                if (!closed.get() && !delivered.get() && now - lastScan >= 400L && image.width.toLong() * image.height <= 4_000_000L) {
                    lastScan = now
                    val width = image.width
                    val height = image.height
                    if (luminance.size != width * height) luminance = ByteArray(width * height)
                    val plane = image.planes.first()
                    val buffer = plane.buffer.duplicate()
                    val start = buffer.position()
                    for (row in 0 until height) {
                        buffer.position(start + row * plane.rowStride)
                        if (plane.pixelStride == 1) buffer.get(luminance, row * width, width)
                        else for (column in 0 until width) luminance[row * width + column] = buffer.get(start + row * plane.rowStride + column * plane.pixelStride)
                    }
                    val source = PlanarYUVLuminanceSource(luminance, width, height, 0, 0, width, height, false)
                    val value = reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text
                    val accepted = value.startsWith(ProvisioningProtocol.REQUEST_PREFIX) ||
                        value.startsWith(ProvisioningProtocol.ENVELOPE_PREFIX) || value.startsWith(CloudConnectionViewModel.INVITATION_PREFIX)
                    if (accepted && value.length <= ProvisioningProtocol.MAX_QR_CHARS && delivered.compareAndSet(false, true)) {
                        main.execute { if (!closed.get()) onResult(value) }
                    }
                }
            } catch (_: ReaderException) {
                // A frame with no QR is normal, not an error and not logged.
            } catch (_: Exception) {
                // Malformed/unsupported camera frames are dropped, never retained or logged.
            } finally { reader.reset(); image.close() }
        }
        future.addListener({
            if (!closed.get()) {
                try { future.get().bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis) }
                catch (_: Exception) { error = "تعذّر تشغيل الكاميرا؛ تحقق من الإذن أو أعد فتح الماسح" }
            }
        }, main)
        onDispose {
            closed.set(true)
            analysis.clearAnalyzer()
            if (future.isDone) runCatching { future.get().unbind(preview, analysis) }
            executor.shutdown()
        }
    }
}
