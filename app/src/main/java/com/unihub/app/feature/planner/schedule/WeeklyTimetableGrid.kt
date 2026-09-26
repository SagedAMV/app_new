package com.unihub.app.feature.planner.schedule

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.unihub.app.core.common.DateFormats
import com.unihub.app.core.common.Weekdays
import com.unihub.app.data.local.entity.LectureEntity
import com.unihub.app.data.local.entity.Weekday
import com.unihub.app.ui.components.TintChip
import kotlinx.coroutines.delay

/**
 * الجدول الأسبوعي الشبكي (طلب تعليمات.md — «عرض أوقات المحاضرات على شكل جداول»).
 *
 * الفكرة المركزية — «الدالة المنطقية للتواقيت»:
 * لا نرسم شبكة 24×7 ثابتة، بل نستخرج من المحاضرات نفسها حدودها الزمنية
 * (بداية ونهاية كل محاضرة) ونعرضها مرتبة بلا تكرار. الساعات التي لا تبدأ
 * ولا تنتهي عندها أي محاضرة (10 و11 بين محاضرتي 7-9 و12-2 مثلاً) لا تظهر
 * إطلاقاً «لأن وجودها دون فائدة» — الجدول يتكيف مع المحتوى ويتمدد المحاضرات
 * فيه أفقياً حسب مدتها الفعلية.
 */

/** مدى زمني بالدقائق من منتصف الليل */
internal data class MinuteRange(val start: Int, val end: Int)

/** تحويل محاضرة إلى مدى دقائق — نهاية فارغة/معكوسة تُعامل كساعة واحدة */
internal fun lectureRange(lecture: LectureEntity): MinuteRange? {
    val from = DateFormats.parseTimeOrNull(lecture.timeFrom) ?: return null
    val to = DateFormats.parseTimeOrNull(lecture.timeTo)
    val startMin = from.hour * 60 + from.minute
    var endMin = if (to != null) to.hour * 60 + to.minute else startMin + 60
    if (endMin <= startMin) endMin = startMin + 60
    return MinuteRange(startMin, endMin)
}

/**
 * الحدود الزمنية المعروضة: كل بداية ونهاية محاضرة، مرتبة وبلا تكرار.
 * هذه هي «الدالة» المطلوبة — مثلاً محاضرتا 07:00-09:00 و12:00-14:00
 * تعطيان [420, 540, 720, 840] أي 7ص، 9ص، 12م، 2م فقط.
 */
internal fun adaptiveTimeBoundaries(lectures: List<LectureEntity>): List<Int> =
    lectures.mapNotNull { lectureRange(it) }
        .flatMap { listOf(it.start, it.end) }
        .distinct()
        .sorted()

/** عرض مقطع زمني: يتناسب مع مدته بحد أدنى حتى لا تنهار المقاطع القصيرة */
internal fun segmentWidthDp(startMin: Int, endMin: Int, dpPerMinute: Float): Dp {
    val minutes = (endMin - startMin).coerceAtLeast(1)
    return (minutes * dpPerMinute).coerceAtLeast(MIN_SEGMENT_DP).dp
}

/** تنسيق دقيقة إلى تسمية عربية 12-ساعية: «7 ص»، «12 م»، «9:30 ص» */
internal fun formatHourLabel(totalMinutes: Int): String {
    val hour24 = (totalMinutes / 60) % 24
    val minute = totalMinutes % 60
    val suffix = if (hour24 < 12) "ص" else "م"
    val hour12 = if (hour24 % 12 == 0) 12 else hour24 % 12
    return if (minute == 0) "$hour12 $suffix"
    else "$hour12:${"%02d".format(minute)} $suffix"
}

private const val DP_PER_MINUTE = 1.1f
private val MIN_SEGMENT_DP = 40f
private val DAY_COLUMN_WIDTH = 66.dp
private val ROW_HEIGHT = 62.dp

@Composable
fun WeeklyTimetableGrid(
    lectures: List<LectureEntity>,
    modifier: Modifier = Modifier
) {
    val today = remember { Weekdays.today() }

    val rangesByLecture = remember(lectures) {
        lectures.mapNotNull { lecture -> lectureRange(lecture)?.let { lecture to it } }
    }
    val boundaries = remember(lectures) {
        adaptiveTimeBoundaries(lectures)
    }

    if (boundaries.size < 2) {
        Column(
            modifier = modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                "أضف محاضرات بأوقات بداية ونهاية ليُبنى الجدول الزمني عليها",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
        return
    }

    // مقاطع زمنية بين كل حدين متتاليين — عرض كل مقطع يُشتق من مدته
    val segments = boundaries.zipWithNext()
    val segmentWidths = segments.map { (a, b) -> segmentWidthDp(a, b, DP_PER_MINUTE) }
    val gridWidth = segmentWidths.reduce { acc, w -> acc + w }

    /** إزاحة أفقية بالدقائق من أول حد — للموضع الدقيق لكل محاضرة */
    fun xOffset(minute: Int): Dp {
        var x = 0f
        for (index in segments.indices) {
            val (a, b) = segments[index]
            val widthDp = segmentWidths[index].value
            if (minute <= a) return x.dp
            if (minute >= b) {
                x += widthDp
                continue
            }
            val fraction = (minute - a).toFloat() / (b - a)
            return (x + widthDp * fraction).dp
        }
        return x.dp
    }

    var gridVisible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { gridVisible = true }

    // تُقرأ ألوان السمة داخل السياق التركيبي فقط — لا في معرّفات عامة
    val blockColors = listOf(
        MaterialTheme.colorScheme.primaryContainer,
        MaterialTheme.colorScheme.secondaryContainer,
        MaterialTheme.colorScheme.tertiaryContainer
    )

    AnimatedVisibility(
        visible = gridVisible,
        modifier = modifier,
        enter = fadeIn(tween(450))
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .horizontalScroll(rememberScrollState())
                .padding(start = 4.dp, end = 4.dp, bottom = 90.dp)
        ) {
            // صف التواقيت الأفقي: حد زمني عند بداية كل مقطع.
            // إصلاح (جلسة التدقيق): صف الـ RTL يرتّب التسميات متلاصقةً تلقائياً،
            // عرض كل تسمية يساوي عرض مقطعها فتقع بدايته عند حدّه الزمني تماماً.
            // كانت التسميات تُزاح أيضاً بإزاحة تراكمية (مجموع عروض المقاطع السابقة)
            // فوق ترتيب الصف — أي تُحسب الإزاحة مرتين، فانجراف كل تسمية يزداد كلما
            // تقدمنا (في مثال 7-9 و12-2 تبتعد تسمية «12 م» مئات الـ dp عن موضعها).
            // ترتيب الصف وحده يكفي، والإزاحة الإضافية أزيلت.
            Row(verticalAlignment = Alignment.Bottom) {
                Spacer(Modifier.width(DAY_COLUMN_WIDTH))
                segments.forEachIndexed { index, (a, _) ->
                    Text(
                        text = formatHourLabel(a),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .width(segmentWidths[index])
                            .padding(start = 4.dp, bottom = 4.dp)
                    )
                }
            }

            // صفوف الأيام — عمود الأيام يميناً في اتجاه RTL لأنه أول عنصر في كل صف
            Weekday.entries.forEachIndexed { dayIndex, day ->
                val dayLectures = rangesByLecture.filter { it.first.day == day }
                WeekdayRow(
                    day = day,
                    dayIndex = dayIndex,
                    isToday = day == today,
                    lectures = dayLectures,
                    segmentWidths = segmentWidths,
                    gridWidth = gridWidth,
                    blockColors = blockColors,
                    xOffset = ::xOffset
                )
                Spacer(Modifier.height(6.dp))
            }
        }
    }
}

@Composable
private fun WeekdayRow(
    day: Weekday,
    dayIndex: Int,
    isToday: Boolean,
    lectures: List<Pair<LectureEntity, MinuteRange>>,
    segmentWidths: List<Dp>,
    gridWidth: Dp,
    blockColors: List<Color>,
    xOffset: (Int) -> Dp
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        // عمود الأيام (يمين الشاشة في الواجهة العربية)
        Column(
            modifier = Modifier
                .width(DAY_COLUMN_WIDTH)
                .height(ROW_HEIGHT),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = day.label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = if (isToday) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface
            )
            if (isToday) {
                Spacer(Modifier.height(2.dp))
                TintChip(
                    text = "اليوم",
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }

        // منطقة الشبكة: خلفية المقاطع + كتل المحاضرات الممددة
        Box(
            Modifier
                .width(gridWidth)
                .height(ROW_HEIGHT)
        ) {
            // خطوط المقاطع الخفيفة
            Row(Modifier.fillMaxSize()) {
                segmentWidths.forEach { width ->
                    Box(
                        Modifier
                            .width(width)
                            .fillMaxHeight()
                            .border(
                                width = 0.5.dp,
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
                            )
                    )
                }
            }

            // المحاضرات تتمدد أفقياً حسب مدتها بين حديها
            lectures.forEachIndexed { index, (lecture, range) ->
                val start = xOffset(range.start)
                val width = xOffset(range.end) - start
                LectureBlock(
                    lecture = lecture,
                    index = dayIndex * 10 + index,
                    tint = blockColors[index % blockColors.size],
                    modifier = Modifier
                        .offset(x = start)
                        .width(width)
                        .padding(3.dp)
                        .fillMaxHeight()
                )
            }
        }
    }
}

/** كتلة محاضرة واحدة مع دخول متدرج (تلاشي + تكبير) — «انميشن رهيب عند الدخول» */
@Composable
private fun LectureBlock(
    lecture: LectureEntity,
    index: Int,
    tint: Color,
    modifier: Modifier = Modifier
) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(120L + index * 70L)
        visible = true
    }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(500)) + scaleIn(
            initialScale = 0.7f,
            animationSpec = tween(500)
        )
    ) {
        Card(
            modifier = modifier,
            shape = RoundedCornerShape(10.dp),
            colors = CardDefaults.cardColors(containerColor = tint)
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = lecture.subject,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val meta = listOfNotNull(
                    lecture.room.takeIf { it.isNotBlank() }?.let { "قاعة $it" },
                    "${lecture.timeFrom}–${lecture.timeTo.ifBlank { "…" }}"
                ).joinToString(" • ")
                Text(
                    text = meta,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
                )
            }
        }
    }
}


