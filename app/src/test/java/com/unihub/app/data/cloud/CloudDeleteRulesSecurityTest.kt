package com.unihub.app.data.cloud

import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * حارس الحذف الأخير: [CloudDeleteRules.isDeletableObjectKey] يجب أن يرفض أي مفتاح
 * يحاول الهروب من مجلد المستخدم، حتى لو وصل من واجهة الملفات أو من استجابة سحابية
 * معدَّلة. هذه التأكيدات مستمدة مباشرة من شروط
 * [com.unihub.app.data.appwrite.AppwriteFileProtocol.isSafeObjectKey]:
 *  - أي جزء مسار يساوي "." أو ".." أو فارغاً يُرفض.
 *  - الشرطة المائلة العكسية ومحارف التحكم تُرفض.
 *  - المفتاح الذي يبدأ بـ "/" يُرفض.
 */
class CloudDeleteRulesSecurityTest {

    @Test fun deletionRulesRejectParentDirectoryEscapes() {
        assertFalse(CloudDeleteRules.isDeletableObjectKey("../unihub_auth_registry.json"))
        assertFalse(CloudDeleteRules.isDeletableObjectKey("files/../../unihub_auth_registry.json"))
        assertFalse(CloudDeleteRules.isDeletableObjectKey("files/./lecture.pdf"))
        assertFalse(CloudDeleteRules.isDeletableObjectKey("/files/0123.pdf"))
    }

    @Test fun deletionRulesRejectControlCharactersAndBackslashes() {
        assertFalse(CloudDeleteRules.isDeletableObjectKey("files\\private.pdf"))
        assertFalse(CloudDeleteRules.isDeletableObjectKey("files/evil\nkey"))
        assertFalse(CloudDeleteRules.isDeletableObjectKey(""))
    }
}
