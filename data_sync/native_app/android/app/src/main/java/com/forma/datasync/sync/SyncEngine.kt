package com.forma.datasync.sync

import android.content.Context
import android.os.Build
import android.webkit.CookieManager
import com.forma.datasync.collectors.AndroidCollector
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.UUID

/** Collect into an encrypted local queue first. Authentication controls only its upload. */
class SyncEngine(context: Context) {
    private val appContext = context.applicationContext
    private val store = SecureStore(appContext)
    private val collector = AndroidCollector(appContext)

    suspend fun restoreSession(): JSONObject? {
        val snapshot = lock.withLock {
            val state = localState()
            restoreSchedules(state)
            state.takeIf { it.optString("token").isNotBlank() }
        } ?: return null
        var refreshedUser: JSONObject? = null
        var unauthorized = false
        // Account validation must not hold the collection mutex while an offline request times out.
        try {
            val result = api(snapshot).request("/api/native/me")
            val user = result.optJSONObject("user") ?: result
            if (user.has("id")) refreshedUser = user
        } catch (error: ApiException) {
            unauthorized = error.status == 401
        } catch (_: IOException) {
            // An offline device continues collection with its cached account identity.
        }
        return lock.withLock {
            var current = store.read()
            if (current.optString("token") == snapshot.optString("token") &&
                current.optString("serverUrl") == snapshot.optString("serverUrl")) {
                if (unauthorized) {
                    invalidateAuth()
                    return@withLock null
                }
                if (refreshedUser != null) current = store.update { it.put("user", refreshedUser) }
            }
            if (current.optString("token").isNotBlank()) session(current) else null
        }
    }

    suspend fun login(serverUrl: String, email: String, password: String): JSONObject = lock.withLock {
        require(email.trim().isNotBlank() && password.isNotEmpty()) { "Enter your email and password." }
        localState()
        val url = NativeApi.normalizeServerUrl(serverUrl)
        val result = NativeApi(url).request("/api/native/login", JSONObject()
            .put("email", email.trim()).put("password", password))
        val user = result.getJSONObject("user")
        val token = result.getString("token")
        require(token.isNotBlank()) { "The server returned an invalid session." }
        SyncScheduler.cancel(appContext)
        val current = store.update {
            CollectionStatePolicy.selectAccount(it, url, user, token)
            it.put("expiresAt", result.optString("expires_at"))
            it.remove("lastError")
        }
        restoreSchedules(current)
        session(current)
    }

    /** Permission/consent onboarding is available without an account or a server. */
    suspend fun startCollection(historyDays: Int): JSONObject = lock.withLock {
        localState()
        requireUsageAccess()
        store.update { CollectionStatePolicy.configureCollection(it, historyDays, System.currentTimeMillis()) }
        CollectionScheduler.schedule(appContext)
        CollectionScheduler.enqueue(appContext)
        status()
    }

    /** This explicit connection step authorizes upload of the local queue to the signed-in account. */
    suspend fun completeOnboarding(historyDays: Int): JSONObject = lock.withLock {
        require(historyDays in 1..365) { "History must be between 1 and 365 days." }
        localState()
        val state = authenticatedState()
        requireUsageAccess()
        try {
            api(state).request("/api/native/devices", JSONObject()
                .put("device_id", state.getString("deviceId"))
                .put("platform", "android")
                .put("name", "${Build.MANUFACTURER} ${Build.MODEL}")
                .put("sync_interval_minutes", 60).put("consent_version", "1").put("history_days", historyDays))
        } catch (error: ApiException) {
            if (error.status == 401) invalidateAuth()
            throw error
        }
        val current = store.update {
            CollectionStatePolicy.configureCollection(it, historyDays, System.currentTimeMillis())
            it.put("connected", true).put("enabled", true).remove("lastError")
        }
        restoreSchedules(current)
        CollectionScheduler.enqueue(appContext)
        SyncScheduler.enqueue(appContext)
        session(current)
    }

    fun status(): JSONObject {
        val state = localState()
        val stats = store.localStats(state.optString("serverUrl").takeIf { it.isNotBlank() },
            state.optJSONObject("user")?.optString("id"))
        return stats
            .put("enabled", CollectionStatePolicy.canUpload(state))
            .put("collectionEnabled", CollectionStatePolicy.canCollect(state))
            .put("onboarded", state.optBoolean("onboarded"))
            .put("connected", state.optBoolean("connected") && state.optString("token").isNotBlank())
            .put("authRequired", state.has("user") && state.optString("token").isBlank())
            .put("lastSyncAt", state.opt("lastSyncAt") ?: JSONObject.NULL)
            .put("lastCollectedAt", state.opt("lastCollectedAt") ?: JSONObject.NULL)
            .put("lastError", state.opt("lastError") ?: JSONObject.NULL)
            .put("collectionError", state.opt("collectionError") ?: JSONObject.NULL)
            .put("pending", stats.optInt("queuedBatches") > 0 || state.optBoolean("collectionBacklog"))
            .put("historyDays", state.optInt("historyDays", 30))
    }

    /** Pause only uploads; the local collection schedule remains active. */
    fun pause(): JSONObject {
        store.update { it.put("enabled", false) }
        activeSync?.cancel(CancellationException("Sync paused."))
        SyncScheduler.cancel(appContext)
        SyncNotifications.clear(appContext)
        return status()
    }

    fun resume(): JSONObject {
        val state = authenticatedState()
        check(state.optBoolean("connected")) { "Connect this account before enabling sync." }
        store.update { it.put("enabled", true).remove("lastError") }
        SyncScheduler.schedule(appContext)
        SyncScheduler.enqueue(appContext)
        return status()
    }

    fun pauseCollection(): JSONObject {
        store.update { it.put("collectionEnabled", false) }
        activeCollection?.cancel(CancellationException("Collection paused."))
        CollectionScheduler.cancel(appContext)
        return status()
    }

    fun resumeCollection(): JSONObject {
        val state = localState()
        check(state.optBoolean("onboarded")) { "Complete permission setup before enabling collection." }
        store.update { it.put("collectionEnabled", true).remove("collectionError") }
        CollectionScheduler.schedule(appContext)
        CollectionScheduler.enqueue(appContext)
        return status()
    }

    suspend fun logout() {
        localState()
        val old = store.read()
        // Remove upload authority immediately; local cursors, grants and queued records survive logout.
        store.update { CollectionStatePolicy.disconnect(it) }
        activeSync?.cancel(CancellationException("Signed out."))
        SyncScheduler.cancel(appContext)
        SyncNotifications.clear(appContext)
        lock.withLock {
            try {
                if (old.optString("token").isNotBlank()) api(old).request("/api/native/logout", JSONObject())
            } catch (_: Exception) {
                // Signing out locally must work while offline.
            } finally {
                withContext(Dispatchers.Main) {
                    CookieManager.getInstance().removeAllCookies(null)
                    CookieManager.getInstance().flush()
                }
            }
        }
    }

    /** Source cursors advance only after their batch is durably encrypted on this device. */
    suspend fun collect(background: Boolean): Boolean = lock.withLock collectLocked@ {
        var initial = localState()
        if (!CollectionStatePolicy.canCollect(initial)) return@collectLocked false
        // Recover a crash between the durable append and the cursor-state commit.
        val recovered = store.recoverLocalCursors(initial.optLong("collectionEpoch"))
        initial = store.update { CollectionStatePolicy.mergeCursors(it, recovered) }
        activeCollection = currentCoroutineContext()[Job]
        val now = System.currentTimeMillis()
        val deadline = now + 90_000L
        val historyStart = initial.optLong("historyStart", now - initial.optInt("historyDays", 30) * SyncPlanner.DAY_MS)
        try {
            // Probe statuses independently. Revoked or background-restricted sections cannot advance.
            val probe = collector.availability(background)
            val permissions = PermissionStatus.read(appContext)
            val historyGranted = permissions.getJSONObject("health").optBoolean("history_granted")
            store.update {
                if (historyGranted && !it.optBoolean("healthHistoryGranted")) {
                    val cursors = it.optJSONObject("cursors") ?: JSONObject()
                    cursors.keys().asSequence().toList().filter { source -> source.startsWith("health_") && source != "health_status" }
                        .forEach { source -> cursors.put(source, historyStart) }
                    it.put("cursors", cursors).put("collectionEpoch", it.optLong("collectionEpoch") + 1)
                }
                it.put("healthHistoryGranted", historyGranted)
            }
            val available = probe.keys().asSequence().filter { key ->
                isComplete(probe.optJSONObject(key))
            }.toMutableList()
            val snapshots = available.filter { probe.getJSONObject(it).optString("mode") == "snapshot" }.toMutableSet()
            val historical = available.filterNot { it in snapshots || it == "health_status" }.toMutableList()
            val failedSources = mutableSetOf<String>()
            fun excludeFailedSource(source: String) {
                available.remove(source)
                historical.remove(source)
                snapshots.remove(source)
                failedSources.add(source)
            }
            fun shrinkOrExclude(duration: Long, sources: Collection<String>) {
                val reduced = SyncPlanner.smallerChunk(duration)
                if (reduced != null) store.update { it.put("chunkMs", reduced) }
                else {
                    check(sources.isNotEmpty()) { "A minimal metadata batch exceeds the server limit." }
                    sources.forEach { source -> excludeFailedSource(source) }
                }
            }
            fun largestSource(data: JSONObject): List<String> = data.keys().asSequence()
                .filter { it != "source_status" }
                .maxByOrNull { data.getJSONObject(it).toString().toByteArray(Charsets.UTF_8).size }
                ?.let { listOf(it) } ?: emptyList()
            val current = store.read()
            if (now - current.optLong("lastRollingRefreshAttemptAt") >= 60 * 60 * 1000) {
                val cursors = current.optJSONObject("cursors") ?: JSONObject()
                val refresh = historical.filter { source ->
                    (source.startsWith("health_") || source == "calendar_events") &&
                        cursors.optLong(source, historyStart) >= now - 60 * 60 * 1000
                }
                if (refresh.isNotEmpty()) store.update {
                    val latest = it.optJSONObject("cursors") ?: JSONObject()
                    refresh.forEach { source -> latest.put(source, maxOf(historyStart, now - 2 * SyncPlanner.DAY_MS)) }
                    it.put("cursors", latest).put("lastRollingRefreshAttemptAt", now)
                        .put("collectionEpoch", it.optLong("collectionEpoch") + 1)
                }
            }
            var count = 0
            while (count < MAX_BATCHES && System.currentTimeMillis() < deadline) {
                currentCoroutineContext().ensureActive()
                val state = store.read()
                if (!CollectionStatePolicy.canCollect(state)) return@collectLocked false
                val cursors = state.optJSONObject("cursors") ?: JSONObject()
                val chunk = state.optLong("chunkMs", SyncPlanner.DAY_MS)
                val planned = SyncPlanner.nextWindow(historical.map { key ->
                    maxOf(historyStart, cursors.optLong(key, historyStart))
                }, now, chunk)
                val snapshotDue = snapshots.any { cursors.optLong(it, 0) < now }
                if (planned == null && !snapshotDue) {
                    store.update {
                        it.put("collectionBacklog", failedSources.isNotEmpty())
                        if (failedSources.isEmpty()) it.remove("collectionError")
                        else it.put("collectionError", "Some permitted sources could not complete collection. Other available sources are stored locally; open the app to retry.")
                    }
                    return@collectLocked false
                }
                val window = planned ?: SyncPlanner.Window(maxOf(historyStart, now - 60 * 60 * 1000), now)
                val selected = available.filter { key ->
                    if (key in snapshots || key == "health_status") window.end == now
                    else maxOf(historyStart, cursors.optLong(key, historyStart)) <= window.start
                }.toSet()
                val data = collector.collect(window.start, window.end, background, selected)
                val sourceSummary = JSONArray()
                for (source in probe.keys().asSequence().toList()) {
                    val section = data.optJSONObject(source) ?: probe.getJSONObject(source)
                    val summary = JSONObject().put("source", source)
                        .put("status", section.optString("status"))
                        .put("complete", section.optBoolean("complete", true))
                        .put("collected", data.has(source))
                    listOf("mode", "reason", "error_type", "retry_hint").forEach { detail ->
                        if (section.has(detail)) summary.put(detail, section.get(detail))
                    }
                    sourceSummary.put(summary)
                }
                val overflowing = data.keys().asSequence().filter { source ->
                    val section = data.getJSONObject(source)
                    section.optString("retry_hint") == "split_window" ||
                        section.optString("reason") == "window_exceeds_record_limit"
                }.toList()
                if (overflowing.isNotEmpty()) {
                    shrinkOrExclude(window.end - window.start, overflowing)
                    continue
                }
                val updates = JSONObject()
                var unavailableThisWindow = false
                for (key in data.keys().asSequence().toList()) {
                    val section = data.optJSONObject(key)
                    val sourceCursor = maxOf(historyStart, cursors.optLong(key, historyStart))
                    val snapshot = section?.optString("mode") == "snapshot" || key == "health_status"
                    if (!isComplete(section) || (snapshot && window.end != now) ||
                        (!snapshot && sourceCursor > window.start)) {
                        data.remove(key)
                        if (!isComplete(section)) {
                            unavailableThisWindow = true
                            available.remove(key)
                            historical.remove(key)
                            snapshots.remove(key)
                            if (section?.optString("status") == "error" || section?.optBoolean("complete", true) == false) failedSources.add(key)
                        }
                    } else {
                        updates.put(key, window.end)
                    }
                }
                if (updates.length() == 0) {
                    if (unavailableThisWindow) continue
                    store.update { it.put("collectionError", "No granted data source could complete this collection. Open the app to review permissions.") }
                    return@collectLocked false
                }
                data.put("source_status", JSONObject().put("status", "ok").put("records", sourceSummary).put("complete", true)
                    .put("rolling_health_calendar_lookback_hours", 48))
                val payload = JSONObject()
                    .put("schema_version", 1).put("batch_id", UUID.randomUUID().toString())
                    .put("device_id", state.getString("deviceId"))
                    .put("window", JSONObject().put("start_ms", window.start).put("end_ms", window.end))
                    .put("collected_at_ms", System.currentTimeMillis())
                    .put("permissions", PermissionStatus.read(appContext)).put("data", data)
                // Logout can arrive while Android is serving a slow read. Determine ownership
                // from the current state immediately before durable append, rather than the old probe.
                val collectionState = store.read()
                val owner = if (collectionState.optBoolean("connected") && collectionState.has("user")) JSONObject()
                    .put("serverUrl", collectionState.getString("serverUrl"))
                    .put("userId", collectionState.getJSONObject("user").getString("id")) else null
                if (owner != null) payload.put("device_id", collectionState.getString("deviceId"))
                if (owner != null) payload.put("user_id", owner.getString("userId"))
                if (payload.toString().toByteArray(Charsets.UTF_8).size > MAX_PAYLOAD_BYTES) {
                    shrinkOrExclude(window.end - window.start, largestSource(data))
                    continue
                }
                val batch = JSONObject().put("payload", payload).put("cursorUpdates", updates)
                    .put("collectionEpoch", state.optLong("collectionEpoch"))
                if (owner != null) batch.put("owner", owner).put("userId", owner.getString("userId"))
                currentCoroutineContext().ensureActive()
                if (!CollectionStatePolicy.canCollect(store.read())) return@collectLocked false
                store.appendLocal(batch)
                store.update {
                    CollectionStatePolicy.mergeCursors(it, updates)
                    it.put("lastCollectedAt", payload.getLong("collected_at_ms")).put("collectionBacklog", true)
                    if (failedSources.isEmpty()) it.remove("collectionError")
                    else it.put("collectionError", "Some permitted sources could not complete collection. Other available sources continue collecting locally.")
                }
                count++
            }
            store.update { it.put("collectionBacklog", true) }
            true
        } catch (error: CancellationException) {
            throw error
        } catch (error: LocalQueueFullException) {
            store.update { it.put("collectionError", "Local storage is full. Existing data is retained; connect and sync to make room for collection.") }
            false
        } catch (error: Exception) {
            store.update { it.put("collectionError", "Collection could not complete. Existing local data is retained; review permissions and device storage.") }
            throw error
        } finally {
            activeCollection = null
        }
    }

    /** Upload already-collected batches. This method never needs collection permissions for new reads. */
    @Suppress("UNUSED_PARAMETER")
    suspend fun sync(background: Boolean): Boolean = lock.withLock {
        val initial = localState()
        if (!CollectionStatePolicy.canUpload(initial)) return@withLock false
        activeSync = currentCoroutineContext()[Job]
        val deadline = System.currentTimeMillis() + 90_000L
        val skipped = mutableSetOf<String>()
        var blockedError: String? = null
        var count = 0
        try {
            while (count < MAX_BATCHES && System.currentTimeMillis() < deadline) {
                currentCoroutineContext().ensureActive()
                val state = store.read()
                if (!CollectionStatePolicy.canUpload(state)) return@withLock false
                val candidateAccess = collector.availability(background = false)
                // Evaluate an anonymous batch's projected owner before persisting that claim.
                // Revoked-source batches remain anonymous and cannot consume the claim reserve.
                val batch = store.nextLocal(state.getString("serverUrl"), state.getJSONObject("user").getString("id"),
                    state.getString("deviceId"), skipped) { candidate ->
                    val decision = SyncBatchPolicy.decide(candidate, store.read(), candidateAccess)
                    if (decision == SyncBatchPolicy.Decision.BLOCK) {
                        skipped.add(candidate.getJSONObject("payload").getString("batch_id"))
                        blockedError = "Some stored batches are waiting for their Android permissions to be granted again. Their data remains on this device."
                    }
                    decision == SyncBatchPolicy.Decision.UPLOAD
                }
                if (batch == null) {
                    store.update {
                        if (blockedError != null) it.put("lastError", blockedError)
                        else it.remove("lastError")
                    }
                    return@withLock false
                }
                val payload = batch.getJSONObject("payload")
                val id = payload.getString("batch_id")
                // Revocation prevents transmitting previously collected data, without deleting local history.
                val freshAccess = collector.availability(background = false)
                currentCoroutineContext().ensureActive()
                when (SyncBatchPolicy.decide(batch, store.read(), freshAccess)) {
                    SyncBatchPolicy.Decision.STOP -> return@withLock false
                    SyncBatchPolicy.Decision.BLOCK -> {
                        skipped.add(id)
                        blockedError = "Some stored batches are waiting for their Android permissions to be granted again. Their data remains on this device."
                        continue
                    }
                    SyncBatchPolicy.Decision.UPLOAD -> Unit
                }
                val uploadState = store.read()
                if (!CollectionStatePolicy.canUpload(uploadState)) return@withLock false
                try {
                    val ack = api(uploadState).request("/api/native/batches", payload)
                    check(ack.optBoolean("accepted") && ack.optString("batch_id") == id) {
                        "The server did not acknowledge this batch."
                    }
                } catch (error: ApiException) {
                    if (error.status != 413) throw error
                    // A server-specific smaller limit must not discard data or mutate an idempotent batch.
                    skipped.add(id)
                    blockedError = "The server cannot accept a stored batch of this size. It remains on this device."
                    store.update { it.put("lastError", blockedError) }
                    continue
                }
                // Persist acknowledgment before deletion; a crash can safely replay the exact UUID/body.
                store.update { it.put("lastSyncAt", System.currentTimeMillis()) }
                store.removeLocal(id)
                count++
            }
            store.update { if (blockedError == null) it.remove("lastError") else it.put("lastError", blockedError) }
            true
        } catch (error: CancellationException) {
            throw error
        } catch (error: ApiException) {
            if (error.status == 401) {
                invalidateAuth()
                false
            } else {
                store.update { it.put("lastError", error.message) }
                if (error.status in 400..499 && error.status != 429) {
                    store.update { it.put("enabled", false) }
                    SyncScheduler.cancel(appContext)
                    false
                } else throw error
            }
        } catch (error: Exception) {
            store.update { it.put("lastError", "Sync could not complete. Stored data is retained while the server is unavailable.") }
            throw error
        } finally {
            activeSync = null
        }
    }

    private fun localState(): JSONObject = synchronized(migrationLock) {
        var state = store.read()
        if (!state.has("collectionVersion")) {
            state = store.update {
                if (!it.has("collectionVersion")) {
                    if (it.optBoolean("onboarded")) {
                        it.put("collectionEnabled", true).put("connected", true)
                    }
                    it.put("collectionVersion", 2)
                    CollectionStatePolicy.rememberDevice(it)
                }
            }
        }
        // Upgrades preserve the old encrypted retry payload and its original account/device association.
        store.readPending()?.let { pending ->
            val legacy = JSONObject(pending.toString()).put("collectionEpoch", state.optLong("collectionEpoch"))
            val userId = pending.optString("userId", pending.getJSONObject("payload").optString("user_id"))
            if (userId.isNotBlank() && state.optString("serverUrl").isNotBlank()) {
                legacy.put("owner", JSONObject().put("serverUrl", state.getString("serverUrl")).put("userId", userId))
            }
            store.appendLocal(legacy)
            state = store.update { CollectionStatePolicy.mergeCursors(it, legacy.optJSONObject("cursorUpdates") ?: JSONObject()) }
            store.clearPending()
        }
        state
    }

    private fun restoreSchedules(state: JSONObject) {
        if (CollectionStatePolicy.canCollect(state)) CollectionScheduler.schedule(appContext)
        if (CollectionStatePolicy.canUpload(state)) {
            SyncScheduler.schedule(appContext)
            SyncScheduler.enqueue(appContext)
        }
    }

    private suspend fun requireUsageAccess() {
        require(PermissionStatus.read(appContext).optBoolean("usageAccess")) { "Grant Usage Access before completing permission setup." }
    }

    private fun invalidateAuth() {
        store.update { it.remove("token"); it.put("lastError", "Session expired. Collection continues locally; sign in again to resume syncing.") }
        SyncScheduler.cancel(appContext)
    }

    private fun authenticatedState(): JSONObject = store.read().also {
        check(it.optString("token").isNotBlank()) { "Sign in to continue." }
    }

    private fun api(state: JSONObject) = NativeApi(state.getString("serverUrl"), state.getString("token"))
    private fun session(state: JSONObject) = JSONObject()
        .put("serverUrl", state.getString("serverUrl")).put("token", state.getString("token"))
        .put("user", state.getJSONObject("user")).put("onboarded", state.optBoolean("onboarded"))

    private fun isComplete(section: JSONObject?) = section?.optString("status") == "ok" && section.optBoolean("complete", true)

    companion object {
        private val lock = Mutex()
        private val migrationLock = Any()
        @Volatile private var activeSync: Job? = null
        @Volatile private var activeCollection: Job? = null
        private const val MAX_BATCHES = 8
        private const val MAX_PAYLOAD_BYTES = 2 * 1024 * 1024
    }
}
