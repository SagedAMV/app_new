package com.unihub.app.data.cloud

/**
 * يقرر هل فحص جديد للسحابة ([after]) يحمل تغييراً فعلياً مقارنةً بالفحص السابق ([before])،
 * ليقرر [CloudPulseIntervalPolicy] هل يبقى في فاصل سريع (نافذة نشاط) أو يتباعد.
 *
 * مقصود أن يكون عدد/حالة فقط (لا توقيت [CloudScanState.checkedAt]) هو معيار "التغيّر"؛
 * فمجرد مرور الوقت بين فحصين ليس تغييراً، وإلا لن يتباعد الفاصل أبداً (كل فحص "يتغيّر").
 */
object CloudScanChangeDetector {
    fun hasChanged(before: CloudScanState, after: CloudScanState): Boolean {
        return before.phase != after.phase ||
            before.serverFileCount != after.serverFileCount ||
            before.serverFolderCount != after.serverFolderCount ||
            before.message != after.message
    }
}
