package com.unihub.app.feature.auth

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.core.content.FileProvider
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.unihub.app.data.provision.ProvisioningProtocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

internal object ConnectionQrImages {
    suspend fun render(text: String): Bitmap = withContext(Dispatchers.Default) {
        require(isConnectionQr(text))
        val bits = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 900, 900,
            mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 4))
        val pixels = IntArray(bits.width * bits.height)
        for (row in 0 until bits.height) {
            currentCoroutineContext().ensureActive()
            for (column in 0 until bits.width) pixels[row * bits.width + column] = if (bits[column, row]) 0xff000000.toInt() else 0xffffffff.toInt()
        }
        Bitmap.createBitmap(pixels, bits.width, bits.height, Bitmap.Config.ARGB_8888)
    }

    suspend fun decode(context: Context, uri: Uri): String = withContext(Dispatchers.IO) {
        require(uri.scheme == "content") { "اختر صورة من منتقي النظام" }
        val out = ByteArrayOutputStream()
        context.contentResolver.openInputStream(uri)?.use { input ->
            coroutineScope {
                val done = AtomicBoolean(false)
                val closeOnCancellation = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
                    try { awaitCancellation() }
                    finally { if (!done.get()) runCatching { input.close() } }
                }
                try {
                    val buffer = ByteArray(8_192)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (out.size() + count > 8 * 1024 * 1024) throw IOException("صورة الباركود أكبر من الحد المسموح")
                        out.write(buffer, 0, count)
                    }
                } finally { done.set(true); closeOnCancellation.cancel() }
            }
        } ?: throw IOException("تعذّر فتح الصورة")
        val bytes = out.toByteArray()
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        require(options.outWidth in 1..32_768 && options.outHeight in 1..32_768)
        options.inJustDecodeBounds = false
        options.inSampleSize = 1
        while (maxOf(options.outWidth, options.outHeight) / options.inSampleSize > 1_600) options.inSampleSize *= 2
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: throw IOException("الصورة غير صالحة")
        try {
            require(bitmap.width.toLong() * bitmap.height <= 3_000_000)
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            val reader = MultiFormatReader().apply { setHints(mapOf(
                DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE), DecodeHintType.TRY_HARDER to true
            )) }
            val value = try { reader.decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(bitmap.width, bitmap.height, pixels)))).text }
                finally { reader.reset(); pixels.fill(0) }
            if (!isConnectionQr(value)) throw IOException("الصورة لا تحتوي باركود اتصال معتمدًا")
            value
        } finally { bitmap.recycle(); bytes.fill(0) }
    }

    suspend fun share(context: Context, text: String) {
        val bitmap = render(text)
        var target: File? = null
        var dispatched = false
        try {
            val uri = withContext(Dispatchers.IO) {
                val directory = File(context.cacheDir, "connection_qr").apply { check(mkdirs() || isDirectory) }
                val cutoff = System.currentTimeMillis() - 60 * 60 * 1_000L
                directory.listFiles()?.filter { it.name.endsWith(".png") && it.lastModified() < cutoff }?.forEach { it.delete() }
                currentCoroutineContext().ensureActive()
                val output = File(directory, "qr_${UUID.randomUUID()}.png").also { target = it }
                output.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                currentCoroutineContext().ensureActive()
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", output)
            }
            withContext(Dispatchers.Main) {
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "image/png"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    clipData = ClipData.newRawUri("باركود اتصال محمي", uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(intent, "مشاركة باركود الاتصال"))
                dispatched = true
            }
        } finally {
            bitmap.recycle()
            if (!dispatched) withContext(NonCancellable + Dispatchers.IO) { target?.delete() }
        }
    }

    private fun isConnectionQr(value: String): Boolean = value.length <= ProvisioningProtocol.MAX_QR_CHARS &&
        (value.startsWith(ProvisioningProtocol.REQUEST_PREFIX) || value.startsWith(ProvisioningProtocol.ENVELOPE_PREFIX) ||
            value.startsWith(CloudConnectionViewModel.INVITATION_PREFIX))
}
