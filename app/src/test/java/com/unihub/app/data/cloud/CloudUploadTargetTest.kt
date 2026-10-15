package com.unihub.app.data.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * اختبارات سياسة وجهة الرفع (JVM خالص).
 *
 * تغطي: لا اختيار = السلوك القديم حرفيًا (حتى لا ينكسر من كان يرفع إلى الجذر)، وقبول مفاتيح
 * البيان/المسار فقط، ورفض مجلد اختفى برسالة إنسانية، وقاعدة الفرد مقابل حفظ الشجرة، و—
 * الأهم — أن السطر المعروض للمستخدم لا يسرّب المفتاح الداخلي (`folder:…`).
 */
class CloudUploadTargetTest {

    @Test fun emptyOrBlankTargetMeansRootAndKeepsTheOldBehaviour() {
        assertNull(CloudUploadTarget.normalize(null))
        assertNull(CloudUploadTarget.normalize(""))
        assertNull(CloudUploadTarget.normalize("   "))
        assertNull(CloudUploadTarget.normalize("\n\t "))
        assertFalse(CloudUploadTarget.flattens(null, emptySet()))
        assertEquals("مجلد أب محلي", CloudUploadTarget.parentFor(treeRoot = true, target = null, derivedParent = "مجلد أب محلي"))
        assertEquals("إلى جذر السحابة مع بنية المجلدات", CloudUploadTarget.labelFor(null, null, false))
    }

    @Test fun targetIsTrimmedSoPaddedKeysDoNotSplitIdentity() {
        assertEquals("folder:abc", CloudUploadTarget.normalize("  folder:abc  "))
        // مفتاح بمسافات حول الاسم نفسه: وجهتان مختلفان بصيغة واحدة = خطأ مطابقة في dedupe
        assertEquals(CloudUploadTarget.normalize("folder:a"), CloudUploadTarget.normalize(" folder:a "))
    }

    @Test fun onlyManifestAndPathFoldersAreSelectable() {
        assertTrue(CloudUploadTarget.isSelectable(null))
        assertTrue(CloudUploadTarget.isSelectable("folder:uuid"))
        assertTrue(CloudUploadTarget.isSelectable("path:دراسي/صور"))
        assertFalse("legacy هوية مكتبة محلية لا وجهة سحابية", CloudUploadTarget.isSelectable("legacy:/sdcard/Download"))
        assertFalse(CloudUploadTarget.isSelectable("bucket/x"))
        assertFalse(CloudUploadTarget.isSelectable("folder:"))
    }

    @Test fun unknownFolderIsRefusedWithAHumanReason() {
        assertNull(CloudUploadTarget.validate(null, emptySet()))
        assertNull(CloudUploadTarget.validate("folder:a", setOf("folder:a", "folder:b")))
        val gone = CloudUploadTarget.validate("folder:deleted", setOf("folder:a"))!!
        assertTrue(gone, gone.contains("لم يعد") && gone.contains("أعد الفحص"))
        assertTrue("رسالة الرفض يجب أن تكون كلامًا: $gone", CloudFailureMessages.isUserFacing(gone))
        val wrongKind = CloudUploadTarget.validate("legacy:/x", setOf("folder:a"))!!
        assertTrue(wrongKind, wrongKind.contains("اختر مجلدًا"))
        assertTrue(CloudFailureMessages.isUserFacing(wrongKind))
    }

    @Test fun filesAreFlattenedIntoTargetFoldersKeepTheirTree() {
        assertTrue(CloudUploadTarget.flattens("folder:t", emptySet()))
        assertFalse("مجلدات مختارة ⇒ لا نهرس تنظيم المستخدم", CloudUploadTarget.flattens("folder:t", setOf(1L, 2L)))
        assertFalse(CloudUploadTarget.flattens(null, emptySet()))
    }

    @Test fun treeRootAdoptsTheTargetAsItsParentAndDepthIsUntouched() {
        assertEquals("folder:t", CloudUploadTarget.parentFor(treeRoot = true, target = "folder:t", derivedParent = "folder:local"))
        assertEquals("folder:local", CloudUploadTarget.parentFor(treeRoot = false, target = "folder:t", derivedParent = "folder:local"))
        assertEquals("folder:t", CloudUploadTarget.parentFor(treeRoot = true, target = "folder:t", derivedParent = null))
        assertNull(CloudUploadTarget.parentFor(treeRoot = false, target = "folder:t", derivedParent = null))
    }

    @Test fun labelNamesTheDestinationWithoutLeakingTheKey() {
        val flat = CloudUploadTarget.labelFor("folder:8f2c", "الصور", flatten = true)
        assertTrue(flat, flat.contains("الصور") && flat.contains("مباشرة"))
        val tree = CloudUploadTarget.labelFor("folder:8f2c", "الصور", flatten = false)
        assertTrue(tree, tree.contains("بنية المجلدات"))
        val stale = CloudUploadTarget.labelFor("folder:8f2c", null, flatten = true)
        assertTrue(stale, stale.contains("لم يعد في القائمة"))
        // I: المفتاح الداخلي لا يظهر أبدًا في نص المستخدم (طبيعة تطبيق.md §5)
        listOf(flat, tree, stale, CloudUploadTarget.labelFor(null, null, false)).forEach {
            assertFalse("تسريب مفتاح: $it", it.contains("folder:") || it.contains("path:") || it.contains("8f2c"))
            assertTrue("نص بلا عربية أو بتقنية: $it", CloudFailureMessages.isUserFacing(it))
        }
    }

    @Test fun sameChoiceAlwaysProducesTheSameLabel() {
        // M: determinism — البطاقة والإشعار يبنيان السطر من نفس الدخل في لحظتين مختلفتين
        val a = CloudUploadTarget.labelFor("folder:x", "دراسي", CloudUploadTarget.flattens("folder:x", emptySet()))
        val b = CloudUploadTarget.labelFor("folder:x", "دراسي", CloudUploadTarget.flattens("folder:x", emptySet()))
        assertEquals(a, b)
    }
}
