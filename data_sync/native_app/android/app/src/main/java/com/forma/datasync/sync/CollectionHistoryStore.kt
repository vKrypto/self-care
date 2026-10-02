package com.forma.datasync.sync

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/** Encrypted job summaries and a bounded archive of server-acknowledged raw batches. */
class CollectionHistoryStore(
    directory: File,
    private val encode: (String) -> String,
    private val decode: (String) -> String,
    private val queuedIds: () -> List<String>,
    private val queuedBatch: (String) -> JSONObject?,
    private val now: () -> Long = System::currentTimeMillis,
    private val archiveLimitBytes: Long = DEFAULT_ARCHIVE_BYTES,
    private val archiveDays: Int = DEFAULT_ARCHIVE_DAYS,
    private val maxFinishedJobs: Int = DEFAULT_MAX_JOBS,
    private val processId: String = PROCESS_ID,
) {
    private val jobsDirectory = File(directory, "jobs")
    private val archiveDirectory = File(directory, "synced")
    private val jobs = linkedMapOf<String, JSONObject>()
    private val batchJobs = mutableMapOf<String, String>()
    private var currentQueuedIds = emptySet<String>()
    private data class Archive(val file: File, val syncedAt: Long)
    private val archives = mutableMapOf<String, Archive>()

    init {
        require(archiveLimitBytes > 0 && archiveDays > 0 && maxFinishedJobs > 0)
        synchronized(lock) {
            currentQueuedIds = queuedIds().toSet()
            listOf(jobsDirectory, archiveDirectory).forEach { folder ->
                check(folder.isDirectory || folder.mkdirs()) { "Unable to create encrypted collection history." }
                folder.listFiles()?.filter { it.name.endsWith(".enc.tmp") }?.forEach { it.delete() }
            }
            files(jobsDirectory).forEach { file ->
                val job = read(file)
                require(job.getString("id") == file.name.removeSuffix(".enc")) { "Invalid collection job identity." }
                jobs[job.getString("id")] = job
                forEachBatch(job) { batch -> batchJobs[batch.getString("id")] = job.getString("id") }
            }
            // Recover a crash during archive/index persistence without loading all records into RAM.
            files(archiveDirectory).forEach { file ->
                val archived = read(file)
                val batch = archived.getJSONObject("batch")
                val id = batchId(batch)
                require(id == file.name.removeSuffix(".enc")) { "Invalid archived collection batch identity." }
                archives[id] = Archive(file, archived.getLong("syncedAt"))
                recordBatchLocked(batch)
                acknowledgeLocked(id, archived.getLong("syncedAt"))
            }
            // Queued raw records are authoritative even if the process died before their index write.
            currentQueuedIds.forEach { id -> queuedBatch(id)?.let(::recordBatchLocked) }
            jobs.values.toList().filter { it.optString("status") == "running" && it.optString("processId") != processId }
                .forEach { job ->
                    job.put("status", "interrupted").put("finishedAt", now())
                        .put("error", "Collection interrupted before completion.")
                    save(job)
                }
            pruneLocked()
        }
    }

    fun begin(background: Boolean): String = synchronized(lock) {
        val id = UUID.randomUUID().toString()
        val job = newJob(id, now(), background, "running")
        save(job)
        jobs[id] = job
        pruneLocked()
        id
    }

    fun finish(jobId: String, status: String, error: String? = null) = synchronized(lock) {
        require(status in setOf("running", "completed", "partial", "failed", "cancelled")) { "Invalid collection job status." }
        val job = job(jobId)
        job.put("status", status).put("finishedAt", if (status == "running") JSONObject.NULL else now())
            .put("error", error?.take(1000) ?: JSONObject.NULL)
        save(job)
        pruneLocked()
    }

    fun recordBatch(batch: JSONObject) = synchronized(lock) { recordBatchLocked(batch) }

    /** A receipt is durable before the outbox can be deleted, including archive-write failures. */
    fun markSynced(batchId: String, syncedAt: Long) = synchronized(lock) {
        validateId(batchId)
        require(syncedAt >= 0) { "Invalid acknowledgment timestamp." }
        val raw = queuedBatch(batchId) ?: archiveBatch(batchId)
        if (raw != null) recordBatchLocked(raw)
        require(batchJobs.containsKey(batchId)) { "Collection batch is no longer in local history." }
        val firstAck = acknowledgeLocked(batchId, syncedAt)
        if (raw != null && !archives.containsKey(batchId) && firstAck >= retentionStart()) {
            val wrapper = JSONObject().put("batch", raw).put("syncedAt", firstAck)
            val encoded = encode(wrapper.toString()).toByteArray(Charsets.UTF_8)
            // Retention pruning can skip an archive; it never prevents a valid upload receipt.
            if (makeArchiveRoom(encoded.size.toLong())) {
                val file = File(archiveDirectory, "$batchId.enc")
                writeEncoded(file, encoded)
                archives[batchId] = Archive(file, firstAck)
            }
        }
        pruneLocked()
    }

    fun markUploadError(batchId: String, message: String) = synchronized(lock) {
        validateId(batchId)
        queuedBatch(batchId)?.let(::recordBatchLocked)
        val jobId = batchJobs[batchId] ?: return@synchronized
        val job = jobs.getValue(jobId)
        val batch = batchSummary(job, batchId)
        if (!batch.isNull("syncedAt")) return@synchronized
        batch.put("status", "failed").put("error", message.take(1000))
        save(job)
    }

    fun history(offset: Int, limit: Int): JSONObject = synchronized(lock) {
        validatePage(offset, limit)
        pruneLocked()
        val ordered = jobs.values.sortedWith(compareByDescending<JSONObject> { it.getLong("startedAt") }
            .thenByDescending { it.getString("id") })
        val page = JSONArray()
        ordered.drop(offset).take(limit).forEach { page.put(publicJob(it)) }
        JSONObject().put("jobs", page).put("total", ordered.size)
            .put("nextOffset", nextOffset(offset, limit, ordered.size))
            .put("retention", JSONObject().put("syncedDays", archiveDays).put("syncedBytes", archiveLimitBytes)
                .put("maxJobs", maxFinishedJobs))
    }

    fun details(jobId: String): JSONObject = synchronized(lock) {
        pruneLocked()
        val job = job(jobId)
        val batches = JSONArray()
        forEachBatch(job) { batch ->
            val projected = JSONObject(batch.toString())
            projected.remove("sources")
            projected.put("detailsAvailable", rawAvailable(batch.getString("id")))
            batches.put(projected)
        }
        publicJob(job).put("sources", JSONArray(aggregateSources(job).values.toList()))
            .put("batches", batches).put("permissions", job.opt("permissions") ?: JSONObject.NULL)
    }

    fun records(jobId: String, source: String, offset: Int, limit: Int): JSONObject = synchronized(lock) {
        validatePage(offset, limit)
        require(SOURCE.matches(source) && source != "source_status") { "Invalid collected data source." }
        pruneLocked()
        val job = job(jobId)
        val expectedTotal = aggregateSources(job)[source]?.optLong("recordCount", 0) ?: 0L
        var available = 0L
        val rows = JSONArray()
        val sections = JSONArray()
        var hasDetails = false
        forEachBatch(job) { batch ->
            val id = batch.getString("id")
            val section = rawBatch(id)?.getJSONObject("payload")?.getJSONObject("data")?.optJSONObject(source)
            if (section != null) {
                hasDetails = true
                val metadata = JSONObject(section.toString()).apply { remove("records") }
                sections.put(JSONObject().put("batchId", id).put("metadata", metadata))
                val values = section.optJSONArray("records") ?: JSONArray()
                for (index in 0 until values.length()) {
                    if (available >= offset.toLong() && rows.length() < limit) {
                        rows.put(JSONObject().put("batchId", id).put("value", values.get(index)))
                    }
                    available++
                }
            }
        }
        JSONObject().put("jobId", jobId).put("source", source).put("records", rows)
            .put("total", expectedTotal).put("available", available).put("detailsAvailable", hasDetails)
            .put("nextOffset", if (offset.toLong() + rows.length() < available) offset + rows.length() else JSONObject.NULL)
            .put("sections", sections)
    }

    fun clear() = synchronized(lock) {
        (files(jobsDirectory) + files(archiveDirectory)).forEach {
            check(it.delete()) { "Unable to clear encrypted collection history." }
        }
        jobs.clear(); batchJobs.clear(); archives.clear()
    }

    private fun recordBatchLocked(raw: JSONObject) {
        val payload = raw.getJSONObject("payload")
        val id = batchId(raw)
        val assignedJob = raw.optString("collectionJobId").takeIf { it.isNotBlank() }
        if (assignedJob != null) validateId(assignedJob)
        val jobId = batchJobs[id] ?: assignedJob ?: id
        var job = jobs[jobId]
        if (job == null) {
            val timestamp = payload.optLong("collected_at_ms", payload.getJSONObject("window").getLong("end_ms"))
            job = newJob(jobId, timestamp, false, if (assignedJob == null) "legacy" else "interrupted")
                .put("finishedAt", timestamp)
                .put("error", if (assignedJob == null) "Imported queued data collected before job history was enabled."
                    else "Collection job recovered from locally saved data.")
            jobs[jobId] = job
        }
        val previous = batchSummaryOrNull(job, id)
        val owner = raw.optJSONObject("owner")
        val window = payload.getJSONObject("window")
        val summary = JSONObject().put("id", id).put("windowStart", window.getLong("start_ms"))
            .put("windowEnd", window.getLong("end_ms")).put("collectedAt", payload.optLong("collected_at_ms", window.getLong("end_ms")))
            .put("syncedAt", previous?.opt("syncedAt") ?: JSONObject.NULL)
            .put("status", previous?.optString("status") ?: "queued")
            .put("error", previous?.opt("error") ?: JSONObject.NULL)
            .put("serverUrl", owner?.optString("serverUrl") ?: JSONObject.NULL)
            .put("userId", owner?.optString("userId") ?: JSONObject.NULL)
            .put("bytes", payload.toString().toByteArray(Charsets.UTF_8).size)
            .put("sources", summarizeSources(payload.getJSONObject("data")))
        if (previous == null) job.getJSONArray("batches").put(summary)
        else {
            val batches = job.getJSONArray("batches")
            for (index in 0 until batches.length()) if (batches.getJSONObject(index).getString("id") == id) batches.put(index, summary)
        }
        job.put("permissions", payload.opt("permissions") ?: JSONObject.NULL)
        batchJobs[id] = jobId
        save(job)
    }

    private fun acknowledgeLocked(batchId: String, syncedAt: Long): Long {
        val job = jobs.getValue(batchJobs.getValue(batchId))
        val batch = batchSummary(job, batchId)
        val firstAck = if (batch.isNull("syncedAt")) syncedAt else batch.getLong("syncedAt")
        batch.put("syncedAt", firstAck).put("status", "synced").put("error", JSONObject.NULL)
        save(job)
        return firstAck
    }

    private fun summarizeSources(data: JSONObject): JSONArray {
        val result = linkedMapOf<String, JSONObject>()
        val audit = data.optJSONObject("source_status")?.optJSONArray("records") ?: JSONArray()
        for (index in 0 until audit.length()) {
            val entry = audit.optJSONObject(index) ?: continue
            val name = entry.optString("source")
            if (SOURCE.matches(name) && name != "source_status") result[name] = sourceSummary(name, entry, false, 0)
        }
        for (name in data.keys()) {
            if (name == "source_status") continue
            val section = data.optJSONObject(name) ?: continue
            result[name] = sourceSummary(name, section, true, section.optJSONArray("records")?.length() ?: 0)
        }
        return JSONArray(result.values.toList())
    }

    private fun sourceSummary(name: String, section: JSONObject, collected: Boolean, count: Int): JSONObject =
        JSONObject().put("name", name).put("status", section.optString("status", "unavailable"))
            .put("recordCount", count).put("collected", collected).put("complete", section.optBoolean("complete", true))
            .put("reason", section.opt("reason") ?: JSONObject.NULL)

    private fun aggregateSources(job: JSONObject): LinkedHashMap<String, JSONObject> {
        val sources = linkedMapOf<String, JSONObject>()
        forEachBatch(job) { batch ->
            val rawExists = rawAvailable(batch.getString("id"))
            val summaries = batch.getJSONArray("sources")
            for (index in 0 until summaries.length()) {
                val entry = summaries.getJSONObject(index)
                val name = entry.getString("name")
                val previous = sources[name]
                sources[name] = JSONObject(entry.toString())
                    .put("recordCount", (previous?.optLong("recordCount", 0) ?: 0) + entry.getLong("recordCount"))
                    .put("collected", previous?.optBoolean("collected") == true || entry.getBoolean("collected"))
                    .put("detailsAvailable", previous?.optBoolean("detailsAvailable") == true || (rawExists && entry.getBoolean("collected")))
            }
        }
        return sources
    }

    private fun publicJob(job: JSONObject): JSONObject {
        val sources = aggregateSources(job)
        var queued = 0
        var synced = 0
        var failed = 0
        var lastSynced: Long? = null
        var available = false
        forEachBatch(job) { batch ->
            if (!batch.isNull("syncedAt")) {
                synced++
                lastSynced = maxOf(lastSynced ?: 0, batch.getLong("syncedAt"))
            } else {
                queued++
                if (batch.optString("status") == "failed") failed++
            }
            available = available || rawAvailable(batch.getString("id"))
        }
        val upload = when {
            queued == 0 && synced == 0 -> "no_data"
            queued == 0 -> "synced"
            synced > 0 -> "partial"
            failed == queued -> "failed"
            else -> "queued"
        }
        return JSONObject().put("id", job.getString("id")).put("startedAt", job.getLong("startedAt"))
            .put("finishedAt", job.opt("finishedAt") ?: JSONObject.NULL).put("background", job.getBoolean("background"))
            .put("status", job.getString("status")).put("error", job.opt("error") ?: JSONObject.NULL)
            .put("batchCount", queued + synced).put("recordCount", sources.values.sumOf { it.getLong("recordCount") })
            .put("sourceCount", sources.values.count { it.getBoolean("collected") })
            .put("queuedBatches", queued).put("syncedBatches", synced).put("uploadStatus", upload)
            .put("lastSyncedAt", lastSynced ?: JSONObject.NULL).put("detailsAvailable", available)
    }

    private fun pruneLocked() {
        archives.values.toList().filter { it.syncedAt < retentionStart() }.forEach { removeArchive(it) }
        makeArchiveRoom(0)
        // Queued and currently running jobs stay visible regardless of the finished-summary cap.
        val protected = queuedIds().mapNotNull { batchJobs[it] }.toSet()
        currentQueuedIds = queuedIds().toSet()
        val finished = jobs.values.filter { it.getString("id") !in protected && it.optString("status") != "running" }
            .sortedWith(compareByDescending<JSONObject> { it.getLong("startedAt") }.thenByDescending { it.getString("id") })
        finished.drop(maxFinishedJobs).forEach { job ->
            if (File(jobsDirectory, "${job.getString("id")}.enc").delete()) {
                jobs.remove(job.getString("id"))
                forEachBatch(job) { batch ->
                    batchJobs.remove(batch.getString("id"))
                    archives[batch.getString("id")]?.let(::removeArchive)
                }
            }
        }
    }

    private fun makeArchiveRoom(additionalBytes: Long): Boolean {
        if (additionalBytes > archiveLimitBytes) return false
        var used = archives.values.sumOf { it.file.length() }
        for (archive in archives.values.sortedBy { it.syncedAt }) {
            if (used + additionalBytes <= archiveLimitBytes) break
            val bytes = archive.file.length()
            if (removeArchive(archive)) used -= bytes
        }
        return used + additionalBytes <= archiveLimitBytes
    }

    private fun removeArchive(archive: Archive): Boolean {
        if (archive.file.exists() && !archive.file.delete()) return false
        archives.remove(archive.file.name.removeSuffix(".enc"))
        return true
    }

    private fun rawAvailable(batchId: String): Boolean = batchId in currentQueuedIds ||
        archives[batchId]?.let { it.syncedAt >= retentionStart() && it.file.exists() } == true

    private fun rawBatch(batchId: String): JSONObject? = queuedBatch(batchId) ?: archiveBatch(batchId)

    private fun archiveBatch(batchId: String): JSONObject? = archives[batchId]?.takeIf { it.syncedAt >= retentionStart() }
        ?.let { if (it.file.exists()) read(it.file).getJSONObject("batch") else null }

    private fun retentionStart(): Long = now() - archiveDays.toLong() * SyncPlanner.DAY_MS

    private fun job(jobId: String): JSONObject {
        validateId(jobId)
        return jobs[jobId] ?: throw IllegalArgumentException("Collection job is no longer in local history.")
    }

    private fun newJob(id: String, startedAt: Long, background: Boolean, status: String) = JSONObject()
        .put("id", id).put("startedAt", startedAt).put("finishedAt", JSONObject.NULL).put("background", background)
        .put("status", status).put("error", JSONObject.NULL).put("processId", processId).put("batches", JSONArray())

    private fun batchSummary(job: JSONObject, batchId: String): JSONObject = batchSummaryOrNull(job, batchId)
        ?: throw IllegalArgumentException("Collection batch is no longer in local history.")

    private fun batchSummaryOrNull(job: JSONObject, batchId: String): JSONObject? {
        val batches = job.getJSONArray("batches")
        for (index in 0 until batches.length()) batches.getJSONObject(index).let { if (it.getString("id") == batchId) return it }
        return null
    }

    private inline fun forEachBatch(job: JSONObject, action: (JSONObject) -> Unit) {
        val batches = job.getJSONArray("batches")
        for (index in 0 until batches.length()) action(batches.getJSONObject(index))
    }

    private fun save(job: JSONObject) = writeEncoded(File(jobsDirectory, "${job.getString("id")}.enc"),
        encode(job.toString()).toByteArray(Charsets.UTF_8))

    private fun read(file: File): JSONObject = JSONObject(decode(file.readText(Charsets.UTF_8)))

    private fun files(directory: File): List<File> = directory.listFiles()?.filter { it.isFile && FILE_NAME.matches(it.name) }
        ?: throw IllegalStateException("Unable to read encrypted collection history.")

    private fun writeEncoded(file: File, bytes: ByteArray) {
        val temp = File(file.parentFile, file.name + ".tmp")
        try {
            FileOutputStream(temp).use { stream -> stream.write(bytes); stream.fd.sync() }
            check(temp.renameTo(file)) { "Unable to save encrypted collection history." }
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    private fun batchId(batch: JSONObject): String = batch.getJSONObject("payload").getString("batch_id").also(::validateId)

    private fun validateId(value: String) {
        require(runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)) { "Invalid collection history UUID." }
    }

    private fun validatePage(offset: Int, limit: Int) {
        require(offset >= 0 && limit in 1..100) { "Invalid history page. Use a positive limit up to 100." }
    }

    private fun nextOffset(offset: Int, limit: Int, total: Int): Any =
        if (offset.toLong() + limit < total) offset + limit else JSONObject.NULL

    companion object {
        const val DEFAULT_ARCHIVE_BYTES = 64L * 1024L * 1024L
        const val DEFAULT_ARCHIVE_DAYS = 30
        const val DEFAULT_MAX_JOBS = 500
        private val PROCESS_ID = UUID.randomUUID().toString()
        private val FILE_NAME = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.enc")
        private val SOURCE = Regex("[a-zA-Z][a-zA-Z0-9_]{0,127}")
        private val lock = Any()
    }
}
