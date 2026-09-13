package com.unihub.app.core.common

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * رسالة واجهة تحمل درجة خطورة: النجاح/المعلومة تختلف عن الخطأ في المعالجة
 * (الخطأ يُعرض مع زر إغلاق ليبقى ظاهراً حتى ينتبه المستخدم، والعادي يختفي وحده).
 */
data class UiMessage(
    val text: String,
    val isError: Boolean = false
)

/**
 * قناة رسائل واجهة خفيفة: كل ViewModel يملك نسخة، والشاشة تجمعها داخل [UiMessagesHost]
 * وتعرضها في Snackbar. بديل أبسط وأكثر تماسكاً من نمط
 * "errorMessage + successMessage" المنفصلين في التطبيق المرجعي.
 */
class UiMessenger {

    private val _messages = MutableSharedFlow<UiMessage>(
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    val messages: Flow<UiMessage> = _messages

    fun notify(message: String) {
        _messages.tryEmit(UiMessage(message))
    }

    fun notifyError(message: String) {
        _messages.tryEmit(UiMessage(message, isError = true))
    }
}
