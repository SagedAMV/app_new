package com.unihub.app.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * أزرار إشعار النقل في الخلفية: «إيقاف مؤقت» يُبقي كل ما لم يبدأ في الطابور للعودة إليه،
 * و«استئناف» يعيد الجدولة، و«إلغاء» يزيل ما لم يبدأ فقط — ولا يمسّ ما اكتمل ولا الاستثناءات
 * الفاشلة (تبقى مرئية قابلة لإعادة المحاولة).
 *
 * يُستعمل [EntryPointAccessors] بدل ‏@AndroidEntryPoint لأن البثّ القادم من الإشعار لا يمر
 * بـ Hilt تلقائيًا؛ والحالة كلها في [CloudTransferQueueStore] وحده فلا حقيقة مزدوجة.
 */
class CloudTransferActionsReceiver : BroadcastReceiver() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface CloudTransferEntryPoint {
        val controls: CloudTransferControls
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != ACTION_PAUSE && action != ACTION_RESUME && action != ACTION_CANCEL) return
        val controls = EntryPointAccessors.fromApplication(context.applicationContext, CloudTransferEntryPoint::class.java).controls
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                when (action) {
                    // لا نكتفي برفع راية: نفس أزرار الواجهة تمامًا، وإلا كان الإشعار طريقًا منقوصًا
                    ACTION_PAUSE -> controls.pause()
                    ACTION_RESUME -> controls.resume()
                    else -> controls.cancelQueued()
                }
            } catch (_: Exception) {
                // زر في إشعار لا يجوز أن يُسقط العملية التي ينفذها النظام؛ الحالة تُقرأ من الطابور عند الجولة التالية
            } finally {
                withContext(NonCancellable) { pending.finish() }
            }
        }
    }

    companion object {
        const val ACTION_PAUSE = "com.unihub.app.action.CLOUD_TRANSFER_PAUSE"
        const val ACTION_RESUME = "com.unihub.app.action.CLOUD_TRANSFER_RESUME"
        const val ACTION_CANCEL = "com.unihub.app.action.CLOUD_TRANSFER_CANCEL"
    }
}
