package com.unihub.app.data.local

import androidx.room.TypeConverter
import com.unihub.app.data.local.entity.ExamType
import com.unihub.app.data.local.entity.FileKind
import com.unihub.app.data.local.entity.TaskPriority
import com.unihub.app.data.local.entity.Weekday

/**
 * محوّلات Room للأنواع القوية. القراءة متسامحة: أي قيمة قديمة/تالفة
 * تعود إلى قيمة افتراضية آمنة بدل كراش عند فتح القاعدة.
 */
class Converters {

    @TypeConverter
    fun priorityToStorage(value: TaskPriority): String = value.name

    @TypeConverter
    fun priorityFromStorage(value: String?): TaskPriority = TaskPriority.fromStorage(value)

    @TypeConverter
    fun examTypeToStorage(value: ExamType): String = value.name

    @TypeConverter
    fun examTypeFromStorage(value: String?): ExamType = ExamType.fromStorage(value)

    @TypeConverter
    fun weekdayToStorage(value: Weekday): String = value.name

    @TypeConverter
    fun weekdayFromStorage(value: String?): Weekday = Weekday.fromStorage(value)

    @TypeConverter
    fun fileKindToStorage(value: FileKind): String = value.name

    @TypeConverter
    fun fileKindFromStorage(value: String?): FileKind =
        FileKind.entries.firstOrNull { it.name == value } ?: FileKind.OTHER
}
