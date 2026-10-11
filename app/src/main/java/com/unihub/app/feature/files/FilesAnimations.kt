package com.unihub.app.feature.files

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.util.LruCache
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.hypot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/*
 * أدوات انميشن واجهة الملفات — كلها معزولة هنا كي يبقى FilesScreen.kt مقروءاً.
 *
 *  1) RevealStyle / reveal : دخول متدرّج لكل نوع عنصر (بطاقة ترحيب، عنوان قسم،
 *     مجلد، ملف عادي، ملف صورة) — لكل نوع حركة مختلفة.
 *  2) TapWashState / tapWash : موجة لون تتّسع من موضع اللمس عند النقر.
 *  3) ImageThumbnail : مصغّرة صورة تُحمَّل بالخلفية بلمعان انتظار ثم «تظهر» بتكبير وتلاشٍ.
 */

/** منحنى «التباطؤ المؤكَّد» من Material 3 — يعطي إحساس الانزلاق الناعم عند الوصول. */
internal val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1.0f)

/** أنواع حركات الدخول */
internal enum class RevealStyle {
    /** بطاقة المكتبة العلوية: تنزل من الأعلى مع تكبير خفيف */
    Hero,

    /** عناوين الأقسام: تنزلق من جهة النهاية */
    Header,

    /** المجلدات: «قفزة» نابضة من الأسفل مع تدوير بسيط */
    Folder,

    /** الملفات العادية: انزلاق أفقي من جهة النهاية */
    File,

    /** الصور: تكبير نابض من المنتصف مع ميلان خفيف */
    Image
}

/**
 * يبني Animatable للدخول. العنصر الذي دخل مرة (مفتاحه في [played]) لا يعيد الحركة
 * عند عودته بالتمرير، فلا يتحرك المحتوى كلما خرج من الشاشة ورجع.
 * [played] = null ⇒ تُشغَّل الحركة دائماً (القوائم المنبثقة).
 */
@Composable
internal fun rememberReveal(
    key: Any,
    index: Int,
    played: MutableSet<Any>?,
    style: RevealStyle
): Animatable<Float, AnimationVector1D> {
    val alreadyPlayed = remember(key) { played?.contains(key) == true }
    val progress = remember(key) { Animatable(if (alreadyPlayed) 1f else 0f) }
    LaunchedEffect(key) {
        if (!alreadyPlayed) {
            played?.add(key)
            // التدرّج الزمني للعناصر العشرة الأولى فقط؛ ما بعدها يدخل فور ظهوره بالتمرير
            if (index < 10) delay(index * 45L + 40L)
            when (style) {
                RevealStyle.Folder -> progress.animateTo(1f, spring(dampingRatio = 0.58f, stiffness = 280f))
                RevealStyle.Image -> progress.animateTo(1f, spring(dampingRatio = 0.62f, stiffness = 240f))
                RevealStyle.File -> progress.animateTo(1f, spring(dampingRatio = 0.82f, stiffness = 320f))
                RevealStyle.Hero, RevealStyle.Header ->
                    progress.animateTo(1f, tween(durationMillis = 460, easing = EmphasizedDecelerate))
            }
        }
    }
    return progress
}

/** يطبّق تحويل الدخول على الطبقة الرسومية (بدون إعادة تركيب أثناء الحركة). */
internal fun Modifier.revealTransform(
    style: RevealStyle,
    progress: Animatable<Float, AnimationVector1D>,
    rtl: Boolean
): Modifier = graphicsLayer {
    val p = progress.value
    val inv = 1f - p
    // «جهة النهاية»: يمين في LTR ويسار في RTL — translationX مطلق لا يتبع الاتجاه
    val endSign = if (rtl) -1f else 1f
    alpha = (p * 1.5f).coerceIn(0f, 1f)
    when (style) {
        RevealStyle.Hero -> {
            translationY = -inv * 24.dp.toPx()
            val s = 0.94f + 0.06f * p
            scaleX = s
            scaleY = s
        }
        RevealStyle.Header -> {
            translationX = endSign * inv * 36.dp.toPx()
        }
        RevealStyle.Folder -> {
            val s = 0.5f + 0.5f * p
            scaleX = s
            scaleY = s
            translationY = inv * 40.dp.toPx()
            rotationZ = -endSign * inv * 8f
        }
        RevealStyle.File -> {
            translationX = endSign * inv * 72.dp.toPx()
            val s = 0.95f + 0.05f * p
            scaleX = s
            scaleY = s
        }
        RevealStyle.Image -> {
            val s = 0.65f + 0.35f * p
            scaleX = s
            scaleY = s
            translationY = inv * 24.dp.toPx()
            rotationZ = endSign * inv * 5f
        }
    }
}

/** اختصار: يبني الحركة ويطبّقها في خطوة واحدة. */
@Composable
internal fun Modifier.reveal(
    key: Any,
    index: Int,
    played: MutableSet<Any>?,
    style: RevealStyle
): Modifier {
    val progress = rememberReveal(key, index, played, style)
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    return this.revealTransform(style, progress, rtl)
}

// ─────────────────────────────────────────────────────────────────────────────
//  موجة النقر
// ─────────────────────────────────────────────────────────────────────────────

/** حالة موجة اللون: التقدّم 0..1 + نقطة اللمس الأخيرة. */
internal class TapWashState {
    val progress = Animatable(0f)
    var origin by mutableStateOf(Offset.Unspecified)

    suspend fun play() {
        progress.snapTo(0f)
        progress.animateTo(1f, tween(durationMillis = 460, easing = EmphasizedDecelerate))
    }
}

/** تلتقط موضع اللمس من مصدر التفاعل نفسه، فتتّسع الموجة من تحت الإصبع تماماً. */
@Composable
internal fun rememberTapWash(interactionSource: MutableInteractionSource): TapWashState {
    val state = remember { TapWashState() }
    LaunchedEffect(interactionSource) {
        interactionSource.interactions.collect { interaction ->
            if (interaction is PressInteraction.Press) state.origin = interaction.pressPosition
        }
    }
    return state
}

/** يرسم الموجة خلف المحتوى (داخل حدود البطاقة لأن Card يقصّ بشكله). */
internal fun Modifier.tapWash(state: TapWashState, color: Color): Modifier = drawBehind {
    val p = state.progress.value
    if (p > 0f && p < 1f) {
        val from = if (state.origin.isSpecified) state.origin else center
        val maxRadius = hypot(size.width, size.height)
        drawCircle(
            color = color.copy(alpha = 0.26f * (1f - p)),
            radius = maxRadius * p,
            center = from
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  مصغّرات الصور
// ─────────────────────────────────────────────────────────────────────────────

/** ذاكرة مؤقتة للمصغّرات: تمنع فكّ الصورة من جديد كلما عاد الصف للظهور. */
internal object ThumbnailCache {
    private val cache = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 1024 / 10).toInt().coerceAtLeast(2048)
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    fun get(key: String): Bitmap? = cache.get(key)

    fun put(key: String, bitmap: Bitmap) {
        cache.put(key, bitmap)
    }
}

/** فكّ صورة مصغّرة على خيط الإدخال/الإخراج مع احترام اتجاه EXIF. */
private suspend fun loadThumbnail(path: String, targetPx: Int): Bitmap? =
    withContext(Dispatchers.IO) {
        runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null

            var sample = 1
            while (bounds.outWidth / (sample * 2) >= targetPx && bounds.outHeight / (sample * 2) >= targetPx) {
                sample *= 2
            }
            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            val raw = BitmapFactory.decodeFile(path, options) ?: return@runCatching null

            // قراءة EXIF اختيارية: فشلها (png/webp مثلاً) لا يُسقط الصورة المفكوكة
            val orientation = runCatching {
                ExifInterface(path).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
            }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
            val degrees = when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
            if (degrees == 0f) {
                raw
            } else {
                val rotated = Bitmap.createBitmap(
                    raw, 0, 0, raw.width, raw.height,
                    Matrix().apply { postRotate(degrees) }, true
                )
                if (rotated !== raw) raw.recycle()
                rotated
            }
        }.getOrNull()
    }

/** لمعان انتظار يعبر مكان المصغّرة أثناء التحميل. */
@Composable
private fun ThumbnailShimmer(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "thumb-shimmer")
    val sweep by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1100, easing = LinearEasing), RepeatMode.Restart),
        label = "thumb-shimmer-sweep"
    )
    val base = MaterialTheme.colorScheme.surfaceVariant
    val highlight = MaterialTheme.colorScheme.surface
    Box(
        modifier.drawBehind {
            val w = size.width
            val start = -w + 2f * w * sweep
            drawRect(
                brush = Brush.horizontalGradient(
                    colors = listOf(base, highlight, base),
                    startX = start,
                    endX = start + w
                )
            )
        }
    )
}

/**
 * مصغّرة صورة: تُحمَّل بالخلفية ثم «تتكشّف» (تكبير 1.3× → 1× مع تلاشٍ).
 * إن كانت محفوظة في الذاكرة المؤقتة تظهر فوراً بلا حركة. عند الفشل يُرسم [fallback].
 */
@Composable
internal fun ImageThumbnail(
    path: String,
    sizeBytes: Long,
    modifier: Modifier = Modifier,
    boxSize: Dp = 46.dp,
    fallback: @Composable () -> Unit
) {
    val cacheKey = remember(path, sizeBytes) { "$path|$sizeBytes" }
    val cached = remember(cacheKey) { ThumbnailCache.get(cacheKey) }
    var bitmap by remember(cacheKey) { mutableStateOf(cached) }
    var failed by remember(cacheKey) { mutableStateOf(false) }
    val reveal = remember(cacheKey) { Animatable(if (cached != null) 1f else 0f) }

    LaunchedEffect(cacheKey) {
        if (bitmap == null) {
            val loaded = loadThumbnail(path, targetPx = 192)
            if (loaded != null) {
                ThumbnailCache.put(cacheKey, loaded)
                bitmap = loaded
                reveal.animateTo(1f, tween(durationMillis = 560, easing = EmphasizedDecelerate))
            } else {
                failed = true
            }
        }
    }

    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier = modifier
            .size(boxSize)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        val current = bitmap
        when {
            failed -> fallback()
            current == null -> ThumbnailShimmer(Modifier.fillMaxSize())
            else -> Image(
                bitmap = current.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        val r = reveal.value
                        val s = 1.3f - 0.3f * r
                        scaleX = s
                        scaleY = s
                        alpha = r.coerceIn(0f, 1f)
                    }
            )
        }
    }
}
