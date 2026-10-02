package com.forma.datasync.sync

import android.content.Context
import android.os.Build
import android.webkit.CookieManager
import com.forma.datasync.collectors.AndroidCollector
import com.forma.datasync.collectors.HealthPermissions
import org.json.JSONArray
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.util.UUID

class SyncEngine(context: Context) {
    private val appContext = context.applicationContext
    private val store = SecureStore(appContext)
    private val collector = AndroidCollector(appContext)

    suspend fun restoreSession(): JSONObject? = withContext(Dispatchers.IO) {
        val state = store.read()
        if (!state.has("token")) return@withContext null
        try {
            val result = api(state).request("/api/native/me")
            val user = result.optJSONObject("user") ?: result
            if (user.has("id")) store.update { it.put("user", user) }
        } catch (error: ApiException) {
            if (error.status == 401) {
                invalidateAuth()
                return@withContext null
            }
            // Keep an existing local session while offline or the server is unavailable.
        } catch (_: IOException) {
            // Cached session permits offline dashboard/onboarding; uploads remain queued.
        }
        val current = store.read()
        if (current.has("token")) session(current) else null
    }

    suspend fun login(serverUrl: String, email: String, password: String): JSONObject = lock.withLock {
        require(email.trim().isNotBlank() && password.isNotEmpty()) { "Enter your email and password." }
        val url = NativeApi.normalizeServerUrl(serverUrl)
        val result = NativeApi(url).request("/api/native/login", JSONObject()
            .put("email", email.trim()).put("password", password))
        val user = result.getJSONObject("user")
        val token = result.getString("token")
        require(token.isNotBlank()) { "The server returned an invalid session." }
        val old = store.read()
        val sameAccount = old.optString("serverUrl") == url &&
            old.optJSONObject("user")?.optString("id") == user.optString("id")
        if (!sameAccount) {
            SyncScheduler.cancel(appContext)
            store.clear()
            HealthPermissions.clearLocalMetadata(appContext)
        }
        val current = store.update {
            it.put("serverUrl", url).put("token", token).put("user", user)
            it.put("expiresAt", result.optString("expires_at"))
            if (!it.has("deviceId")) it.put("deviceId", UUID.randomUUID().toString())
            it.remove("lastError")
        }
        if (current.optBoolean("onboarded") && current.optBoolean("enabled")) {
            SyncScheduler.schedule(appContext)
        }
        session(current)
    }

    suspend fun completeOnboarding(historyDays: Int): JSONObject = lock.withLock {
        require(historyDays in 1..365) { "History must be between 1 and 365 days." }
        val state = authenticatedState()
        require(PermissionStatus.read(appContext).optBoolean("usageAccess")) {
            "Grant Usage Access before completing onboarding."
        }
        api(state).request("/api/native/devices", JSONObject()
            .put("device_id", state.getString("deviceId"))
            .put("platform", "android")
            .put("name", "${Build.MANUFACTURER} ${Build.MODEL}")
            .put("sync_interval_minutes", 60)
            .put("consent_version", "1")
            .put("history_days", historyDays))
        val requestedStart = System.currentTimeMillis() - historyDays * SyncPlanner.DAY_MS
        if (state.has("historyStart") && requestedStart < state.getLong("historyStart")) {
            // Keep an already-collected retry payload, but prevent its ack from skipping the new backfill.
            store.readPending()?.let { pending ->
                pending.put("cursorUpdates", JSONObject())
                store.writePending(pending)
            }
        }
        val current = store.update {
            it.put("historyDays", historyDays).put("onboarded", true).put("enabled", true)
            if (!it.has("historyStart") || requestedStart < it.getLong("historyStart")) {
                it.put("historyStart", requestedStart)
                val cursors = it.optJSONObject("cursors") ?: JSONObject()
                cursors.keys().asSequence().toList().forEach { source -> cursors.put(source, requestedStart) }
                it.put("cursors", cursors)
            }
            it.remove("lastError")
        }
        SyncScheduler.schedule(appContext)
        SyncScheduler.enqueue(appContext)
        session(current)
    }

    fun status(): JSONObject {
        val state = store.read()
        return JSONObject()
            .put("enabled", state.optBoolean("enabled") && state.has("token"))
            .put("authRequired", state.has("user") && !state.has("token"))
            .put("lastSyncAt", state.opt("lastSyncAt") ?: JSONObject.NULL)
            .put("lastError", state.opt("lastError") ?: JSONObject.NULL)
            .put("pending", store.readPending() != null || state.optBoolean("backlog"))
            .put("historyDays", state.optInt("historyDays", 30))
    }

    fun pause(): JSONObject {
        store.update { it.put("enabled", false) }
        SyncScheduler.cancel(appContext)
        SyncNotifications.clear(appContext)
        return status()
    }

    fun resume(): JSONObject {
        val state = authenticatedState()
        check(state.optBoolean("onboarded")) { "Complete onboarding before enabling sync." }
        store.update { it.put("enabled", true).remove("lastError") }
        SyncScheduler.schedule(appContext)
        SyncScheduler.enqueue(appContext)
        return status()
    }

    suspend fun logout() {
        // Stop new upload attempts immediately, including before waiting for an in-flight request.
        val old = store.update { it.put("enabled", false) }
        SyncScheduler.cancel(appContext)
        SyncNotifications.clear(appContext)
        lock.withLock {
            try {
                if (old.has("token")) api(old).request("/api/native/logout", JSONObject())
            } catch (_: Exception) {
                // Local logout must always finish, even if the server is unreachable.
            } finally {
                store.clear()
                HealthPermissions.clearLocalMetadata(appContext)
                withContext(Dispatchers.Main) {
                    CookieManager.getInstance().removeAllCookies(null)
                    CookieManager.getInstance().flush()
                }
            }
        }
    }

    /** Per-source cursors advance only after the server acknowledges a persisted UUID batch. */
    suspend fun sync(background: Boolean): Boolean = lock.withLock {
        val initial = store.read()
        if (!initial.optBoolean("enabled") || !initial.has("token")) return@withLock false
        val now = System.currentTimeMillis()
        val deadline = now + 90_000L
        val historyStart = initial.optLong("historyStart", now - initial.optInt("historyDays", 30) * SyncPlanner.DAY_MS)
        try {
            // Probe statuses independently. Revoked or background-restricted sections cannot advance.
            val probe = collector.availability(background)
            val uploadPermissions = if (background) collector.availability(background = false) else probe
            val authorized = uploadPermissions.keys().asSequence().filter { key ->
                isComplete(uploadPermissions.optJSONObject(key))
            }.toSet()
            val permissions = PermissionStatus.read(appContext)
            val historyGranted = permissions.getJSONObject("health").optBoolean("history_granted")
            if (historyGranted && !initial.optBoolean("healthHistoryGranted")) {
                store.readPending()?.let { pending ->
                    val updates = pending.getJSONObject("cursorUpdates")
                    updates.keys().asSequence().toList().filter { source -> source.startsWith("health_") }
                        .forEach { source -> updates.remove(source) }
                    store.writePending(pending)
                }
            }
            store.update {
                if (historyGranted && !it.optBoolean("healthHistoryGranted")) {
                    val cursors = it.optJSONObject("cursors") ?: JSONObject()
                    cursors.keys().asSequence().toList().filter { source -> source.startsWith("health_") && source != "health_status" }
                        .forEach { source -> cursors.put(source, historyStart) }
                    it.put("cursors", cursors)
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
            if (store.readPending() == null && now - current.optLong("lastRollingRefreshAttemptAt") >= 60 * 60 * 1000) {
                val cursors = current.optJSONObject("cursors") ?: JSONObject()
                val refresh = historical.filter { source ->
                    (source.startsWith("health_") || source == "calendar_events") &&
                        cursors.optLong(source, historyStart) >= now - 60 * 60 * 1000
                }
                if (refresh.isNotEmpty()) store.update {
                    val latest = it.optJSONObject("cursors") ?: JSONObject()
                    refresh.forEach { source -> latest.put(source, maxOf(historyStart, now - 2 * SyncPlanner.DAY_MS)) }
                    it.put("cursors", latest).put("lastRollingRefreshAttemptAt", now)
                }
            }
            var count = 0
            while (count < MAX_BATCHES && System.currentTimeMillis() < deadline) {
                currentCoroutineContext().ensureActive()
                val state = store.read()
                if (!state.optBoolean("enabled") || !state.has("token")) return@withLock false
                val cursors = state.optJSONObject("cursors") ?: JSONObject()
                var pending = store.readPending()
                if (pending != null) {
                    val exportedSources = pending.getJSONObject("payload").getJSONObject("data").keys().asSequence()
                        .filter { it != "source_status" }.toList()
                    val permitted = exportedSources.all { it in authorized }
                    if (!permitted || pending.optString("userId") != state.getJSONObject("user").getString("id")) {
                        // A revoked permission invalidates the local pending export; recollect allowed sources.
                        store.clearPending()
                        pending = null
                    }
                }
                if (pending == null) {
                    val chunk = state.optLong("chunkMs", SyncPlanner.DAY_MS)
                    val planned = SyncPlanner.nextWindow(historical.map { key ->
                        maxOf(historyStart, cursors.optLong(key, historyStart))
                    }, now, chunk)
                    val snapshotDue = snapshots.any { cursors.optLong(it, 0) < now }
                    if (planned == null && !snapshotDue) {
                        store.update {
                            it.put("backlog", failedSources.isNotEmpty())
                            if (failedSources.isEmpty()) it.remove("lastError")
                            else it.put("lastError", "Some permitted sources could not complete collection. Other available sources are synced; open the app to retry.")
                        }
                        return@withLock false
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
                        store.update { it.put("lastError", "No granted data source could complete this collection. Open the app to review permissions.") }
                        return@withLock false
                    }
                    data.put("source_status", JSONObject().put("status", "ok").put("records", sourceSummary).put("complete", true)
                        .put("rolling_health_calendar_lookback_hours", 48))
                    val payload = JSONObject()
                        .put("schema_version", 1)
                        .put("batch_id", UUID.randomUUID().toString())
                        .put("device_id", state.getString("deviceId"))
                        .put("user_id", state.getJSONObject("user").getString("id"))
                        .put("window", JSONObject().put("start_ms", window.start).put("end_ms", window.end))
                        .put("collected_at_ms", System.currentTimeMillis())
                        .put("permissions", PermissionStatus.read(appContext))
                        .put("data", data)
                    if (payload.toString().toByteArray(Charsets.UTF_8).size > MAX_PAYLOAD_BYTES) {
                        shrinkOrExclude(window.end - window.start, largestSource(data))
                        continue
                    }
                    pending = JSONObject().put("payload", payload).put("cursorUpdates", updates)
                        .put("userId", state.getJSONObject("user").getString("id"))
                    store.writePending(pending)
                }
                val payload = pending.getJSONObject("payload")
                try {
                    val ack = api(state).request("/api/native/batches", payload)
                    check(ack.optBoolean("accepted") && ack.optString("batch_id") == payload.getString("batch_id")) {
                        "The server did not acknowledge this batch."
                    }
                } catch (error: ApiException) {
                    if (error.status != 413) throw error
                    val window = payload.getJSONObject("window")
                    shrinkOrExclude(window.getLong("end_ms") - window.getLong("start_ms"), largestSource(payload.getJSONObject("data")))
                    store.clearPending()
                    continue
                }
                // Persist ack before removing queue: replay after a crash remains idempotent on the server.
                val updates = pending.getJSONObject("cursorUpdates")
                store.update {
                    val latest = it.optJSONObject("cursors") ?: JSONObject()
                    updates.keys().forEach { key -> latest.put(key, maxOf(latest.optLong(key), updates.getLong(key))) }
                    it.put("cursors", latest).put("lastSyncAt", System.currentTimeMillis())
                        .put("backlog", true)
                    if (failedSources.isEmpty()) it.remove("lastError")
                    else it.put("lastError", "Some permitted sources could not complete collection. Other available sources continue syncing.")
                }
                store.clearPending()
                count++
            }
            store.update { it.put("backlog", true) }
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
            store.update { it.put("lastError", "Sync could not complete. Check your connection and granted permissions; the pending batch is retained.") }
            throw error
        }
    }

    private fun invalidateAuth() {
        store.update { it.remove("token"); it.put("lastError", "Session expired. Sign in again to resume syncing.") }
        SyncScheduler.cancel(appContext)
    }

    private fun authenticatedState(): JSONObject = store.read().also {
        check(it.has("token")) { "Sign in to continue." }
    }

    private fun api(state: JSONObject) = NativeApi(state.getString("serverUrl"), state.getString("token"))
    private fun session(state: JSONObject) = JSONObject()
        .put("serverUrl", state.getString("serverUrl"))
        .put("token", state.getString("token"))
        .put("user", state.getJSONObject("user"))
        .put("onboarded", state.optBoolean("onboarded"))

    private fun isComplete(section: JSONObject?) =
        section?.optString("status") == "ok" && section.optBoolean("complete", true)

    companion object {
        private val lock = Mutex()
        private const val MAX_BATCHES = 8
        private const val MAX_PAYLOAD_BYTES = 2 * 1024 * 1024
    }
}
