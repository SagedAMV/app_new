package com.unihub.app.feature.galaxy

import kotlin.math.PI
import kotlin.math.cos
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

    /**
     * نصف عرض عمود الكوكب الأم (العمود 112dp).
     *
     * (إصلاح هذه الجلسة) كانت القيمتان 56 و112 مكتوبتين رقمين سحريّين داخل
     * [FolderClusterView] في GalaxyScreen بينما عمود الابن يشتق عرضه من ثابت
     * هنا — ازدواج مصدر الحقيقة نفسه الذي وثّقت جلسة سابقة أنها عالجته
     * للأبناء فقط ونسيت الأم. توحيدها هنا يمنع أي انحراف صامت مستقبلاً،
     * ويجعل حساب بصمة العنقود قادراً على حصر عمود الأم أيضاً.
     */
    const val PARENT_COLUMN_HALF_WIDTH_DP = 56f

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

    /**
     * عدد المقاعد الثابتة في كل حلقة حول مجلد الأب.
     *
     * الفكرة الجديدة: المجرة ليست مداراً عشوائياً؛ هي شبكة علاقات لها
     * أماكن محفوظة. كل ستة أبناء يحصلون على حلقة مستقلة، لذلك لا تتكدس
     * المجلدات عندما يكبر العدد.
     */
    const val CHILDREN_PER_RING = 6

    /** المسافة بين مركز الأب ومقاعد الحلقة الأولى. */
    const val FIRST_RING_RADIUS_DP = 82f

    /** المسافة الإضافية بين الحلقات المتعاقبة. */
    const val RING_STEP_DP = 68f

    /** الحد الأدنى للمسافة بين العقد على الحلقة نفسها. */
    const val MIN_PLANET_SEPARATION_DP = 34f

    /** قطر كوكب الأم: يكبر مع عدد الملفات بحد أعلى (30..48) */
    fun parentPlanetSizeDp(fileCount: Int): Float = 30f + min(fileCount * 2f, 18f)

    /** قطر كوكب الابن: يكبر مع عدد ملفاته بحد أعلى (20..30) */
    fun childPlanetSizeDp(fileCount: Int): Float = 20f + min(fileCount * 2f, 10f)

    /** رقم الحلقة التي ينتمي إليها الابن. */
    fun childRing(index: Int): Int = index / CHILDREN_PER_RING

    /** موضع الابن داخل حلقته، وليس في مدار واحد مزدحم. */
    fun childSlot(index: Int): Int = index % CHILDREN_PER_RING

    /** عدد المقاعد الفعلية في حلقة معينة. */
    fun ringChildCount(childCount: Int, ring: Int): Int =
        (childCount - ring * CHILDREN_PER_RING).coerceIn(0, CHILDREN_PER_RING)

    /** نصف قطر الحلقة التي يوجد فيها الابن. */
    fun childRingRadiusDp(index: Int): Float =
        FIRST_RING_RADIUS_DP + childRing(index) * RING_STEP_DP

    /**
     * نصف قطر الحلقات. هذا ليس مداراً دواراً؛ هو إطار مكاني ثابت لتوزيع
     * العقد على مقاعد واضحة.
     */
    fun orbitRadiusDp(childCount: Int): Float {
        if (childCount <= 0) return 0f
        val lastRing = (childCount - 1) / CHILDREN_PER_RING
        return childRingRadiusDp(lastRing)
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
    fun confinementRadiusDp(
        childCount: Int,
        maxChildFileCount: Int,
        parentFileCount: Int
    ): Float {
        if (childCount <= 0) return 0f
        val orbit = orbitRadiusDp(childCount)
        val childSize = childPlanetSizeDp(maxChildFileCount)
        val verticalHalf = childSize / 2f + LABEL_BLOCK_DP
        val columnHalfDiagonal = sqrt(
            CHILD_COLUMN_HALF_WIDTH_DP * CHILD_COLUMN_HALF_WIDTH_DP +
                verticalHalf * verticalHalf
        )
        // الأم نفسها عمودٌ أعرض من عمود الابن (112 مقابل 78): لو كان المدار
        // قصيراً (ابن أو ابنان) فمدى عمود الأم قد يفوق مدى الأبناء، فتخرج
        // تسمية الأم خارج دائرتها. الحصر يأخذ الأكبر من المدَيَين.
        return max(orbit + columnHalfDiagonal, parentColumnReachDp(parentFileCount)) +
            CONFINEMENT_PADDING_DP
    }

    /**
     * أبعد نقطة في عمود كوكب الأم عن مركز قرصه (نصف القطر القطري للعمود):
     * أفقياً [PARENT_COLUMN_HALF_WIDTH_DP]، ورأسياً نصف القرص + كتلة التسمية.
     *
     * (إصلاح هذه الجلسة) لم تكن هذه المسافة تدخل في أي حساب إطلاقاً، فكانت
     * بصمة المجلد بلا أبناء تُحسب من الحلقة الواقية وحدها — وهي أصغر من عمود
     * الأم وتسميته، فتتداخل تسميات المجلدات المتجاورة في الشبكة.
     */
    fun parentColumnReachDp(parentFileCount: Int): Float {
        val verticalHalf = parentPlanetSizeDp(parentFileCount) / 2f + LABEL_BLOCK_DP
        return sqrt(
            PARENT_COLUMN_HALF_WIDTH_DP * PARENT_COLUMN_HALF_WIDTH_DP +
                verticalHalf * verticalHalf
        )
    }

    // ───────────────── تموضع محايد لاتجاه التخطيط (RTL/LTR) ─────────────────
    //
    // (السبب الجذري الذي تعالجه هذه الدوال) طبقة الرسم (Canvas) تستعمل
    // إحداثيات مطلقة لا تُعكس أبداً، بينما طبقة التخطيط كانت تخلط لغتين:
    // نقطة أصل حسّاسة للاتجاه (Alignment.TopStart وهي BiasAlignment(-1,-1)
    // تنقلب إلى أعلى-اليمين في RTL) مع إزاحة مطلقة (absoluteOffset لا
    // تنقلب). على جهاز عربي ينزاح الكوكب عن مركز دائرته بمقدار
    // (بصمة العنقود − عرض العمود) — وهذا هو عطل «الكواكب ليست في منتصف
    // دائرتها» حرفياً.
    //
    // العلاج: تُحسب كل المواضع نسبةً إلى **مركز** الحاوية. المركز
    // (Alignment.Center) انحيازه صفر، وصفرٌ لا ينقلب بالنفي، فيصير محايداً
    // رياضياً لأي اتجاه؛ ومعه absoluteOffset تصبح الطبقتان بلغة إحداثية
    // واحدة. ميزة إضافية: الناتج لا يعتمد على بصمة العنقود إطلاقاً.

    /**
     * الإزاحة الرأسية لعمود كوكب متمركز، كي يقع **مركز قرصه** (لا مركز
     * العمود بتسميته) عند الإزاحة [dyFromCenterDp] عن مركز الحاوية.
     *
     * الاشتقاق: ارتفاع العمود h = قطر الكوكب + [LABEL_BLOCK_DP]. التمركز
     * يضع أعلاه عند (H−h)/2 فيقع مركز القرص عند (H−h)/2 + قطر/2؛ والمطلوب
     * H/2 + dy، والفرق = dy + (h − قطر)/2 = dy + [LABEL_BLOCK_DP]/2.
     * لاحظ أن قطر الكوكب وارتفاع الحاوية H حُذفا معاً — النتيجة ثابتة.
     */
    fun columnOffsetYDp(dyFromCenterDp: Float): Float = dyFromCenterDp + LABEL_BLOCK_DP / 2f

    /**
     * نصف قطر الحلقة الواقية لعنقود بلا أبناء — بنفس قيم التصميم الأصلي
     * (نصف الكوكب + 14) بلا أي تغيير بصري؛ تجميعها هنا فقط لتوحيد المصدر.
     *
     * (إصلاح جلسة التحقق العميق) كانت هذه الدالة قد حُذفت بالخطأ في commit
     * إعادة تصميم المجرة 443d3d6 ضمن تفكيك الدوال الميتة، مع أن GalaxyScreen
     * لا يزال يستدعيها لرسم الحلقة الواقية للعنقود الوحيد — ففشل البناء.
     */
    fun guardianRingRadiusDp(parentFileCount: Int): Float =
        parentPlanetSizeDp(parentFileCount) / 2f + 14f

    /**
     * بصمة العنقود بوحدات dp المستقلة — القطر الكامل لمحيطه الحاضن
     * (أو الحلقة الواقية إن كان وحيداً) مضافاً إليه هامش الخلية.
     * تستعملها [computeGalaxyLayout] للتحجيم النسبي بين العناقيد.
     *
     * (إصلاح جلسة التحقق العميق) حُذفت بالخطأ في commit 443d3d6 مع أن
     * clusterFootprint في GalaxyLayoutEngine يستدعيها — ففشل البناء.
     */
    fun footprintDp(
        childCount: Int,
        maxChildFileCount: Int,
        parentFileCount: Int
    ): Float {
        val radius = if (childCount <= 0) {
            // الحلقة الواقية تبقى كما هي بصرياً (لا تغيير في الرسم)، لكن
            // البصمة — وهي ما يحجز مساحة الخلية — يجب أن تسع عمود الأم
            // وتسميته أيضاً، وإلا تداخلت التسميات بين المجلدات المتجاورة.
            max(guardianRingRadiusDp(parentFileCount), parentColumnReachDp(parentFileCount))
        } else {
            confinementRadiusDp(childCount, maxChildFileCount, parentFileCount)
        }
        return radius * 2f + OUTER_MARGIN_DP
    }

    /**
     * زاوية المقعد داخل الحلقة مع إضافة إزاحة الدوران (Orbit Rotation).
     * هذا هو التغيير الجذري لجعل المجلدات تدور حول الشمس بسرعات متفاوتة.
     * [orbitAngleOffset]: زاوية الدوران الحالية للمدار (تُمرر من Animation في الشاشة).
     */
    fun childAngleRadWithOrbit(index: Int, childCount: Int, orbitAngleOffset: Double): Double {
        val ring = childRing(index)
        val slot = childSlot(index)
        val slots = ringChildCount(childCount, ring).coerceAtLeast(1)
        val ringOffset = if (ring % 2 == 0) 0.0 else PI / 6.0
        // الزاوية الأصلية + إزاحة الدوران
        return -PI / 2 + ringOffset + slot * (2.0 * PI / slots) + orbitAngleOffset
    }

    /** إزاحة مركز قرص الابن أفقياً عن مركز العنقود مع دوران المدار. */
    fun childCenterDxDpWithOrbit(index: Int, childCount: Int, orbitAngleOffset: Double): Float =
        childRingRadiusDp(index) * cos(childAngleRadWithOrbit(index, childCount, orbitAngleOffset)).toFloat()

    /** إزاحة مركز قرص الابن رأسياً عن مركز العنقود مع دوران المدار. */
    fun childCenterDyDpWithOrbit(index: Int, childCount: Int, orbitAngleOffset: Double): Float =
        childRingRadiusDp(index) * sin(childAngleRadWithOrbit(index, childCount, orbitAngleOffset)).toFloat()
}
