package com.forma.datasync.sync

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.Base64
import java.util.UUID

class CollectionHistoryStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private var clock = 100 * SyncPlanner.DAY_MS
    private fun encode(value: String) = Base64.getEncoder().encodeToString(value.toByteArray(Charsets.UTF_8))
    private fun decode(value: String) = String(Base64.getDecoder().decode(value), Charsets.UTF_8)

    private inner class Fixture(
        val root: File = temporary.newFolder(),
        val archiveBytes: Long = CollectionHistoryStore.DEFAULT_ARCHIVE_BYTES,
        val maxJobs: Int = 500,
        val encoder: (String) -> String = ::encode,
    ) {
        val queue = LocalBatchQueue(File(root, "queue"), ::encode, ::decode)
        val historyDirectory = File(root, "history")
        var history = open()
        fun open(process: String = "current-process") = CollectionHistoryStore(historyDirectory, encoder, ::decode,
            queue::batchIds, queue::readBatch, { clock }, archiveBytes, 30, maxJobs, process)
        fun append(batch: JSONObject) { queue.append(batch); history.recordBatch(batch) }
        fun ack(batch: JSONObject, time: Long = clock) { history.markSynced(id(batch), time); queue.remove(id(batch)) }
    }

    private fun batch(job: String? = null, values: List<Any> = listOf(JSONObject().put("app", "private.app").put("duration", 12))): JSONObject {
        val audit = JSONArray()
            .put(JSONObject().put("source", "usage_stats").put("status", "ok").put("complete", true).put("collected", true))
            .put(JSONObject().put("source", "health_steps").put("status", "denied").put("complete", false)
                .put("collected", false).put("reason", "permission_not_granted"))
        val data = JSONObject().put("usage_stats", JSONObject().put("status", "ok").put("complete", true)
            .put("mode", "historical").put("records", JSONArray(values)))
            .put("health_status", JSONObject().put("status", "ok").put("complete", true)
                .put("details", JSONObject().put("sdk_status", "available").put("history_granted", false)))
            .put("source_status", JSONObject().put("status", "ok").put("records", audit))
        return JSONObject().put("payload", JSONObject().put("batch_id", UUID.randomUUID().toString())
            .put("schema_version", 1).put("device_id", UUID.randomUUID().toString())
            .put("collected_at_ms", clock).put("window", JSONObject().put("start_ms", clock - 1000).put("end_ms", clock))
            .put("permissions", JSONObject().put("usageAccess", true)).put("data", data))
            .put("cursorUpdates", JSONObject().put("usage_stats", clock))
            .apply { if (job != null) put("collectionJobId", job) }
    }

    private fun id(batch: JSONObject) = batch.getJSONObject("payload").getString("batch_id")
    private fun firstJob(history: CollectionHistoryStore) = history.history(0, 20).getJSONArray("jobs").getJSONObject(0)
    private fun source(details: JSONObject, name: String): JSONObject {
        val sources = details.getJSONArray("sources")
        return (0 until sources.length()).map { sources.getJSONObject(it) }.first { it.getString("name") == name }
    }

    @Test fun aCollectionJobGroupsWindowsWithoutCountingAdministrativeAuditRows() {
        val fixture = Fixture()
        val job = fixture.history.begin(background = true)
        fixture.append(batch(job, listOf("first", "second")))
        fixture.append(batch(job, listOf("third")))
        fixture.history.finish(job, "completed")

        val details = fixture.history.details(job)
        assertEquals(1, fixture.history.history(0, 20).getInt("total"))
        assertEquals(2, details.getInt("batchCount"))
        assertEquals(3L, details.getLong("recordCount"))
        assertEquals(2, details.getInt("sourceCount"))
        assertEquals(2, details.getInt("queuedBatches"))
        assertEquals("queued", details.getString("uploadStatus"))
        assertTrue(details.getBoolean("background"))
        assertTrue(details.getBoolean("detailsAvailable"))
        assertEquals("denied", source(details, "health_steps").getString("status"))
        assertFalse(source(details, "health_steps").getBoolean("collected"))
        assertFalse(source(details, "health_steps").getBoolean("complete"))
        assertEquals("permission_not_granted", source(details, "health_steps").getString("reason"))
        assertEquals(3, details.getJSONArray("sources").length())
    }

    @Test fun serverAcknowledgmentArchivesActualRecordsAfterQueueDeletionAndReopening() {
        val fixture = Fixture()
        val job = fixture.history.begin(false)
        val collected = batch(job)
        fixture.append(collected)
        fixture.history.finish(job, "completed")
        fixture.ack(collected)

        val reopened = fixture.open()
        val details = reopened.details(job)
        assertEquals(0, details.getInt("queuedBatches"))
        assertEquals(1, details.getInt("syncedBatches"))
        assertEquals("synced", details.getString("uploadStatus"))
        assertEquals(1, details.getInt("recordCount"))
        assertTrue(details.getBoolean("detailsAvailable"))
        val rows = reopened.records(job, "usage_stats", 0, 25)
        assertEquals("private.app", rows.getJSONArray("records").getJSONObject(0).getJSONObject("value").getString("app"))
        assertEquals(id(collected), rows.getJSONArray("records").getJSONObject(0).getString("batchId"))
        assertEquals(0, fixture.queue.stats().getInt("queuedBatches"))
    }

    @Test fun acknowledgmentRetriesKeepFirstReceiptAndDoNotDoubleCountQueueAndArchive() {
        val fixture = Fixture()
        val job = fixture.history.begin(false)
        val collected = batch(job)
        fixture.append(collected)
        fixture.history.markSynced(id(collected), clock)
        clock += 1000
        fixture.history.markSynced(id(collected), clock)
        fixture.history.recordBatch(collected)
        val details = fixture.history.details(job)
        assertEquals(1, details.getInt("recordCount"))
        assertEquals(1, details.getInt("batchCount"))
        assertEquals(clock - 1000, details.getLong("lastSyncedAt"))
        assertEquals(1, fixture.history.records(job, "usage_stats", 0, 20).getInt("available"))
        fixture.queue.remove(id(collected))
        fixture.history.markSynced(id(collected), clock)
        assertEquals(clock - 1000, fixture.open().details(job).getLong("lastSyncedAt"))
    }

    @Test fun failedUploadRemainsSeparateFromCollectionAndAcknowledgmentClearsIt() {
        val fixture = Fixture()
        val job = fixture.history.begin(false)
        val collected = batch(job)
        fixture.append(collected)
        fixture.history.finish(job, "completed")
        fixture.history.markUploadError(id(collected), "Server unavailable.")
        val failed = fixture.history.details(job)
        assertEquals("completed", failed.getString("status"))
        assertEquals("failed", failed.getString("uploadStatus"))
        assertEquals(1, failed.getInt("queuedBatches"))
        assertEquals("Server unavailable.", failed.getJSONArray("batches").getJSONObject(0).getString("error"))

        fixture.ack(collected)
        assertEquals("synced", fixture.history.details(job).getString("uploadStatus"))
        assertTrue(fixture.history.details(job).getJSONArray("batches").getJSONObject(0).isNull("error"))
    }

    @Test fun archiveWriteFailurePreservesKnownReceiptAndQueueForIdempotentRetry() {
        var failArchive = true
        val fixture = Fixture(encoder = { value ->
            if (failArchive && JSONObject(value).has("batch")) throw IOException("Simulated archive failure")
            encode(value)
        })
        val job = fixture.history.begin(false)
        val collected = batch(job)
        fixture.append(collected)
        expect<IOException> { fixture.history.markSynced(id(collected), clock) }
        fixture.history.markUploadError(id(collected), "Local history could not save.")

        val acknowledged = fixture.history.details(job)
        assertEquals("synced", acknowledged.getString("uploadStatus"))
        assertTrue(acknowledged.getBoolean("detailsAvailable"))
        assertNotNull(fixture.queue.readBatch(id(collected)))
        failArchive = false
        clock += 1000
        fixture.ack(collected)
        assertEquals(clock - 1000, fixture.open().details(job).getLong("lastSyncedAt"))
        assertEquals(1, fixture.open().records(job, "usage_stats", 0, 25).getInt("available"))
    }

    @Test fun rawArchiveExpiresAfterThirtyDaysButSummaryAndOriginalCountsRemain() {
        val fixture = Fixture()
        val job = fixture.history.begin(false)
        val collected = batch(job, listOf("one", "two"))
        fixture.append(collected)
        fixture.history.finish(job, "completed")
        fixture.ack(collected)
        clock += 31 * SyncPlanner.DAY_MS

        val details = fixture.history.details(job)
        assertEquals(2, details.getInt("recordCount"))
        assertEquals("synced", details.getString("uploadStatus"))
        assertFalse(details.getBoolean("detailsAvailable"))
        val rows = fixture.history.records(job, "usage_stats", 0, 25)
        assertEquals(2, rows.getInt("total"))
        assertEquals(0, rows.getInt("available"))
        assertFalse(rows.getBoolean("detailsAvailable"))
        assertTrue(rows.isNull("nextOffset"))
        assertEquals(0, File(fixture.historyDirectory, "synced").listFiles()!!.size)
    }

    @Test fun archiveByteLimitEvictsAcknowledgedRawDataAndNeverQueuedRecords() {
        val fixture = Fixture(archiveBytes = 1800)
        val oldJob = fixture.history.begin(false)
        val old = batch(oldJob)
        fixture.append(old)
        fixture.history.finish(oldJob, "completed")
        fixture.ack(old)

        val queuedJob = fixture.history.begin(true)
        val queued = batch(queuedJob)
        fixture.append(queued)
        fixture.history.finish(queuedJob, "completed")

        clock += 1000
        val recentJob = fixture.history.begin(false)
        val recent = batch(recentJob)
        fixture.append(recent)
        fixture.history.finish(recentJob, "completed")
        fixture.ack(recent)

        assertNotNull(fixture.queue.readBatch(id(queued)))
        assertTrue(fixture.history.details(queuedJob).getBoolean("detailsAvailable"))
        assertFalse(fixture.history.details(oldJob).getBoolean("detailsAvailable"))
        assertTrue(fixture.history.details(recentJob).getBoolean("detailsAvailable"))
        assertTrue(File(fixture.historyDirectory, "synced").listFiles()!!.sumOf { it.length() } <= 1800)
    }

    @Test fun aBatchLargerThanArchiveBudgetStillRecordsTheReceiptWithoutBlockingUpload() {
        val fixture = Fixture(archiveBytes = 1)
        val job = fixture.history.begin(false)
        val collected = batch(job)
        fixture.append(collected)
        fixture.history.finish(job, "completed")
        fixture.ack(collected)

        assertEquals("synced", fixture.history.details(job).getString("uploadStatus"))
        assertFalse(fixture.history.details(job).getBoolean("detailsAvailable"))
        assertEquals(1, fixture.history.records(job, "usage_stats", 0, 25).getInt("total"))
    }

    @Test fun recordPaginationReturnsExactRowsAcrossBatchBoundaries() {
        val fixture = Fixture()
        val job = fixture.history.begin(false)
        val first = batch(job, listOf("a", "b", "c"))
        val second = batch(job, listOf("d", "e"))
        fixture.append(first); fixture.append(second)
        fixture.history.markSynced(id(first), clock)
        val page = fixture.history.records(job, "usage_stats", 2, 2)
        assertEquals(5, page.getInt("total"))
        assertEquals(5, page.getInt("available"))
        assertEquals(4, page.getInt("nextOffset"))
        val records = page.getJSONArray("records")
        assertEquals("c", records.getJSONObject(0).getString("value"))
        assertEquals(id(first), records.getJSONObject(0).getString("batchId"))
        assertEquals("d", records.getJSONObject(1).getString("value"))
        assertEquals(id(second), records.getJSONObject(1).getString("batchId"))
        assertFalse(page.getJSONArray("sections").getJSONObject(0).getJSONObject("metadata").has("records"))
        assertTrue(fixture.history.records(job, "usage_stats", 4, 2).isNull("nextOffset"))
    }

    @Test fun metadataOnlyHealthStatusIsViewableWithNoFabricatedRecords() {
        val fixture = Fixture()
        val job = fixture.history.begin(false)
        fixture.append(batch(job))
        val rows = fixture.history.records(job, "health_status", 0, 25)
        assertEquals(0, rows.getInt("total"))
        assertEquals(0, rows.getInt("available"))
        assertTrue(rows.getBoolean("detailsAvailable"))
        assertEquals("available", rows.getJSONArray("sections").getJSONObject(0)
            .getJSONObject("metadata").getJSONObject("details").getString("sdk_status"))
        assertTrue(source(fixture.history.details(job), "health_status").getBoolean("detailsAvailable"))
    }

    @Test fun sourceAggregationPreservesEarlierDataWhenLaterWindowLosesPermission() {
        val fixture = Fixture()
        val job = fixture.history.begin(false)
        fixture.append(batch(job, listOf("first", "second")))
        val revoked = batch(job)
        val data = revoked.getJSONObject("payload").getJSONObject("data")
        data.remove("usage_stats")
        data.getJSONObject("source_status").getJSONArray("records").put(0, JSONObject()
            .put("source", "usage_stats").put("status", "denied").put("complete", false)
            .put("reason", "permission_revoked").put("collected", false))
        fixture.append(revoked)

        val aggregate = source(fixture.history.details(job), "usage_stats")
        assertEquals("mixed", aggregate.getString("status"))
        assertEquals(2, aggregate.getInt("recordCount"))
        assertTrue(aggregate.getBoolean("collected"))
        assertFalse(aggregate.getBoolean("complete"))
        assertEquals("permission_revoked", aggregate.getString("reason"))
        assertEquals(2, fixture.history.records(job, "usage_stats", 0, 25).getInt("available"))
    }

    @Test fun legacyQueuedBatchesBecomeRealImportedJobsWithoutInventingMissingUploads() {
        val fixture = Fixture()
        val legacy = batch()
        fixture.queue.append(legacy) // Upgrade also recovers the append-before-index crash.
        val reopened = fixture.open()
        val job = reopened.details(id(legacy))
        assertEquals("legacy", job.getString("status"))
        assertEquals(1, job.getInt("batchCount"))
        assertEquals(1, reopened.history(0, 25).getInt("total"))
        assertEquals(1, reopened.records(id(legacy), "usage_stats", 0, 25).getInt("available"))
        assertEquals(0, job.getInt("syncedBatches"))
    }

    @Test fun unfinishedPreviousProcessJobIsInterruptedWithoutAffectingLiveRunningJobs() {
        val fixture = Fixture()
        val job = fixture.history.begin(true)
        fixture.append(batch(job))
        assertEquals("running", fixture.open("current-process").details(job).getString("status"))
        val recovered = fixture.open("new-process").details(job)
        assertEquals("interrupted", recovered.getString("status"))
        assertFalse(recovered.isNull("finishedAt"))
        assertEquals(1, recovered.getInt("queuedBatches"))
        assertEquals("Collection interrupted before completion.", recovered.getString("error"))
    }

    @Test fun summaryCapKeepsAllQueuedJobsAlongsideNewestFinishedSummaries() {
        val fixture = Fixture(maxJobs = 2)
        val queuedJobs = (0 until 3).map {
            clock++
            val job = fixture.history.begin(false)
            fixture.append(batch(job)); fixture.history.finish(job, "completed")
            job
        }
        val finished = (0 until 3).map {
            clock++
            val job = fixture.history.begin(false)
            fixture.history.finish(job, "completed")
            job
        }
        val page = fixture.history.history(0, 25)
        assertEquals(5, page.getInt("total"))
        queuedJobs.forEach { assertEquals(1, fixture.history.details(it).getInt("queuedBatches")) }
        expect<IllegalArgumentException> { fixture.history.details(finished.first()) }
        assertEquals(3, fixture.queue.stats().getInt("queuedBatches"))
        assertEquals(2, page.getJSONObject("retention").getInt("maxJobs"))
    }

    @Test fun historyPaginationAndLargeOffsetsAreSafeAndReturnCorrectBoundaries() {
        val fixture = Fixture()
        repeat(3) { clock++; fixture.history.finish(fixture.history.begin(false), "completed") }
        assertEquals(2, fixture.history.history(0, 2).getInt("nextOffset"))
        assertTrue(fixture.history.history(2, 2).isNull("nextOffset"))
        val empty = fixture.history.history(Int.MAX_VALUE, 100)
        assertEquals(0, empty.getJSONArray("jobs").length())
        assertTrue(empty.isNull("nextOffset"))
        val job = fixture.history.begin(false)
        fixture.append(batch(job))
        assertEquals(0, fixture.history.records(job, "usage_stats", Int.MAX_VALUE, 100).getJSONArray("records").length())
    }

    @Test fun unsafeAndMissingIdsOrInvalidPagesCannotReadLocalFiles() {
        val fixture = Fixture()
        val job = fixture.history.begin(false)
        expect<IllegalArgumentException> { fixture.history.details("../outside") }
        expect<IllegalArgumentException> { fixture.history.details(UUID.randomUUID().toString()) }
        expect<IllegalArgumentException> { fixture.history.records(job, "../outside", 0, 25) }
        expect<IllegalArgumentException> { fixture.history.records(job, "source_status", 0, 25) }
        expect<IllegalArgumentException> { fixture.history.history(-1, 25) }
        expect<IllegalArgumentException> { fixture.history.history(0, 101) }
    }

    @Test fun bothJobMetadataAndAcknowledgedRecordsPassThroughTheEncryptionCodec() {
        val fixture = Fixture()
        val job = fixture.history.begin(false)
        val collected = batch(job).put("owner", JSONObject().put("serverUrl", "https://private.example").put("userId", "private-user"))
        fixture.append(collected)
        fixture.history.finish(job, "completed")
        fixture.ack(collected)
        val encrypted = fixture.historyDirectory.walkTopDown().filter { it.isFile }.toList()
        assertEquals(2, encrypted.size)
        encrypted.forEach { file ->
            assertFalse(file.readText().contains("private-user"))
            assertFalse(file.readText().contains("private.app"))
            assertFalse(file.name.contains("private-user"))
            assertTrue(JSONObject(decode(file.readText())).length() > 0)
        }
    }

    private inline fun <reified T : Throwable> expect(action: () -> Unit) {
        try { action(); throw AssertionError("Expected ${T::class.java.simpleName}") }
        catch (error: Throwable) { if (error !is T) throw error }
    }
}
