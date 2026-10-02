package com.forma.datasync.sync

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Pure lifecycle decisions shared by the local collector and the upload worker. */
object CollectionStatePolicy {
    fun canCollect(state: JSONObject) = state.optBoolean("onboarded") && state.optBoolean("collectionEnabled")

    fun canUpload(state: JSONObject) = state.optBoolean("connected") && state.optBoolean("enabled") &&
        state.optString("token").isNotBlank() && state.optString("serverUrl").isNotBlank() &&
        state.optJSONObject("user")?.optString("id")?.isNotBlank() == true

    fun rememberDevice(state: JSONObject) {
        val server = state.optString("serverUrl")
        val user = state.optJSONObject("user")?.optString("id").orEmpty()
        val device = state.optString("deviceId")
        if (server.isBlank() || user.isBlank() || device.isBlank()) return
        val devices = state.optJSONArray("accountDevices") ?: JSONArray()
        if ((0 until devices.length()).none { index ->
                val entry = devices.getJSONObject(index)
                entry.optString("serverUrl") == server && entry.optString("userId") == user
            }) {
            devices.put(JSONObject().put("serverUrl", server).put("userId", user).put("deviceId", device))
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
        state.put("serverUrl", serverUrl).put("user", user).put("token", token).put("deviceId", deviceId)
        if (!sameAccount) state.put("connected", false).put("enabled", false).remove("lastSyncAt")
        rememberDevice(state)
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
