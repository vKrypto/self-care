package com.forma.datasync.sync

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

class LocalQueueFullException : IllegalStateException(
    "Local collection storage is full. Connect and sync queued data before collecting more."
)

/**
 * A durable installation-local outbox. Only opaque sequence numbers and batch UUIDs
 * appear in filenames; all identity, timestamps, data and cursors pass through the codec.
 * The injected codec keeps file ordering, crash recovery and account isolation testable
 * without Android's keystore. Production supplies authenticated AES-GCM encryption.
 */
class LocalBatchQueue(
    private val directory: File,
    private val encode: (String) -> String,
    private val decode: (String) -> String,
    private val limitBytes: Long = DEFAULT_LIMIT_BYTES,
    private val claimReserveBytes: Long = minOf(DEFAULT_CLAIM_RESERVE_BYTES, limitBytes / 4),
) {
    private data class Metadata(
        val length: Long,
        val modified: Long,
        val serverUrl: String?,
        val userId: String?,
        val deviceId: String,
        val collectionEpoch: Long,
        val cursors: Map<String, Long>,
    )
    private val metadataCache = mutableMapOf<String, Metadata>()

    init {
        require(limitBytes > 0) { "The collection storage limit must be positive." }
        require(claimReserveBytes in 0 until limitBytes) { "Invalid account claim storage reserve." }
        synchronized(lock) {
            check(directory.isDirectory || directory.mkdirs()) { "Unable to create local collection storage." }
            // A process killed before rename never committed the temporary record.
            directory.listFiles()?.filter { it.name.endsWith(".enc.tmp") }?.forEach {
                check(it.delete()) { "Unable to recover local collection storage." }
            }
        }
    }

    fun append(batch: JSONObject) = synchronized(lock) {
        val id = batchId(batch)
        owner(batch)
        val files = files()
        val existing = files.firstOrNull { fileBatchId(it) == id }
        if (existing != null) {
            require(sameJson(read(existing), batch)) { "A batch UUID already contains different collected data." }
            return@synchronized
        }
        val sequence = (files.lastOrNull()?.name?.substringBefore('_')?.toLong() ?: 0L)
        check(sequence < Long.MAX_VALUE) { "The local collection sequence is exhausted." }
        val name = "%020d_%s.enc".format(java.util.Locale.ROOT, sequence + 1, id)
        // Keep room to claim and upload a guest record even when collection fills its cap.
        write(File(directory, name), batch, files, limitBytes - claimReserveBytes)
    }

    /** Claim a guest batch durably before exposing it to a network request. */
    fun next(
        serverUrl: String,
        userId: String,
        deviceId: String,
        excludeBatchIds: Set<String> = emptySet(),
        canUpload: (JSONObject) -> Boolean = { true },
    ): JSONObject? = synchronized(lock) {
        require(serverUrl.isNotBlank() && userId.isNotBlank() && deviceId.isNotBlank()) {
            "A connected account and registered device are required to upload collected data."
        }
        val files = files()
        for (file in files) {
            if (fileBatchId(file) in excludeBatchIds) continue
            val metadata = metadata(file)
            if (metadata.serverUrl != null &&
                (metadata.serverUrl != serverUrl || metadata.userId != userId)) continue
            val batch = read(file)
            val owner = owner(batch)
            if (owner != null) {
                if (owner.optString("serverUrl") != serverUrl || owner.optString("userId") != userId) continue
                // An existing claim has already fixed the request body for idempotent retries.
                if (batch.getJSONObject("payload").optString("device_id") != deviceId) continue
                if (!canUpload(batch)) continue
                return@synchronized batch
            }
            batch.put("owner", JSONObject().put("serverUrl", serverUrl).put("userId", userId))
            batch.put("userId", userId)
            batch.getJSONObject("payload").put("user_id", userId).put("device_id", deviceId)
            // Permission-blocked guest records remain unclaimed. This also preserves
            // claim headroom for a later eligible record when collection fills the queue.
            if (!canUpload(batch)) continue
            write(file, batch, files)
            return@synchronized batch
        }
        null
    }

    fun remove(batchId: String) = synchronized(lock) {
        validateId(batchId)
        files().firstOrNull { fileBatchId(it) == batchId }?.let {
            check(it.delete()) { "Unable to acknowledge the local collection batch." }
            metadataCache.remove(it.name)
        }
        Unit
    }

    fun batchIds(): List<String> = synchronized(lock) { files().map(::fileBatchId) }

    fun readBatch(batchId: String): JSONObject? = synchronized(lock) {
        validateId(batchId)
        files().firstOrNull { fileBatchId(it) == batchId }?.let(::read)
    }

    fun stats(serverUrl: String? = null, userId: String? = null, deviceId: String? = null): JSONObject = synchronized(lock) {
        val files = files()
        var waiting = 0
        var waitingConnection = 0
        for (file in files) {
            val metadata = metadata(file)
            if (metadata.serverUrl != null && (metadata.serverUrl != serverUrl || metadata.userId != userId)) waiting++
            else if (metadata.serverUrl != null && deviceId != null && metadata.deviceId != deviceId) waitingConnection++
        }
        JSONObject()
            .put("queuedBatches", files.size)
            .put("queuedBytes", files.sumOf { it.length() })
            .put("waitingAccountBatches", waiting)
            .put("waitingConnectionBatches", waitingConnection)
            .put("storageLimitBytes", limitBytes)
    }

    /** Recover the append-before-state-update crash without advancing any failed source. */
    fun recoverCursors(collectionEpoch: Long = 0): JSONObject = synchronized(lock) {
        val cursors = JSONObject()
        for (file in files()) {
            val metadata = metadata(file)
            if (metadata.collectionEpoch != collectionEpoch) continue
            for ((source, end) in metadata.cursors) {
                if (!cursors.has(source) || end > cursors.getLong(source)) cursors.put(source, end)
            }
        }
        cursors
    }

    fun clear() = synchronized(lock) {
        files().forEach { check(it.delete()) { "Unable to clear local collection storage." } }
        metadataCache.clear()
    }

    private fun files(): List<File> = directory.listFiles()
        ?.filter { it.isFile && FILE_NAME.matches(it.name) }
        ?.sortedBy { it.name }
        ?: throw IllegalStateException("Unable to read local collection storage.")

    private fun read(file: File): JSONObject {
        val batch = JSONObject(decode(file.readText(Charsets.UTF_8)))
        check(batchId(batch) == fileBatchId(file)) { "Invalid local collection batch identity." }
        return batch
    }

    private fun write(file: File, batch: JSONObject, files: List<File>, writeLimit: Long = limitBytes) {
        // Validate recovery metadata before committing the record to disk.
        val parsedMetadata = metadata(file, batch)
        val encoded = encode(batch.toString()).toByteArray(Charsets.UTF_8)
        val existingBytes = if (file.exists()) file.length() else 0L
        if (files.sumOf { it.length() } - existingBytes + encoded.size > writeLimit) throw LocalQueueFullException()
        val temp = File(directory, file.name + ".tmp")
        try {
            FileOutputStream(temp).use { stream ->
                stream.write(encoded)
                stream.fd.sync()
            }
            check(temp.renameTo(file)) { "Unable to save the local collection batch." }
            metadataCache[file.name] = parsedMetadata.copy(length = file.length(), modified = file.lastModified())
        } finally {
            // On a normal exception retain the previously committed file, never partial bytes.
            if (temp.exists()) check(temp.delete()) { "Unable to clean up local collection storage." }
        }
    }

    private fun batchId(batch: JSONObject): String = batch.getJSONObject("payload").getString("batch_id")
        .also(::validateId)

    private fun metadata(file: File): Metadata {
        metadataCache[file.name]?.let { cached ->
            if (cached.length == file.length() && cached.modified == file.lastModified()) return cached
        }
        return metadata(file, read(file)).also { metadataCache[file.name] = it }
    }

    private fun metadata(file: File, batch: JSONObject): Metadata {
        val owner = owner(batch)
        val updates = batch.optJSONObject("cursorUpdates")
        val cursors = updates?.keys()?.asSequence()?.associateWith { updates.getLong(it) } ?: emptyMap()
        return Metadata(file.length(), file.lastModified(), owner?.getString("serverUrl"),
            owner?.getString("userId"), batch.getJSONObject("payload").optString("device_id"), batch.optLong("collectionEpoch", 0), cursors)
    }

    private fun owner(batch: JSONObject): JSONObject? {
        if (!batch.has("owner") || batch.isNull("owner")) return null
        val owner = batch.getJSONObject("owner")
        require(owner.optString("serverUrl").isNotBlank() && owner.optString("userId").isNotBlank()) {
            "Invalid collected data account ownership."
        }
        return owner
    }

    private fun fileBatchId(file: File): String = file.name.substringAfter('_').removeSuffix(".enc")

    private fun validateId(value: String) {
        require(runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)) {
            "A canonical batch UUID is required for local storage."
        }
    }

    private fun sameJson(first: Any?, second: Any?): Boolean = when {
        first is JSONObject && second is JSONObject -> {
            val keys = first.keys().asSequence().toSet()
            keys == second.keys().asSequence().toSet() && keys.all { sameJson(first.get(it), second.get(it)) }
        }
        first is JSONArray && second is JSONArray -> first.length() == second.length() &&
            (0 until first.length()).all { sameJson(first.get(it), second.get(it)) }
        first is Number && second is Number -> first.toString().toBigDecimal()
            .compareTo(second.toString().toBigDecimal()) == 0
        else -> first == second
    }

    companion object {
        const val DEFAULT_LIMIT_BYTES = 64L * 1024L * 1024L
        const val DEFAULT_CLAIM_RESERVE_BYTES = 8L * 1024L
        private val FILE_NAME = Regex("[0-9]{20}_[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.enc")
        private val lock = Any()
    }
}
