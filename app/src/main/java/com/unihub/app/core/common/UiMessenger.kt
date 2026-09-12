package com.unihub.app.core.common

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * قناة رسائل واجهة خفيفة: كل ViewModel يملك نسخة، والشاشة تجمعها داخل [UiMessengerHost]
 * وتعرضها في Snackbar. بديل أبسط وأكثر تماسكاً من نمط
 * "errorMessage + successMessage" المنفصلين في التطبيق المرجعي.
 */
class UiMessenger {

    private val _messages = MutableSharedFlow<String>(
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    val messages: Flow<String> = _messages

    fun notify(message: String) {
        _messages.tryEmit(message)
    }

    fun notifyError(message: String) {
        _messages.tryEmit(message)
    }
}
