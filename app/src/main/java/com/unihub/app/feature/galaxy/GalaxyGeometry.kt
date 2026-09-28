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
 * التحليل الجذري بيّن أن نصف قطر الدائرة الحاضنة كان يحسب حساب قرص الكوكب
 * فقط (المدار + نصف قطر الكوكب + 8) بينما كل ابن يُرسم عموداً كاملاً:
 * كوكب + مباعدة 4 + سطران من النص يبلغان نحو 36 إضافية تحت الكوكب — فكان
 * النص (وأحياناً أطراف العمود) يخترق الدائرة، ويظهر الخرق واضحاً كلما كثر
 * الأبناء لأن أبناء النصف السفلي من المدار تتدلى تسمياتهم نحو الخارج.
 *
 * الحل هنا: حدّ رياضي مضمون لأي زاوية — متباينة المثلث تعطي أن أبعد نقطة
 * في عمود الابن عن مركز العنقود لا تتجاوز: نصف قطر المدار + نصف قطر عمود
 * الابن (قطرياً) + هامش أمان. وبذلك تُحصر العائلة كاملة داخل دائرتها مهما
 * كان عدد الأبناء ومواقعهم على المدار.
 *
 * كما يُصلح هنا تناقضٌ قديم: مدار الأبناء كان يُحدّ بـ [min(count, 8) * 6]
 * مهما كثر العدد بينما التعليق يعد «بلا تداخل مهما كثر عددها» — الآن يُضاف
 * حدّ أدنى هندسي يضمن مسافة لا تقل عن [MIN_PLANET_SEPARATION_DP] بين أي
 * كوكبين متجاورين على المدار.
 *
 * كل الدوال نقية بلا أي اعتماد على أندرويد — قابلة للاختبار على JVM مباشرة.
 */
internal object GalaxyGeometry {

    /** نصف عرض عمود الكوكب الابن (العمود 78dp وعرضه مثبّت في واجهة الشاشة) */
    const val CHILD_COLUMN_HALF_WIDTH_DP = 39f

    /**
     * ارتفاع كتلة التسمية المتدلية تحت الكوكب: مباعدة 4dp + سطران من نص
     * [androidx.compose.material3.MaterialTheme.typography.labelSmall].
     * قيمة تحفظية تغطي الأسطر الفعلية حتى لا يخرج أي نص عن الدائرة.
     */
    const val LABEL_BLOCK_DP = 36f

    /** هامش أمان بين أبعد نقطة في عمود الابن ومحيط الدائرة الحاضنة */
    const val CONFINEMENT_PADDING_DP = 6f

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
     * الأسفل. أبعد ركن في هذا الصندوق عن مركز الكوكب هو نصف قطره القطري،
     * ومتباينة المثلث تجعل أبعد نقطة عن مركز العنقود ≤ المدار + هذا النصف
     * القطري — لأي زاوية كان عليها الابن. يضاف هامش الأمان فيُحصر الجميع.
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
