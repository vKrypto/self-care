package com.forma.datasync.sync

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Pure lifecycle decisions shared by the local collector and the upload worker. */
object CollectionStatePolicy {
    fun canCollect(state: JSONObject) = state.optBoolean("onboarded") && state.optBoolean("collectionEnabled")

    fun canUpload(state: JSONObject) = !state.optBoolean("connectionRemoved") && state.optBoolean("connected") && state.optBoolean("enabled") &&
        state.optString("token").isNotBlank() && state.optString("serverUrl").isNotBlank() &&
        state.optJSONObject("user")?.optString("id")?.isNotBlank() == true

    fun rememberDevice(state: JSONObject, replaceIdentity: Boolean = false) {
        val server = state.optString("serverUrl")
        val user = state.optJSONObject("user")?.optString("id").orEmpty()
        val device = state.optString("deviceId")
        if (server.isBlank() || user.isBlank() || device.isBlank()) return
        val devices = state.optJSONArray("accountDevices") ?: JSONArray()
        val entry = (0 until devices.length()).map { devices.getJSONObject(it) }.firstOrNull {
            it.optString("serverUrl") == server && it.optString("userId") == user
        }
        if (entry == null) {
            devices.put(JSONObject().put("serverUrl", server).put("userId", user).put("deviceId", device)
                .put("connectionRemoved", state.optBoolean("connectionRemoved")))
        } else {
            if (replaceIdentity) entry.put("deviceId", device)
            entry.put("connectionRemoved", state.optBoolean("connectionRemoved"))
        }
        state.put("accountDevices", devices)
    }

    /** Preserve each server/account's registered device ID without resetting local history. */
    fun selectAccount(state: JSONObject, serverUrl: String, user: JSONObject, token: String) {
        rememberDevice(state)
        val sameAccount = state.optString("serverUrl") == serverUrl &&
            state.optJSONObject("user")?.optString("id") == user.optString("id")
        val devices = state.optJSONArray("accountDevices") ?: JSONArray()
        val known = (0 until devices.length()).map { devices.getJSONObject(it) }.firstOrNull {
            it.optString("serverUrl") == serverUrl && it.optString("userId") == user.optString("id")
        }
        val deviceId = known?.getString("deviceId") ?: UUID.randomUUID().toString()
        val removed = known?.optBoolean("connectionRemoved") ?: false
        state.put("serverUrl", serverUrl).put("user", user).put("token", token).put("deviceId", deviceId)
            .put("connectionRemoved", removed)
        if (!sameAccount || removed) state.put("connected", false).put("enabled", false).remove("lastSyncAt")
        rememberDevice(state)
    }

    /** Record only the rejected connection, preserving its UUID and collection state. */
    fun markConnectionRemoved(state: JSONObject, rejectedConnection: JSONObject = state) {
        val server = rejectedConnection.optString("serverUrl")
        val user = rejectedConnection.optJSONObject("user")?.optString("id").orEmpty()
        val device = rejectedConnection.optString("deviceId")
        if (server.isBlank() || user.isBlank() || device.isBlank()) return
        rememberDevice(state)
        val devices = state.optJSONArray("accountDevices") ?: JSONArray()
        val known = (0 until devices.length()).map { devices.getJSONObject(it) }.firstOrNull {
            it.optString("serverUrl") == server && it.optString("userId") == user
        }
        if (known == null) {
            devices.put(JSONObject().put("serverUrl", server).put("userId", user).put("deviceId", device)
                .put("connectionRemoved", true))
        } else if (known.optString("deviceId") == device) {
            known.put("connectionRemoved", true)
        }
        state.put("accountDevices", devices)
        val currentUser = state.optJSONObject("user")?.optString("id").orEmpty()
        if (state.optString("serverUrl") == server && state.optString("deviceId") == device &&
            (currentUser == user || currentUser.isBlank())) {
            state.put("connectionRemoved", true).put("connected", false).put("enabled", false)
        }
    }

    /** Preparing a registration must not rotate a persisted identity on HTTP failure. */
    fun registrationDeviceId(state: JSONObject, freshId: () -> String = { UUID.randomUUID().toString() }): String =
        if (state.optBoolean("connectionRemoved")) freshId().also {
            require(it.isNotBlank() && it != state.optString("deviceId")) { "A new connection requires a fresh device identity." }
        } else state.getString("deviceId")

    /** Call only after the explicit consent action receives its matching server acknowledgment. */
    fun completeRegistration(state: JSONObject, registeredDeviceId: String) {
        require(registeredDeviceId.isNotBlank()) { "The server returned an invalid device identity." }
        require(!state.optBoolean("connectionRemoved") || registeredDeviceId != state.optString("deviceId")) {
            "A removed connection cannot be reused."
        }
        state.put("deviceId", registeredDeviceId).put("connectionRemoved", false).put("connected", true).put("enabled", true)
        state.remove("lastError")
        rememberDevice(state, replaceIdentity = true)
    }

    /** Missing/malformed status responses cannot prove that a connection was removed. */
    fun missingFromServerStatus(state: JSONObject, response: JSONObject): Boolean {
        val devices = response.optJSONArray("devices") ?: return false
        val identities = (0 until devices.length()).map { index ->
            val entry = devices.optJSONObject(index) ?: return false
            val identity = entry.opt("device_id") as? String ?: return false
            try {
                if (!UUID.fromString(identity).toString().equals(identity, ignoreCase = true)) return false
            } catch (_: IllegalArgumentException) {
                return false
            }
            identity
        }
        return identities.none { it.equals(state.optString("deviceId"), ignoreCase = true) }
    }

    fun disconnect(state: JSONObject) {
        rememberDevice(state)
        state.put("enabled", false).put("connected", false)
        state.remove("token")
        state.remove("user")
        state.remove("expiresAt")
        state.remove("lastError")
    }

    fun configureCollection(state: JSONObject, historyDays: Int, now: Long) {
        require(historyDays in 1..365) { "History must be between 1 and 365 days." }
        val requestedStart = now - historyDays * SyncPlanner.DAY_MS
        state.put("historyDays", historyDays).put("onboarded", true).put("collectionEnabled", true)
        if (!state.has("deviceId")) state.put("deviceId", UUID.randomUUID().toString())
        if (!state.has("historyStart") || requestedStart < state.getLong("historyStart")) {
            val existed = state.has("historyStart")
            state.put("historyStart", requestedStart)
            if (existed) state.put("collectionEpoch", state.optLong("collectionEpoch") + 1)
            val cursors = state.optJSONObject("cursors") ?: JSONObject()
            cursors.keys().asSequence().toList().forEach { source -> cursors.put(source, requestedStart) }
            state.put("cursors", cursors)
        }
        state.remove("collectionError")
    }

    fun mergeCursors(state: JSONObject, updates: JSONObject) {
        val cursors = state.optJSONObject("cursors") ?: JSONObject()
        updates.keys().forEach { source -> cursors.put(source, maxOf(cursors.optLong(source), updates.getLong(source))) }
        state.put("cursors", cursors)
    }
}
