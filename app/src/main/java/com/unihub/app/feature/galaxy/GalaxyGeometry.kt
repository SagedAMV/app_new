package com.unihub.app.feature.galaxy

import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * الهندسة الموحّدة لعناقيد المجرّة — مصدر الحقيقة الوحيد لكل حسابات
 * الأنصاف والبصمات في شاشة المجرّة.
 *
 * سبب وجود هذا الملف (إصلاح جلسة اليوم وفق تعليمات.md):
 * الشكوى كانت «إذا كثرت مجلدات الأبناء تظهر ملفات خارج الدائرة الحاضنة».
 *
 * الجذر الحقيقي (تحليل هذه الجلسة بالأرقام):
 * النسخة السابقة كانت تحصر العمود بحدّ رياضي صحيح… لكن تحت افتراض أن كتلة
 * التسمية تحت الكوكب = [36] نقطة بالضبط (مباعدة 4 + سطران × 16). هذا
 * الافتراض غير مضمون على الجهاز الحقيقي: النص العربي فواصله ورسمه أعلى،
 * وحشوة الخط الافتراضية في Compose (includeFontPadding = true) تضيف ارتفاعاً
 * إضافياً، وأي تكبير لحجم الخط في النظام يزيد الارتفاع أكثر. المحاكاة
 * الرقمية أثبتت: هامش الحصر كان 6 نقاط فقط، وأي كتلة تسمية فعلية ≥ 44
 * تخترق الدائرة (0.5 ← 7 نقاط كلما كبر الخط) — وكلما كثر الأبناء زاد عدد
 * الكواكب في النصف السفلي من المدار فظهر الاختراق أكثر. الاختبارات السابقة
 * كانت تثبت الحدّ الرياضي بافتراضه نفسه (استدلال دائري) فلم تكشف الخلل.
 *
 * الإصلاح الجذري هنا: قلب المعادلة — بدل تخمين ارتفاع النص، نفرضه في
 * الواجهة: كتلة التسمية في عمود الكوكب صارت صندوقاً بارتفاع ثابت
 * [LABELS_BOX_DP] مقصوصاً (clipToBounds) بنصّين بارتفاع سطر مثبت
 * (16sp) وبلا حشوة خط (includeFontPadding = false) — انظر PlanetColumn في
 * GalaxyScreen. بذلك يصبح الارتفاع حقيقة مضمونة على أي جهاز وأي خط وأي حجم
 * خط، ويبقى الحدّ الرياضي هنا مضموناً فعلاً لا افتراضاً:
 *
 * متباينة المثلث تعطي أن أبعد نقطة في عمود الابن عن مركز العنقود لا
 * تتجاوز: نصف قطر المدار + نصف قطر عمود الابن (قطرياً) + هامش الأمان
 * [CONFINEMENT_PADDING_DP] — لأي زاوية كان عليها الابن ومهما كثر العدد.
 *
 * كما يبقى هنا الحدّ الأدنى الهندسي للمدار الذي يضمن مسافة لا تقل عن
 * [MIN_PLANET_SEPARATION_DP] بين أي كوكبين متجاورين على المدار.
 *
 * كل الدوال نقية بلا أي اعتماد على أندرويد — قابلة للاختبار على JVM مباشرة.
 */
internal object GalaxyGeometry {

    /** نصف عرض عمود الكوكب الابن (العمود 78dp وعرضه مثبّت في واجهة الشاشة) */
    const val CHILD_COLUMN_HALF_WIDTH_DP = 39f

    /** المباعدة بين قرص الكوكب وكتلة التسمية تحته — نفس قيمتها في PlanetColumn */
    const val LABEL_SPACER_DP = 4f

    /**
     * ارتفاع صندوق التسميات المثبّت في الواجهة (سطران × 16sp مع
     * includeFontPadding = false). الصندوق مقصوص (clipToBounds) ومثبت
     * أعلاه، فلا يتجاوز ارتفاعه الفعلي هذه القيمة على أي جهاز أو حجم خط —
     * هذا الفرض هو ما يجعل حدّ الحصر أدناه مضموناً واقعياً لا افتراضياً.
     */
    const val LABELS_BOX_DP = 40f

    /**
     * ارتفاع كتلة التسمية المتدلية تحت الكوكب كاملة = المباعدة + صندوق
     * التسميات. مصدر حقيقة واحد تستعمله الهندسة هنا والواجهة معاً.
     */
    const val LABEL_BLOCK_DP = LABEL_SPACER_DP + LABELS_BOX_DP

    /** هامش أمان بين أبعد نقطة في عمود الابن ومحيط الدائرة الحاضنة */
    const val CONFINEMENT_PADDING_DP = 8f

    /** هامش حول البصمة حتى لا تلامس الدائرة حواف خليتها في الشبكة */
    const val OUTER_MARGIN_DP = 26f

    /** أدنى مسافة مضمونة بين مركزي كوكبين ابنين متجاورين على المدار */
    const val MIN_PLANET_SEPARATION_DP = 34f

    /** قطر كوكب الأم: يكبر مع عدد الملفات بحد أعلى (30..48) */
    fun parentPlanetSizeDp(fileCount: Int): Float = 30f + min(fileCount * 2f, 18f)

    /** قطر كوكب الابن: يكبر مع عدد ملفاته بحد أعلى (20..30) */
    fun childPlanetSizeDp(fileCount: Int): Float = 20f + min(fileCount * 2f, 10f)

    /**
     * نصف قطر مدار الأبناء: يكبر مع العدد كما في التصميم الأصلي، وبحدّ
     * هندسي أدنى يضمن ألا يتقابل كوكبان متجاوران بأقل من
     * [MIN_PLANET_SEPARATION_DP] — ضلع مضلع منتظم ذي [childCount] رأساً
     * على دائرة نصف قطرها `r` يساوي `2r·sin(π/n)`، ومنه الحد الأدنى.
     */
    fun orbitRadiusDp(childCount: Int): Float {
        if (childCount <= 0) return 0f
        val base = 50f + min(childCount, 8) * 6f
        if (childCount < 3) return base
        val minBySeparation =
            (MIN_PLANET_SEPARATION_DP / 2f) / sin(PI / childCount).toFloat()
        return max(base, minBySeparation)
    }

    /**
     * نصف قطر الدائرة الحاضنة — جوهر الإصلاح.
     *
     * كل ابن عمودٌ عرضه [CHILD_COLUMN_HALF_WIDTH_DP]·2 وارتفاعه من أعلى
     * الكوكب حتى أسفل التسمية: `حجم الكوكب/2 + [LABEL_BLOCK_DP]` من جهة
     * الأسفل — وهذا الارتفاع مضمون فعلياً لأن واجهة الشاشة تثبته في صندوق
     * مقصوص (انظر ملاحظة [LABELS_BOX_DP]). أبعد ركن في هذا الصندوق عن مركز
     * الكوكب هو نصف قطره القطري، ومتباينة المثلث تجعل أبعد نقطة عن مركز
     * العنقود ≤ المدار + هذا النصف القطري — لأي زاوية كان عليها الابن.
     * يضاف هامش الأمان فيُحصر الجميع مهما كثر العدد.
     */
    fun confinementRadiusDp(childCount: Int, maxChildFileCount: Int): Float {
        if (childCount <= 0) return 0f
        val orbit = orbitRadiusDp(childCount)
        val childSize = childPlanetSizeDp(maxChildFileCount)
        val verticalHalf = childSize / 2f + LABEL_BLOCK_DP
        val columnHalfDiagonal = sqrt(
            CHILD_COLUMN_HALF_WIDTH_DP * CHILD_COLUMN_HALF_WIDTH_DP +
                verticalHalf * verticalHalf
        )
        return orbit + columnHalfDiagonal + CONFINEMENT_PADDING_DP
    }

    /**
     * نصف قطر الحلقة الواقية لعنقود بلا أبناء — بنفس قيم التصميم الأصلي
     * (نصف الكوكب + 14) بلا أي تغيير بصري؛ تجميعها هنا فقط لتوحيد المصدر.
     */
    fun guardianRingRadiusDp(parentFileCount: Int): Float =
        parentPlanetSizeDp(parentFileCount) / 2f + 14f

    /**
     * بصمة العنقود بوحدات dp المستقلة — القطر الكامل لمحيطه الحاضن
     * (أو الحلقة الواقية إن كان وحيداً) مضافاً إليه هامش الخلية.
     * تستعملها [computeGalaxyLayout] للتحجيم النسبي بين العناقيد.
     */
    fun footprintDp(
        childCount: Int,
        maxChildFileCount: Int,
        parentFileCount: Int
    ): Float {
        val radius = if (childCount <= 0) {
            guardianRingRadiusDp(parentFileCount)
        } else {
            confinementRadiusDp(childCount, maxChildFileCount)
        }
        return radius * 2f + OUTER_MARGIN_DP
    }
}
