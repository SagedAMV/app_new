package com.unihub.app.feature.files.capture

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/** مراحل المسجل: خامل ← يسجّل ← متوقف مؤقتاً ← معاينة بعد الانتهاء */
private enum class RecorderPhase { IDLE, RECORDING, PAUSED, PREVIEW }

/** سرعات التشغيل المتاحة في المعاينة */
private val PLAYBACK_SPEEDS = listOf(1f, 1.5f, 2f)

/**
 * واجهة التسجيل الصوتي الاحترافية — لوح سفلي يمر بثلاث مراحل:
 *  1) زر تسجيل دائري كبير في المنتصف.
 *  2) أثناء التسجيل: نبض متحرك حول زر الإيقاف، موجة صوتية حقيقية تُرسم من سعة
 *     الميكروفون الفعلية (MediaRecorder.maxAmplitude كل 100ms)، مؤقت بصيغة 00:00،
 *     وأزرار إيقاف مؤقت / استئناف / إيقاف نهائي.
 *  3) المعاينة: مشغل كامل (تشغيل/إيقاف، شريط تقدم قابل للسحب، وقت حالي/كلي،
 *     سرعة 1x/1.5x/2x، موجة ثابتة قابلة للنقر للتنقل) + اسم اختياري +
 *     حذف وإعادة تسجيل + حفظ.
 *
 * الملف يُسجَّل في كاش التطبيق ولا ينتقل لمكتبة الملفات إلا بضغط "حفظ" —
 * والإغلاق بأي طريقة ينظف المسجل والمشغل والملف المؤقت.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioRecorderSheet(
    onDismiss: () -> Unit,
    onSave: (file: File, name: String?) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    fun showMessage(message: String) {
        scope.launch { snackbarHostState.showSnackbar(message) }
    }

    // ── إذن الميكروفون ──
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasPermission = granted }
    LaunchedEffect(Unit) {
        if (!hasPermission) permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    // ── حالة الجلسة ──
    var phase by remember { mutableStateOf(RecorderPhase.IDLE) }
    var recordedFile by remember { mutableStateOf<File?>(null) }
    var amplitudes by remember { mutableStateOf<List<Float>>(emptyList()) }
    var elapsedMs by remember { mutableStateOf(0L) }
    var name by remember { mutableStateOf("") }

    // ── حالة المعاينة ──
    var isPlaying by remember { mutableStateOf(false) }
    var positionMs by remember { mutableStateOf(0) }
    var durationMs by remember { mutableStateOf(0) }
    var speed by remember { mutableStateOf(1f) }

    // مراجع مستقرة للموارد الأصلية — لا تُخزَّن في حالة قابلة لإعادة التركيب
    val recorderRef = remember { AtomicReference<MediaRecorder?>(null) }
    val playerRef = remember { AtomicReference<MediaPlayer?>(null) }

    // ── دوال المسجل ──
    fun buildAndStart() {
        runCatching {
            val file = File.createTempFile("voice_", ".m4a", context.cacheDir)
            @Suppress("DEPRECATION")
            val recorder = MediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(128_000)
                setAudioSamplingRate(44_100)
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }
            recorderRef.set(recorder)
            recordedFile = file
            elapsedMs = 0L
            amplitudes = emptyList()
            phase = RecorderPhase.RECORDING
        }.onFailure {
            showMessage("تعذّر بدء التسجيل — تأكد أن الميكروفون غير مستخدم من تطبيق آخر")
        }
    }

    fun pauseRecording() {
        recorderRef.get()?.let { runCatching { it.pause() } }
        phase = RecorderPhase.PAUSED
    }

    fun resumeRecording() {
        recorderRef.get()?.let { runCatching { it.resume() } }
        phase = RecorderPhase.RECORDING
    }

    fun preparePlayer() {
        val file = recordedFile ?: return
        runCatching {
            val player = MediaPlayer()
            player.setDataSource(file.absolutePath)
            player.setOnCompletionListener {
                isPlaying = false
                positionMs = durationMs
            }
            player.prepare()
            durationMs = player.duration
            positionMs = 0
            playerRef.set(player)
        }.onFailure {
            showMessage("تعذّر تجهيز المعاينة الصوتية")
        }
    }

    fun stopRecording() {
        recorderRef.get()?.let { recorder ->
            runCatching { recorder.stop() }
            runCatching { recorder.release() }
        }
        recorderRef.set(null)
        preparePlayer()
        phase = RecorderPhase.PREVIEW
    }

    fun togglePlayback() {
        val player = playerRef.get() ?: return
        if (isPlaying) {
            runCatching { player.pause() }
            isPlaying = false
        } else {
            runCatching {
                if (durationMs > 0 && positionMs >= durationMs) player.seekTo(0)
                player.playbackParams = player.playbackParams.setSpeed(speed)
                player.start()
                isPlaying = true
            }
        }
    }

    fun seekToFraction(fraction: Float) {
        if (durationMs <= 0) return
        val target = (fraction * durationMs).toInt().coerceIn(0, durationMs)
        playerRef.get()?.let { runCatching { it.seekTo(target) } }
        positionMs = target
    }

    fun applySpeed(newSpeed: Float) {
        speed = newSpeed
        val player = playerRef.get() ?: return
        if (isPlaying) {
            // تغيير السرعة أثناء التشغيل فوري — setSpeed على مشغل متوقف يبدأه، لذا نحصره هنا
            runCatching { player.playbackParams = player.playbackParams.setSpeed(newSpeed) }
        }
    }

    fun resetForRetake() {
        playerRef.get()?.let { player ->
            runCatching { if (player.isPlaying) player.stop() }
            runCatching { player.release() }
        }
        playerRef.set(null)
        recordedFile?.let { runCatching { it.delete() } }
        recordedFile = null
        amplitudes = emptyList()
        elapsedMs = 0L
        isPlaying = false
        positionMs = 0
        durationMs = 0
        phase = RecorderPhase.IDLE
    }

    // ── مؤقت + موجة حية أثناء التسجيل الفعلي فقط ──
    LaunchedEffect(phase) {
        if (phase == RecorderPhase.RECORDING) {
            val resumedAt = SystemClock.elapsedRealtime()
            val baseElapsed = elapsedMs
            while (true) {
                delay(100)
                elapsedMs = baseElapsed + (SystemClock.elapsedRealtime() - resumedAt)
                @Suppress("DEPRECATION")
                val amp = runCatching { recorderRef.get()?.maxAmplitude ?: 0 }.getOrDefault(0)
                amplitudes = (amplitudes + (amp / 32767f).coerceIn(0.05f, 1f)).takeLast(600)
            }
        }
    }

    // ── تحديث موضع التشغيل أثناء المعاينة ──
    LaunchedEffect(isPlaying) {
        while (isPlaying) {
            positionMs = runCatching { playerRef.get()?.currentPosition ?: 0 }.getOrDefault(0)
            delay(100)
        }
    }

    // ── تنظيف مضمون عند إغلاق اللوح بأي طريقة (سحبة، زر، حفظ…) ──
    DisposableEffect(Unit) {
        onDispose {
            recorderRef.get()?.let { recorder ->
                runCatching { recorder.stop() }
                runCatching { recorder.release() }
            }
            playerRef.get()?.let { player ->
                runCatching { if (player.isPlaying) player.stop() }
                runCatching { player.release() }
            }
            recordedFile?.let { runCatching { it.delete() } }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "تسجيل صوتي",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = "إغلاق")
                }
            }
            SnackbarHost(snackbarHostState)

            when {
                !hasPermission -> MicPermissionContent(
                    onRequestAgain = { permissionLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                    onOpenSettings = {
                        runCatching {
                            context.startActivity(
                                Intent(
                                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.fromParts("package", context.packageName, null)
                                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }
                    },
                    onClose = onDismiss
                )

                phase == RecorderPhase.IDLE -> IdleRecorder(onStart = { buildAndStart() })

                phase == RecorderPhase.PREVIEW -> PreviewPlayer(
                    amplitudes = amplitudes,
                    isPlaying = isPlaying,
                    positionMs = positionMs,
                    durationMs = durationMs,
                    speed = speed,
                    name = name,
                    onNameChange = { name = it },
                    onTogglePlay = { togglePlayback() },
                    onSeekFraction = { seekToFraction(it) },
                    onSeekMs = { ms ->
                        positionMs = ms
                        playerRef.get()?.let { runCatching { it.seekTo(ms) } }
                    },
                    onSpeedChange = { applySpeed(it) },
                    onRetake = { resetForRetake() },
                    onSave = {
                        val file = recordedFile
                        if (file != null) {
                            onSave(file, name.trim().takeIf { it.isNotBlank() })
                        }
                    }
                )

                else -> RecordingControls(
                    phase = phase,
                    elapsedMs = elapsedMs,
                    amplitudes = amplitudes,
                    onPause = { pauseRecording() },
                    onResume = { resumeRecording() },
                    onStop = { stopRecording() }
                )
            }
        }
    }
}

// ─────────────────────────────── مراحل الواجهة ───────────────────────────────

/** الحالة الخاملة: زر ميكروفون كبير يدعو لبدء التسجيل */
@Composable
private fun IdleRecorder(onStart: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(92.dp)
                .background(MaterialTheme.colorScheme.primaryContainer, CircleShape)
                .clickable(onClick = onStart),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.Mic,
                contentDescription = "بدء التسجيل",
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(40.dp)
            )
        }
        Spacer(Modifier.height(14.dp))
        Text(
            text = "اضغط لبدء التسجيل",
            style = MaterialTheme.typography.titleSmall
        )
        Text(
            text = "يمكنك الإيقاف المؤقت والاستئناف أثناء التسجيل",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * واجهة التسجيل النشط: مؤقت كبير، موجة حية من الميكروفون، حلقتا نبض حول زر
 * الإيقاف النهائي، وزر إيقاف مؤقت/استئناف.
 */
@Composable
private fun RecordingControls(
    phase: RecorderPhase,
    elapsedMs: Long,
    amplitudes: List<Float>,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit
) {
    val recording = phase == RecorderPhase.RECORDING
    val pulse = rememberInfiniteTransition(label = "recorderPulse")
    val ringScale1 by pulse.animateFloat(
        initialValue = 1f,
        targetValue = 1.75f,
        animationSpec = infiniteRepeatable(tween(1300), RepeatMode.Restart),
        label = "ring1"
    )
    val ringAlpha1 by pulse.animateFloat(
        initialValue = 0.55f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(tween(1300), RepeatMode.Restart),
        label = "ringA1"
    )
    val ringScale2 by pulse.animateFloat(
        initialValue = 1f,
        targetValue = 1.75f,
        animationSpec = infiniteRepeatable(tween(1300, delayMillis = 650), RepeatMode.Restart),
        label = "ring2"
    )
    val ringAlpha2 by pulse.animateFloat(
        initialValue = 0.55f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(tween(1300, delayMillis = 650), RepeatMode.Restart),
        label = "ringA2"
    )

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(
            text = formatDuration(elapsedMs),
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = if (recording) "جارٍ التسجيل…" else "متوقف مؤقتاً",
            style = MaterialTheme.typography.labelLarge,
            color = if (recording) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurfaceVariant
        )

        LiveWaveform(
            amplitudes = amplitudes,
            active = recording,
            modifier = Modifier
                .fillMaxWidth()
                .height(72.dp)
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // إيقاف مؤقت / استئناف
            FilledTonalButton(onClick = if (recording) onPause else onResume) {
                Icon(
                    imageVector = if (recording) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(if (recording) "إيقاف مؤقت" else "استئناف")
            }

            Spacer(Modifier.width(26.dp))

            // الإيقاف النهائي — حلقتا نبض متعاكستان أثناء التسجيل الفعلي
            Box(contentAlignment = Alignment.Center) {
                if (recording) {
                    Box(
                        Modifier
                            .size(78.dp)
                            .scale(ringScale1)
                            .alpha(ringAlpha1)
                            .background(MaterialTheme.colorScheme.error, CircleShape)
                    )
                    Box(
                        Modifier
                            .size(78.dp)
                            .scale(ringScale2)
                            .alpha(ringAlpha2)
                            .background(MaterialTheme.colorScheme.error, CircleShape)
                    )
                }
                Box(
                    modifier = Modifier
                        .size(78.dp)
                        .background(MaterialTheme.colorScheme.error, CircleShape)
                        .clickable(onClick = onStop),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.Stop,
                        contentDescription = "إنهاء التسجيل والانتقال للمعاينة",
                        tint = MaterialTheme.colorScheme.onError,
                        modifier = Modifier.size(36.dp)
                    )
                }
            }
        }

        Text(
            text = "الموجة تعكس مستوى صوتك الحقيقي من الميكروفون",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * واجهة المعاينة بعد الانتهاء: مشغل كامل بموجة ثابتة قابلة للنقر للتنقل،
 * شريط تقدم، وقت حالي/كلي، سرعات تشغيل، اسم اختياري، وحفظ أو إعادة تسجيل.
 */
@Composable
private fun PreviewPlayer(
    amplitudes: List<Float>,
    isPlaying: Boolean,
    positionMs: Int,
    durationMs: Int,
    speed: Float,
    name: String,
    onNameChange: (String) -> Unit,
    onTogglePlay: () -> Unit,
    onSeekFraction: (Float) -> Unit,
    onSeekMs: (Int) -> Unit,
    onSpeedChange: (Float) -> Unit,
    onRetake: () -> Unit,
    onSave: () -> Unit
) {
    val progress = if (durationMs > 0) positionMs.toFloat() / durationMs else 0f

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(54.dp)
                        .background(MaterialTheme.colorScheme.primary, CircleShape)
                        .clickable(onClick = onTogglePlay),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (isPlaying) "إيقاف التشغيل" else "تشغيل",
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(28.dp)
                    )
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "${formatDuration(positionMs.toLong())} / ${formatDuration(durationMs.toLong())}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "اضغط على الموجة للانتقال لأي موضع",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // الموجة الثابتة القابلة للنقر
            StaticWaveform(
                amplitudes = amplitudes,
                progress = progress,
                onSeekFraction = onSeekFraction,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
            )

            // شريط تقدم قابل للسحب
            Slider(
                value = positionMs.toFloat(),
                onValueChange = { onSeekMs(it.toInt()) },
                valueRange = 0f..(durationMs.coerceAtLeast(1)).toFloat(),
                modifier = Modifier.fillMaxWidth()
            )

            // سرعات التشغيل
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "السرعة:",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                PLAYBACK_SPEEDS.forEach { s ->
                    FilterChip(
                        selected = speed == s,
                        onClick = { onSpeedChange(s) },
                        label = {
                            Text(
                                if (s == s.toLong().toFloat()) "${s.toLong()}x" else "${s}x"
                            )
                        }
                    )
                }
            }
        }
    }

    OutlinedTextField(
        value = name,
        onValueChange = onNameChange,
        label = { Text("اسم التسجيل (اختياري)") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically
    ) {
        TextButton(onClick = onRetake) {
            Icon(
                Icons.Filled.Replay,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text("حذف وإعادة تسجيل", color = MaterialTheme.colorScheme.error)
        }
        Spacer(Modifier.weight(1f))
        Button(onClick = onSave) {
            Text("حفظ")
        }
    }
}

/** حالة رفض إذن الميكروفون — رسالة واضحة وخيارا إصلاح */
@Composable
private fun MicPermissionContent(
    onRequestAgain: () -> Unit,
    onOpenSettings: () -> Unit,
    onClose: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Filled.Mic,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(44.dp)
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = "مطلوب إذن الميكروفون",
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center
        )
        Text(
            text = "رفضت الوصول للميكروفون، ولن يعمل التسجيل الصوتي بدون هذا الإذن.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = onRequestAgain) { Text("السماح الآن") }
            FilledTonalButton(onClick = onOpenSettings) { Text("إعدادات النظام") }
        }
        TextButton(onClick = onClose) { Text("إغلاق") }
    }
}

// ─────────────────────────────── رسم الموجات ───────────────────────────────

/** موجة حية أثناء التسجيل — تعرض آخر 56 عينة من الميكروفون */
@Composable
private fun LiveWaveform(
    amplitudes: List<Float>,
    active: Boolean,
    modifier: Modifier = Modifier
) {
    val activeColor = MaterialTheme.colorScheme.error
    val idleColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
    Canvas(modifier) {
        val visible = amplitudes.takeLast(56)
        if (visible.isEmpty()) return@Canvas
        val barCount = 56
        val barWidth = size.width / barCount
        val start = barCount - visible.size
        visible.forEachIndexed { i, amp ->
            val h = (amp * size.height).coerceAtLeast(5f)
            drawRoundRect(
                color = if (active) activeColor else idleColor,
                topLeft = Offset(
                    x = (start + i) * barWidth + barWidth * 0.22f,
                    y = (size.height - h) / 2f
                ),
                size = Size(barWidth * 0.56f, h),
                cornerRadius = CornerRadius(barWidth * 0.28f)
            )
        }
    }
}

/**
 * موجة ثابتة لكامل التسجيل — تُضغط العينات في 72 عموداً، يلوّن المنجز منها،
 * والنقر على أي موضع ينقل التشغيل إليه.
 */
@Composable
private fun StaticWaveform(
    amplitudes: List<Float>,
    progress: Float,
    onSeekFraction: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val playedColor = MaterialTheme.colorScheme.primary
    val baseColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
    Canvas(
        modifier.pointerInput(amplitudes) {
            detectTapGestures { offset ->
                onSeekFraction((offset.x / size.width).coerceIn(0f, 1f))
            }
        }
    ) {
        val bars = 72
        if (amplitudes.isEmpty()) return@Canvas
        val step = (amplitudes.size / bars).coerceAtLeast(1)
        val barWidth = size.width / bars
        repeat(bars) { i ->
            val amp = amplitudes.getOrNull(i * step) ?: 0.08f
            val h = (amp * size.height).coerceAtLeast(5f)
            drawRoundRect(
                color = if (i / bars.toFloat() <= progress) playedColor else baseColor,
                topLeft = Offset(
                    x = i * barWidth + barWidth * 0.22f,
                    y = (size.height - h) / 2f
                ),
                size = Size(barWidth * 0.56f, h),
                cornerRadius = CornerRadius(barWidth * 0.28f)
            )
        }
    }
}

/** تنسيق مدة بصيغة 00:00 (دقائق:ثوانٍ) — Locale.US يضمن أرقاماً لاتينية ثابتة بغضّ عن لغة الجهاز */
private fun formatDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    return String.format(
        java.util.Locale.US,
        "%02d:%02d",
        totalSeconds / 60,
        totalSeconds % 60
    )
}
