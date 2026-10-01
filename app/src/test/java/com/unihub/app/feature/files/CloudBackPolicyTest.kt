package com.unihub.app.feature.files

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * اختبارات محلية لسلّم زر الرجوع (جولة تعليمات.md — المشكلة الأولى):
 * التشغيل: ./gradlew :app:testDebugUnitTest --tests "com.unihub.app.feature.files.CloudBackPolicyTest"
 */
class CloudBackPolicyTest {

    @Test fun selectionIsClearedBeforeNavigation() {
        assertEquals(BackStep.CLEARED_SELECTION, CloudBackPolicy.step(hasSelection = true, isInsideFolder = true))
        assertEquals(BackStep.CLEARED_SELECTION, CloudBackPolicy.step(hasSelection = true, isInsideFolder = false))
    }

    @Test fun insideFolderGoesUpWhenNoSelection() {
        assertEquals(BackStep.WENT_UP, CloudBackPolicy.step(hasSelection = false, isInsideFolder = true))
    }

    @Test fun rootWithoutSelectionExitsScreen() {
        assertEquals(BackStep.EXITED, CloudBackPolicy.step(hasSelection = false, isInsideFolder = false))
    }

    @Test fun rapidPressSequenceFollowsTheLadderWithoutSkippingLevels() {
        // محاكاة سيناريو الضغط السريع المتتالي: تحديد داخل مجلد، ثم 4 ضغطات رجوع
        var hasSelection = true
        var insideFolder = true
        val steps = mutableListOf<BackStep>()
        repeat(4) {
            val step = CloudBackPolicy.step(hasSelection, insideFolder)
            steps += step
            when (step) {
                BackStep.CLEARED_SELECTION -> hasSelection = false
                BackStep.WENT_UP -> insideFolder = false // صعدنا إلى الجذر في هذه المحاكاة
                BackStep.EXITED -> Unit
            }
        }
        assertEquals(
            listOf(BackStep.CLEARED_SELECTION, BackStep.WENT_UP, BackStep.EXITED, BackStep.EXITED),
            steps
        )
    }
}
