package com.unihub.app.data.auth

/**
 * منطق صِرف (بلا Context/شبكة/DataStore) لتحديد من هو "مستخدم جديد" يستحق
 * إشعار المشرف، بالمقارنة مع آخر مجموعة معرّفات معروفة محفوظة محليًا.
 *
 * لماذا طبقة منفصلة عن [CloudAuthManager]: نفس نمط المشروع في `CloudAuthRules`،
 * `CloudFileRules`، `CloudPresenceMatcher` — منطق القرار قابل للاختبار وحده
 * بلا تشغيل الشبكة أو Android Context، والمدير (Manager) ينفّذ فقط.
 */
object NewUserWatchRules {

    data class Diff(
        /** المستخدمون الجدد الذين يستحقّون إشعارًا الآن (لا يشملون المشرف الحالي نفسه). */
        val newUsers: List<CloudUserAccount>,
        /** المجموعة الكاملة التي يجب حفظها بعد هذا الفحص ليُبنى عليها الفحص التالي. */
        val updatedKnownIds: Set<String>
    )

    /**
     * أول فحص على الإطلاق (`knownIds` فارغة) هو خط أساس فقط: يسجّل كل من هو
     * موجود حاليًا كـ"معروف" بلا أي إشعار — فلا يُفاجَأ المشرف بإشعارات عن
     * مستخدمين كانوا موجودين من قبل. الإشعار الحقيقي يبدأ من الفحص الذي يليه:
     * أي معرّف ظهر في `users` ولم يكن ضمن `knownIds` السابقة (وليس المشرف نفسه).
     */
    fun diff(knownIds: Set<String>, users: List<CloudUserAccount>, selfUserId: String): Diff {
        val currentIds = users.mapNotNullTo(mutableSetOf()) { it.userId.takeIf(String::isNotBlank) }
        if (knownIds.isEmpty()) return Diff(emptyList(), currentIds)
        val newcomers = users.filter { it.userId.isNotBlank() && it.userId != selfUserId && it.userId !in knownIds }
        return Diff(newcomers, knownIds + currentIds)
    }
}
