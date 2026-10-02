package com.forma.datasync.sync

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Base64
import java.util.UUID

class LocalBatchQueueTest {
    @get:Rule val temporary = TemporaryFolder()
    private val server = "https://collector.example"
    private val user = "account-one"
    private val device = "device-one"

    // A reversible non-plaintext codec exercises the production codec boundary.
    // Android AES-GCM is supplied by SecureStore, rather than stubbed in JVM tests.
    private fun encode(value: String): String = Base64.getEncoder().encodeToString(value.toByteArray(Charsets.UTF_8))
    private fun decode(value: String): String = String(Base64.getDecoder().decode(value), Charsets.UTF_8)

    private fun queue(
        directory: File,
        limit: Long = LocalBatchQueue.DEFAULT_LIMIT_BYTES,
        reserve: Long = minOf(LocalBatchQueue.DEFAULT_CLAIM_RESERVE_BYTES, limit / 4),
    ) = LocalBatchQueue(directory, ::encode, ::decode, limit, reserve)

    private fun batch(
        id: String = UUID.randomUUID().toString(),
        cursor: Long = 100,
        owner: JSONObject? = null,
        epoch: Long = 0,
    ): JSONObject = JSONObject()
        .put("owner", owner ?: JSONObject.NULL)
        .put("collectionEpoch", epoch)
        .put("cursorUpdates", JSONObject().put("usage_stats", cursor))
        .put("payload", JSONObject()
            .put("schema_version", 1)
            .put("batch_id", id)
            .put("device_id", device)
            .put("window", JSONObject().put("start_ms", 0).put("end_ms", cursor))
            .put("data", JSONObject().put("usage_stats", JSONObject().put("records",
                JSONArray().put(JSONObject().put("app", "private-app-record"))))))

    private fun owner(serverUrl: String = server, userId: String = user) =
        JSONObject().put("serverUrl", serverUrl).put("userId", userId)

    private fun id(batch: JSONObject): String = batch.getJSONObject("payload").getString("batch_id")

    @Test fun appendSurvivesReopeningAndUploadsInDurableCollectionOrder() {
        val directory = temporary.newFolder()
        val first = batch(cursor = 200)
        val second = batch(cursor = 100)
        queue(directory).apply { append(first); append(second) }

        val reopened = queue(directory)
        assertEquals(id(first), id(reopened.next(server, user, device)!!))
        reopened.remove(id(first))
        assertEquals(id(second), id(reopened.next(server, user, device)!!))
        reopened.remove(id(second))
        assertNull(reopened.next(server, user, device))
        assertEquals(0, reopened.stats().getInt("queuedBatches"))
    }

    @Test fun theCodecCoversRecordsAndOwnershipAndFilenamesContainNoIdentity() {
        val directory = temporary.newFolder()
        val collected = batch(owner = owner())
        queue(directory).append(collected)
        val file = directory.listFiles()!!.single()
        assertFalse(file.readText().contains("private-app-record"))
        assertFalse(file.readText().contains(user))
        assertFalse(file.name.contains(user))
        assertFalse(file.name.contains("collector"))
        assertEquals(id(collected), id(JSONObject(decode(file.readText()))))
    }

    @Test fun anonymousClaimPersistsBeforeUploadAndRetriesKeepTheExactRequestBody() {
        val directory = temporary.newFolder()
        val collected = batch()
        queue(directory).append(collected)
        val claimed = queue(directory).next(server, user, "registered-device")!!
        assertEquals(id(collected), id(claimed))
        assertEquals(user, claimed.getJSONObject("payload").getString("user_id"))
        assertEquals("registered-device", claimed.getJSONObject("payload").getString("device_id"))
        assertEquals(server, claimed.getJSONObject("owner").getString("serverUrl"))

        val originalBody = claimed.getJSONObject("payload").toString()
        val retried = queue(directory).next(server, user, "registered-device")!!
        assertEquals(originalBody, retried.getJSONObject("payload").toString())
        assertNull(queue(directory).next(server, "different-account", "registered-device"))
        assertNull(queue(directory).next("https://another.example", user, "registered-device"))
        assertNull(queue(directory).next(server, user, "different-device"))
    }

    @Test fun accountSwitchSkipsPreviousAccountsAndTheirDataRemainsAvailableOnReturn() {
        val directory = temporary.newFolder()
        val previous = batch(owner = owner(userId = "previous-account"))
        val guest = batch()
        val outbox = queue(directory)
        outbox.append(previous)
        outbox.append(guest)

        assertEquals(id(guest), id(outbox.next(server, user, device)!!))
        outbox.remove(id(guest))
        assertNull(outbox.next(server, user, device))
        assertEquals(id(previous), id(queue(directory).next(server, "previous-account", device)!!))
    }

    @Test fun excludedPermissionBlockedBatchDoesNotPreventOtherBatchesUploading() {
        val directory = temporary.newFolder()
        val first = batch()
        val second = batch()
        val outbox = queue(directory)
        outbox.append(first)
        outbox.append(second)

        assertEquals(id(second), id(outbox.next(server, user, device, setOf(id(first)))!!))
        // Excluding an anonymous record must not claim it for the account.
        assertEquals(id(first), id(outbox.next(server, "another-account", device)!!))
    }

    @Test fun fullQueueRejectsNewCollectionWithoutDroppingSavedRecords() {
        val directory = temporary.newFolder()
        val first = batch()
        val second = batch()
        val originalBytes = encode(first.toString()).toByteArray().size.toLong()
        val reserve = 1024L
        val limit = originalBytes + reserve
        val outbox = queue(directory, limit, reserve)
        outbox.append(first)
        expect<LocalQueueFullException> { outbox.append(second) }

        assertEquals(1, queue(directory, limit, reserve).stats().getInt("queuedBatches"))
        assertEquals(originalBytes, outbox.stats().getLong("queuedBytes"))
        assertEquals(100L, outbox.recoverCursors().getLong("usage_stats"))
        assertEquals(id(first), id(JSONObject(decode(directory.listFiles()!!.single().readText()))))
    }

    @Test fun fullCollectionQueueCanStillClaimAndUploadAnAnonymousBatch() {
        val directory = temporary.newFolder()
        val first = batch()
        val originalBytes = encode(first.toString()).toByteArray().size.toLong()
        val reserve = 1024L
        val limit = originalBytes + reserve
        val outbox = queue(directory, limit, reserve)
        outbox.append(first)
        expect<LocalQueueFullException> { outbox.append(batch()) }

        val claimed = outbox.next(server, user, device)!!
        assertEquals(id(first), id(claimed))
        assertEquals(user, claimed.getJSONObject("payload").getString("user_id"))
        assertTrue(outbox.stats().getLong("queuedBytes") > originalBytes)
        assertTrue(outbox.stats().getLong("queuedBytes") <= limit)

        outbox.remove(id(claimed))
        outbox.append(batch())
        assertEquals(1, outbox.stats().getInt("queuedBatches"))
    }

    @Test fun fullQueueSkipsManyBlockedGuestsWithoutClaimingOrConsumingClaimHeadroom() {
        val directory = temporary.newFolder()
        val blocked = List(32) { batch() }
        val permitted = batch()
        val allBatches = blocked + permitted
        val originalBytes = allBatches.sumOf { encode(it.toString()).toByteArray().size.toLong() }
        val reserve = 1024L
        val outbox = queue(directory, originalBytes + reserve, reserve)
        allBatches.forEach(outbox::append)
        expect<LocalQueueFullException> { outbox.append(batch()) }

        var evaluated = 0
        val selected = outbox.next(server, user, device, canUpload = { projected ->
            evaluated++
            // The engine can evaluate the same owned body that would be uploaded.
            assertEquals(user, projected.getJSONObject("owner").getString("userId"))
            assertEquals(user, projected.getJSONObject("payload").getString("user_id"))
            id(projected) == id(permitted)
        })!!
        assertEquals(33, evaluated)
        assertEquals(id(permitted), id(selected))
        assertTrue(outbox.stats().getLong("queuedBytes") <= originalBytes + reserve)

        val blockedIds = blocked.map(::id).toSet()
        for (file in directory.listFiles()!!) {
            val stored = JSONObject(decode(file.readText()))
            if (id(stored) in blockedIds) {
                assertTrue(stored.isNull("owner"))
                assertFalse(stored.getJSONObject("payload").has("user_id"))
            }
        }
        outbox.remove(id(selected))
        // Skipped guest history can still belong to a later explicitly connected account.
        val later = queue(directory, originalBytes + reserve, reserve)
            .next(server, "another-account", device)!!
        assertEquals(id(blocked.first()), id(later))
        assertEquals("another-account", later.getJSONObject("owner").getString("userId"))
    }

    @Test fun aBlockedOwnedHeadDoesNotPreventAPermittedGuestBatchUploading() {
        val directory = temporary.newFolder()
        val blocked = batch(owner = owner())
        val permitted = batch()
        val outbox = queue(directory)
        outbox.append(blocked)
        outbox.append(permitted)

        val selected = outbox.next(server, user, device, canUpload = { id(it) != id(blocked) })!!
        assertEquals(id(permitted), id(selected))
        outbox.remove(id(selected))
        assertEquals(id(blocked), id(queue(directory).next(server, user, device)!!))
    }

    @Test fun abnormallyLargeClaimCannotExceedStorageCapOrCorruptItsGuestRecord() {
        val directory = temporary.newFolder()
        val first = batch()
        val originalBytes = encode(first.toString()).toByteArray().size.toLong()
        val outbox = queue(directory, originalBytes + 1024, 1024)
        outbox.append(first)
        expect<LocalQueueFullException> { outbox.next(server, "x".repeat(4096), device) }

        val stored = JSONObject(decode(directory.listFiles()!!.single().readText()))
        assertTrue(stored.isNull("owner"))
        assertFalse(stored.getJSONObject("payload").has("user_id"))
        assertEquals(0, outbox.stats().getInt("waitingAccountBatches"))
    }

    @Test fun retryingTheExactAppendIsIdempotentButChangedContentCannotReuseAnId() {
        val directory = temporary.newFolder()
        val collected = batch()
        val outbox = queue(directory)
        outbox.append(collected)
        val reordered = JSONObject()
            .put("payload", JSONObject(collected.getJSONObject("payload").toString()))
            .put("cursorUpdates", JSONObject().put("usage_stats", 100L))
            .put("collectionEpoch", 0)
            .put("owner", JSONObject.NULL)
        outbox.append(reordered)
        assertEquals(1, outbox.stats().getInt("queuedBatches"))

        val changed = JSONObject(collected.toString())
        changed.getJSONObject("payload").put("schema_version", 2)
        expect<IllegalArgumentException> { outbox.append(changed) }
        assertEquals(1, queue(directory).stats().getInt("queuedBatches"))
    }

    @Test fun appendRecoveryOnlyAdvancesCompletedSourcesInTheCurrentCollectionEpoch() {
        val directory = temporary.newFolder()
        val outbox = queue(directory)
        outbox.append(batch(cursor = 200, epoch = 0))
        outbox.append(batch(cursor = 75, epoch = 1).apply {
            getJSONObject("cursorUpdates").put("calendar_events", 60)
        })
        outbox.append(batch(cursor = 100, epoch = 1))

        val recovered = queue(directory).recoverCursors(collectionEpoch = 1)
        assertEquals(100L, recovered.getLong("usage_stats"))
        assertEquals(60L, recovered.getLong("calendar_events"))
        assertFalse(recovered.has("health_steps"))
        assertEquals(200L, outbox.recoverCursors().getLong("usage_stats"))
    }

    @Test fun idempotentAppendAcceptsNumbersNormalizedByJsonSerialization() {
        val directory = temporary.newFolder()
        val collected = batch().apply {
            getJSONObject("payload").getJSONObject("data").put("measurements",
                JSONArray().put(JSONObject().put("count", 12.0).put("samples", JSONArray().put(1.0))))
        }
        val outbox = queue(directory)
        outbox.append(collected)
        queue(directory).append(collected)
        assertEquals(1, outbox.stats().getInt("queuedBatches"))
    }

    @Test fun repeatedStatusAndCursorRecoveryUseOnlyCachedMetadata() {
        val directory = temporary.newFolder()
        var decodeCalls = 0
        val outbox = LocalBatchQueue(directory, ::encode, { value -> decodeCalls++; decode(value) })
        val collected = batch()
        outbox.append(collected)
        repeat(3) { outbox.stats(server, user); outbox.recoverCursors() }
        assertEquals(0, decodeCalls)

        val reopened = LocalBatchQueue(directory, ::encode, { value -> decodeCalls++; decode(value) })
        repeat(3) { reopened.stats(server, user); reopened.recoverCursors() }
        assertEquals(1, decodeCalls)
        reopened.next(server, user, device)
        val beforeCachedPoll = decodeCalls
        assertEquals(0, reopened.stats(server, user).getInt("waitingAccountBatches"))
        reopened.recoverCursors()
        assertEquals(beforeCachedPoll, decodeCalls)
    }

    @Test fun statisticsDistinguishOtherAccountsFromUnownedAndCurrentAccountBatches() {
        val directory = temporary.newFolder()
        val outbox = queue(directory)
        outbox.append(batch())
        outbox.append(batch(owner = owner()))
        outbox.append(batch(owner = owner(userId = "other-account")))
        outbox.append(batch(owner = owner(serverUrl = "https://another.example")))

        val connected = outbox.stats(server, user)
        assertEquals(4, connected.getInt("queuedBatches"))
        assertEquals(2, connected.getInt("waitingAccountBatches"))
        assertEquals(3, outbox.stats().getInt("waitingAccountBatches"))
        assertEquals(directory.listFiles()!!.sumOf { it.length() }, connected.getLong("queuedBytes"))
        assertEquals(LocalBatchQueue.DEFAULT_LIMIT_BYTES, connected.getLong("storageLimitBytes"))
    }

    @Test fun uncommittedTemporaryFilesAreRecoveredWithoutChangingCommittedRecords() {
        val directory = temporary.newFolder()
        val collected = batch()
        queue(directory).append(collected)
        val committed = directory.listFiles()!!.single()
        val temp = File(directory, committed.name + ".tmp")
        temp.writeText("incomplete bytes")

        val reopened = queue(directory)
        assertFalse(temp.exists())
        assertNotNull(reopened.next(server, user, device))
        assertEquals(1, reopened.stats().getInt("queuedBatches"))
    }

    @Test fun unsafeBatchIdsAndMalformedOwnershipCannotEnterTheQueue() {
        val directory = temporary.newFolder()
        val outbox = queue(directory)
        expect<IllegalArgumentException> { outbox.append(batch(id = "../outside")) }
        expect<IllegalArgumentException> { outbox.append(batch(owner = JSONObject().put("userId", user))) }
        expect<org.json.JSONException> { outbox.append(batch().put("owner", "some-account")) }
        assertEquals(0, outbox.stats().getInt("queuedBatches"))
    }

    @Test fun removingAnAlreadyAcknowledgedBatchIsIdempotent() {
        val directory = temporary.newFolder()
        val collected = batch()
        val outbox = queue(directory)
        outbox.append(collected)
        outbox.remove(id(collected))
        outbox.remove(id(collected))
        assertEquals(0, queue(directory).stats().getInt("queuedBatches"))
    }

    private inline fun <reified T : Throwable> expect(action: () -> Unit) {
        try {
            action()
            throw AssertionError("Expected ${T::class.java.simpleName}")
        } catch (error: Throwable) {
            if (error !is T) throw error
        }
    }
}
