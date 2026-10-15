package com.unihub.app.data.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * اختبارات منطق طابور النقل في الخلفية (JVM خالص، بلا Android وبلا شبكة): تغطي
 * S1/S3/S4/S8/S9/S10 من مصفوفة السيناريوهات، وInvariants 1..4 وMetamorphic MR1/MR3.
 * تُختبر طبقة المنطق وحدها عمدًا — لأن تنفيذ الشبكة والواجهة لا يُختبران محليًا.
 */
class CloudTransferQueueLogicTest {

    private fun upload(id: String, vararg fileIds: Long, at: Long = 0L) = CloudTransferBatch(
        id = id,
        kind = CloudTransferKind.UPLOAD,
        title = "رفع المحدد",
        fileIds = fileIds.toSet(),
        createdAt = at
    )

    private fun download(id: String, vararg keys: String, at: Long = 0L, destination: CloudDownloadDestination = CloudDownloadDestination()) = CloudTransferBatch(
        id = id,
        kind = CloudTransferKind.DOWNLOAD,
        title = "تنزيل المحدد",
        remoteKeys = keys.toList(),
        destination = destination,
        createdAt = at
    )

    // ── S1 / S3: الجدولة والانتظار بالطلبات ────────────────────────────────────────
    @Test
    fun enqueueKeepsFifoOrderAndCountsItems() {
        val queue = CloudTransferQueueLogic.enqueue(
            CloudTransferQueueLogic.enqueue(CloudTransferQueueSnapshot(), upload("a", 1, 2, 3), now = 100L),
            upload("b", 9),
            now = 50L
        )
        assertEquals(listOf("b", "a"), queue.pending.map { it.id })
        assertEquals(4, queue.pendingItems)
        assertEquals("بانتظار 2 عملية (4 عنصر)", queue.summary)
    }

    @Test
    fun nextPendingTakesOldestFirst() {
        var snapshot = CloudTransferQueueSnapshot()
        snapshot = CloudTransferQueueLogic.enqueue(snapshot, upload("second", 2), now = 20L)
        snapshot = CloudTransferQueueLogic.enqueue(snapshot, upload("first", 1), now = 10L)
        assertEquals("first", CloudTransferQueueLogic.nextPending(snapshot)?.id)
    }

    // ── S9 / MR-1: تكرار نفس الفعل وهو معلّق لا يُنشئ طابورًا مكررًا ────────────────
    @Test
    fun duplicatePendingBatchIsIgnored() {
        val empty = CloudTransferQueueLogic.enqueue(CloudTransferQueueSnapshot(), upload("a", 1, 2), now = 1L)
        val again = CloudTransferQueueLogic.enqueue(empty, upload("a-dup", 2, 1), now = 2L)
        assertEquals(1, again.batches.size)
        assertEquals(listOf("a"), again.pending.map { it.id })
    }

    @Test
    fun finishedBatchDoesNotBlockNewSameRequest() {
        var snapshot = CloudTransferQueueLogic.enqueue(CloudTransferQueueSnapshot(), upload("a", 1), now = 1L)
        snapshot = CloudTransferQueueLogic.markDone(snapshot, "a")
        snapshot = CloudTransferQueueLogic.enqueue(snapshot, upload("b", 1), now = 2L)
        // prune يُقدّم النشطة على المنتهية: الطابور المعروض يبدأ بما سيُنفَّذ
        assertEquals(listOf("b", "a"), snapshot.batches.map { it.id })
        assertEquals(listOf("b"), snapshot.pending.map { it.id })
    }

    @Test
    fun uselessBatchIsRejected() {
        val emptyUpload = CloudTransferQueueLogic.enqueue(CloudTransferQueueSnapshot(), upload("a"), now = 1L)
        assertTrue("رفع بلا ملفات لا يجوز أن يبقى معلقًا", emptyUpload.isEmpty)
        val noneKind = CloudTransferQueueLogic.enqueue(
            CloudTransferQueueSnapshot(),
            CloudTransferBatch(id = "x", kind = CloudTransferKind.NONE, title = "—"),
            now = 1L
        )
        assertTrue(noneKind.isEmpty)
    }

    // ── إيقاف مؤقت / استئناف (قرار wm_foreground) ──────────────────────────────────
    @Test
    fun pauseHidesNextPendingAndResumeRestoresItLosslessly() {
        var snapshot = CloudTransferQueueLogic.enqueue(CloudTransferQueueSnapshot(), upload("a", 1), now = 1L)
        snapshot = CloudTransferQueueLogic.enqueue(snapshot, upload("b", 2), now = 2L)
        val before = snapshot
        snapshot = CloudTransferQueueLogic.setPaused(snapshot, true)
        assertNull("الإيقاف المؤقت يمنع كل دفعة جديدة", CloudTransferQueueLogic.nextPending(snapshot))
        snapshot = CloudTransferQueueLogic.setPaused(snapshot, false)
        assertEquals("الإيقاف ثم الاستئناف لا يفقدان ولا يغيّران شيئًا", before.batches, snapshot.batches)
        assertEquals("a", CloudTransferQueueLogic.nextPending(snapshot)?.id)
    }

    @Test
    fun pausedSummarySaysSo() {
        val snapshot = CloudTransferQueueLogic.setPaused(CloudTransferQueueSnapshot(batches = listOf(upload("a", 1))), true)
        assertTrue(snapshot.summary, snapshot.summary.startsWith("موقوف مؤقتًا"))
    }

    // ── S8: الإلغاء يمسّ ما لم يبدأ فقط (Inv-3) ─────────────────────────────────────
    @Test
    fun cancelRemovesOnlyPendingAndKeepsDoneAndFailed() {
        var snapshot = CloudTransferQueueSnapshot(
            batches = listOf(
                upload("done", 1).copy(status = CloudTransferStatus.DONE),
                upload("wait", 2),
                upload("bad", 3).copy(status = CloudTransferStatus.FAILED, lastError = "تعذّر")
            )
        )
        snapshot = CloudTransferQueueLogic.cancelPending(snapshot)
        assertEquals(listOf("done", "bad"), snapshot.batches.map { it.id })
        assertTrue("ما اكتمال يبقى منجزًا", snapshot.batches.first().status == CloudTransferStatus.DONE)
        assertEquals(1, snapshot.failed.size)
    }

    // ── S4: الانقطاع والانشغال لا يستهلكان المحاولات (Inv-2) ────────────────────────
    @Test
    fun transientFailureKeepsBatchPendingWithoutBurningAttempts() {
        var snapshot = CloudTransferQueueLogic.enqueue(CloudTransferQueueSnapshot(), upload("a", 1), now = 1L)
        repeat(20) {
            snapshot = CloudTransferQueueLogic.markFailed(snapshot, "a", "لا يتوفر إنترنت", retryLater = true)
        }
        val batch = snapshot.pending.single()
        assertEquals(0, batch.attempts)
        assertEquals("لا يتوفر إنترنت", batch.lastError)
        assertTrue("التأجيل المؤقت لا يصنّف استثناءً", snapshot.failed.isEmpty())
    }

    @Test
    fun realFailuresBecomeExceptionOnlyAfterMaxAttempts() {
        var snapshot = CloudTransferQueueLogic.enqueue(CloudTransferQueueSnapshot(), upload("a", 1), now = 1L)
        repeat(CloudTransferQueueLogic.MAX_ATTEMPTS - 1) {
            snapshot = CloudTransferQueueLogic.markFailed(snapshot, "a", "رفض الخادم", retryLater = false)
            assertTrue("قبل الحد تبقى قابلة للتلقائى", snapshot.pending.isNotEmpty())
        }
        snapshot = CloudTransferQueueLogic.markFailed(snapshot, "a", "رفض الخادم", retryLater = false)
        assertEquals(listOf("a"), snapshot.failed.map { it.id })
        assertEquals(CloudTransferQueueLogic.MAX_ATTEMPTS, snapshot.failed.single().attempts)
        assertTrue(snapshot.summary.contains("تعذّر تنفيذ 1"))
    }

    @Test
    fun retryFailedResetsAttemptsAndRequeuesOnlyExceptions() {
        var snapshot = CloudTransferQueueSnapshot(
            batches = listOf(
                upload("bad", 1).copy(status = CloudTransferStatus.FAILED, attempts = 4, lastError = "خادم"),
                upload("ok", 2).copy(status = CloudTransferStatus.DONE)
            )
        )
        snapshot = CloudTransferQueueLogic.retryFailed(snapshot)
        assertEquals(listOf("bad"), snapshot.pending.map { it.id })
        assertEquals(0, snapshot.pending.single().attempts)
        // سبب العطل يبقى معروضًا («آخر عطل» في الإشعار) حتى تنجح المحاولة الجديدة
        assertEquals("خادم", snapshot.pending.single().lastError)
        assertEquals(listOf("ok"), snapshot.batches.filter { it.status == CloudTransferStatus.DONE }.map { it.id })
    }

    @Test
    fun doneBatchRecordsNoteAndIsNeverRequeuedSilently() {
        var snapshot = CloudTransferQueueLogic.enqueue(CloudTransferQueueSnapshot(), upload("a", 1), now = 1L)
        snapshot = CloudTransferQueueLogic.markDone(snapshot, "a", "رُفع 1 • لم يوجد 2 ملف على الجهاز")
        val done = snapshot.batches.single()
        assertEquals(CloudTransferStatus.DONE, done.status)
        assertEquals("رُفع 1 • لم يوجد 2 ملف على الجهاز", done.lastError)
        assertNull("لا شيء معلق بعد الاكتمال", CloudTransferQueueLogic.nextPending(snapshot))
    }

    @Test
    fun pruningKeepsAllPendingAndLastFinishedOnly() {
        var snapshot = CloudTransferQueueSnapshot()
        repeat(40) { index ->
            snapshot = CloudTransferQueueLogic.enqueue(snapshot, upload("id-$index", index.toLong()), now = index.toLong())
            snapshot = CloudTransferQueueLogic.markDone(snapshot, "id-$index")
        }
        snapshot = CloudTransferQueueLogic.enqueue(snapshot, upload("pending", 999), now = 50L)
        assertEquals("منتهية محدودة", 12, snapshot.batches.count { it.status == CloudTransferStatus.DONE })
        assertEquals(1, snapshot.pending.size)
        assertEquals("pending", snapshot.pending.single().id)
    }

    // ── S6: الوجهة مجمّدة داخل الدفعة ───────────────────────────────────────────────
    @Test
    fun destinationStaysFrozenInBatch() {
        val destination = CloudDownloadDestination(
            location = CloudDownloadLocation.LOCAL_FOLDER,
            localFolderId = 77L,
            localFolderCreatedAt = 1234L,
            rootFolderName = "محاضير"
        )
        val snapshot = CloudTransferQueueLogic.enqueue(CloudTransferQueueSnapshot(), download("d", "k1", destination = destination), now = 1L)
        assertEquals(destination, snapshot.batches.single().destination)
        assertEquals(1, snapshot.batches.single().itemCount)
    }

    @Test
    fun dedupeKeySeparatesDifferentDestinations() {
        val base = download("d", "k1")
        val other = base.copy(id = "d2", destination = CloudDownloadDestination(localFolderId = 5L))
        var snapshot = CloudTransferQueueLogic.enqueue(CloudTransferQueueSnapshot(), base, now = 1L)
        snapshot = CloudTransferQueueLogic.enqueue(snapshot, other, now = 2L)
        assertEquals("وجهة مختلفة = طلب مختلف", 2, snapshot.pending.size)
    }

    // ── S10: التخزين — JSON تالف أو ناقص لا يُسقط التطبيق ──────────────────────────
    @Test
    fun blankAndCorruptJsonDecodeToEmptyQueue() {
        assertTrue(decodeQueueJson(null).isEmpty)
        assertTrue(decodeQueueJson("").isEmpty)
        assertTrue(decodeQueueJson("   ").isEmpty)
        assertTrue("JSON تالف يعود بطابورًا فارغًا لا بانقطاع", decodeQueueJson("{ليس json").isEmpty)
        assertTrue(decodeQueueJson("""{"batches":[]}""").isEmpty)
        assertTrue(decodeQueueJson("""{"batches":{}}""").isEmpty)
    }

    @Test
    fun jsonRoundTripPreservesEveryField() {
        val snapshot = CloudTransferQueueSnapshot(
            paused = true,
            batches = listOf(
                CloudTransferBatch(
                    id = "u1",
                    kind = CloudTransferKind.UPLOAD,
                    title = "رفع مجلد الفيزياء ومحتوياته",
                    fileIds = setOf(3L, 1L),
                    folderIds = setOf(9L),
                    totalBytes = 4096L,
                    destination = CloudDownloadDestination(location = CloudDownloadLocation.FOLDER_INSIDE_LOCAL, localFolderId = 4L, localFolderCreatedAt = 8L, rootFolderName = "جديد"),
                    status = CloudTransferStatus.PENDING,
                    attempts = 2,
                    lastError = "انقطع الاتصال",
                    createdAt = 555L
                ),
                download("d1", "a/b.pdf", "a/c.pdf", at = 556L).copy(
                    status = CloudTransferStatus.FAILED,
                    remoteFolderKeys = setOf("folder:3"),
                    attempts = 4,
                    lastError = "تعذّر تنزيل 1: c.pdf"
                )
            )
        )
        val decoded = decodeQueueJson(encodeQueueJson(snapshot))
        assertEquals(snapshot.paused, decoded.paused)
        assertEquals(snapshot.batches, decoded.batches)
    }

    @Test
    fun malformedEntriesAreDroppedNotCrashed() {
        val json = """{"paused":false,"batches":[{"kind":"UPLOAD","title":"بلا معرّف"},{"id":"ok","kind":"UNKNOWN"},{"id":"ok2","kind":"UPLOAD","fileIds":[7,"8",9999999999]}]}"""
        val decoded = decodeQueueJson(json)
        assertEquals("تُسجَّل فقط الدفعات المعقولة", 1, decoded.batches.size)
        val batch = decoded.batches.single()
        assertEquals("ok2", batch.id)
        assertEquals(setOf(7L, 8L, 9999999999L), batch.fileIds)
        assertEquals(CloudTransferStatus.PENDING, batch.status)
        assertEquals(CloudDownloadDefaults.location, batch.destination.location)
    }

    // ── عطل «قيد الانتظار ولا يتغيّر أبدًا»: قرار إعادة التسليح (تقرير 2026-10-08) ──────
    private val withPending get() = CloudTransferQueueLogic.enqueue(CloudTransferQueueSnapshot(), upload("a", 1), now = 1L)

    @Test
    fun offlinePendingReArmsScheduledWorkInsteadOfRelyingOnIgnoredResult() {
        assertEquals(CloudTransferRearm.WAIT_FOR_NETWORK, CloudTransferQueueLogic.rearmAction(withPending, networkAvailable = false, workerStopped = false, busyEncountered = false))
    }

    @Test
    fun stoppedWorkerReArmsBecauseItsResultWouldBeDiscarded() {
        // هنا لبّ العطل: العامل أُوقف لسقوط شرط الشبكة، فأي Result.retry() يُهمَل
        assertEquals(CloudTransferRearm.WAIT_FOR_NETWORK, CloudTransferQueueLogic.rearmAction(withPending, networkAvailable = true, workerStopped = true, busyEncountered = false))
    }

    @Test
    fun busyQueueRetriesSoonInsteadOfWaitingOnNetwork() {
        assertEquals(CloudTransferRearm.RETRY_SOON, CloudTransferQueueLogic.rearmAction(withPending, networkAvailable = true, workerStopped = false, busyEncountered = true))
        assertEquals("وبلا انشغال أيضًا: ما زال هناك ما يُنفَّذ", CloudTransferRearm.RETRY_SOON,
            CloudTransferQueueLogic.rearmAction(withPending, networkAvailable = true, workerStopped = false, busyEncountered = false))
    }

    @Test
    fun pausedOrEmptyQueueNeedsNoFurtherWork() {
        val paused = CloudTransferQueueLogic.setPaused(withPending, true)
        assertEquals("الإيقاف المؤقت اختيار صريح من المستخدم، فلا إعادة تسليح", CloudTransferRearm.NONE,
            CloudTransferQueueLogic.rearmAction(paused, networkAvailable = true, workerStopped = false, busyEncountered = false))
        val finished = CloudTransferQueueLogic.markDone(withPending, "a")
        assertEquals(CloudTransferRearm.NONE, CloudTransferQueueLogic.rearmAction(finished, networkAvailable = false, workerStopped = true, busyEncountered = true))
        assertEquals(0, finished.pending.size)
    }

    @Test
    fun removedBatchDisappearsAndIsNotRevived() {
        var snapshot = CloudTransferQueueLogic.enqueue(CloudTransferQueueSnapshot(), upload("a", 1), now = 1L)
        snapshot = CloudTransferQueueLogic.enqueue(snapshot, upload("b", 2), now = 2L)
        snapshot = CloudTransferQueueLogic.removeBatch(snapshot, "a")
        assertEquals(listOf("b"), snapshot.batches.map { it.id })
        assertFalse(snapshot.batches.any { it.id == "a" })
    }

    // ─── إشعار النتيجة: ما ينتبه له المستخدم وهو خارج التطبيق (F10، I4) ───

    private fun failedUploadSnapshot(vararg ids: Long): CloudTransferQueueSnapshot {
        var snapshot = CloudTransferQueueSnapshot()
        snapshot = CloudTransferQueueLogic.enqueue(snapshot, upload("a", *ids), now = 1L)
        repeat(CloudTransferQueueLogic.MAX_ATTEMPTS) {
            snapshot = CloudTransferQueueLogic.markFailed(snapshot, "a", "تعذّر الوصول إلى خادم السحابة", retryLater = false)
        }
        return snapshot
    }

    @Test
    fun outcomeIsSilentOnPureSuccess() {
        var snapshot = CloudTransferQueueLogic.enqueue(CloudTransferQueueSnapshot(), upload("a", 1, 2), now = 1L)
        snapshot = CloudTransferQueueLogic.markDone(snapshot, "a")
        assertNull(CloudTransferQueueLogic.outcomeNotificationText(snapshot, completedItems = 2))
    }

    @Test
    fun outcomeNamesFailuresAndPointsToRetry() {
        val snapshot = failedUploadSnapshot(1, 2, 3)
        val text = CloudTransferQueueLogic.outcomeNotificationText(snapshot, completedItems = 0)!!
        assertTrue("يجب أن يذكر عدد ما تعذّر: $text", text.contains("3"))
        assertTrue(text.contains("إعادة المحاولة"))
        assertTrue("نص تقني في إشعار: $text", CloudFailureMessages.isUserFacing(text))
    }

    @Test
    fun outcomeCombinesCompletedAndFailedWithoutInventingTotals() {
        var snapshot = CloudTransferQueueLogic.enqueue(CloudTransferQueueSnapshot(), upload("a", 1, 2), now = 1L)
        snapshot = CloudTransferQueueLogic.enqueue(snapshot, upload("b", 3), now = 2L)
        snapshot = CloudTransferQueueLogic.markDone(snapshot, "a")
        repeat(CloudTransferQueueLogic.MAX_ATTEMPTS) {
            snapshot = CloudTransferQueueLogic.markFailed(snapshot, "b", "انقطع الاتصال أثناء النقل", retryLater = false)
        }
        val text = CloudTransferQueueLogic.outcomeNotificationText(snapshot, completedItems = 2)!!
        assertTrue(text, text.contains("اكتمل 2"))
        assertTrue(text, text.contains("تعذّر 1"))
    }

    @Test
    fun staleCompletedBatchesDoNotInflateThisRunsNumber() {
        // المنتهية تبقى محفوظة للمقارنة؛ لو حسبناها من الطابور لقرأ المستخدم «اكتمل 6»
        // عن تشغيلٍ نقل عنصرين فقط.
        var snapshot = CloudTransferQueueSnapshot()
        listOf("a", "b", "c").forEachIndexed { index, id ->
            snapshot = CloudTransferQueueLogic.enqueue(snapshot, upload(id, 10L + index, 11L + index), now = index.toLong())
            snapshot = CloudTransferQueueLogic.markDone(snapshot, id)
        }
        snapshot = CloudTransferQueueLogic.enqueue(snapshot, upload("d", 99L), now = 4L)
        repeat(CloudTransferQueueLogic.MAX_ATTEMPTS) {
            snapshot = CloudTransferQueueLogic.markFailed(snapshot, "d", "تعذّر الوصول إلى خادم السحابة", retryLater = false)
        }
        val text = CloudTransferQueueLogic.outcomeNotificationText(snapshot, completedItems = 2)!!
        assertTrue("لا «اكتمل 6»: $text", text.contains("اكتمل 2") && !text.contains("اكتمل 6"))
    }

    @Test
    fun outcomeMentionsPauseAndWaitingCount() {
        var snapshot = CloudTransferQueueLogic.enqueue(CloudTransferQueueSnapshot(), upload("a", 1), now = 1L)
        snapshot = CloudTransferQueueLogic.enqueue(snapshot, upload("b", 2), now = 2L)
        snapshot = CloudTransferQueueLogic.setPaused(snapshot, true)
        val text = CloudTransferQueueLogic.outcomeNotificationText(snapshot, completedItems = 0)!!
        assertTrue(text, text.contains("متوقف مؤقتًا"))
        assertTrue(text, text.contains("2 في الانتظار"))
        assertTrue(text, text.contains("للاستئناف"))
    }

    @Test
    fun outcomeTextIsNeverTechnical() {
        // I1: الإشعارات أخطر من البطاقة لأن اسم الصنف فيها يقرأه المستخدم في قفل الشاشة
        listOf(
            failedUploadSnapshot(1),
            CloudTransferQueueLogic.setPaused(CloudTransferQueueSnapshot(), true)
        ).forEach { snapshot ->
            CloudTransferQueueLogic.outcomeNotificationText(snapshot, completedItems = 0)?.let {
                assertTrue("تسريب: $it", CloudFailureMessages.isUserFacing(it))
            }
        }
    }
}
