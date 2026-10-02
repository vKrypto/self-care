package com.forma.datasync.sync

import org.json.JSONObject

/** Revalidate persisted exports against the current account and Android permissions. */
object SyncBatchPolicy {
    enum class Decision { UPLOAD, STOP, BLOCK }

    fun decide(pending: JSONObject, state: JSONObject, availability: JSONObject): Decision {
        if (!CollectionStatePolicy.canUpload(state)) return Decision.STOP
        val payload = pending.getJSONObject("payload")
        val owner = pending.optJSONObject("owner")
        if (owner != null && (owner.optString("serverUrl") != state.optString("serverUrl") ||
                owner.optString("userId") != state.optJSONObject("user")?.optString("id"))) return Decision.STOP
        if (pending.optString("userId") != state.optJSONObject("user")?.optString("id") ||
            payload.optString("device_id") != state.optString("deviceId")) return Decision.BLOCK

        val data = payload.getJSONObject("data")
        val health = availability.optJSONObject("health_status")?.optJSONObject("details")
        val historyStart = health?.optLong("history_read_start_ms", Long.MAX_VALUE) ?: Long.MAX_VALUE
        for (source in data.keys().asSequence().filter { it != "source_status" }) {
            val access = availability.optJSONObject(source)
            if (access?.optString("status") != "ok" || !access.optBoolean("complete", true)) {
                return Decision.BLOCK
            }
            if (source.startsWith("health_") && source != "health_status" &&
                health?.optBoolean("history_granted") != true) {
                val section = data.getJSONObject(source)
                // History access can be revoked independently of a record-type permission.
                // Hold the queued broader read on this device after that revocation.
                val queryStart = section.optLong("effective_window_start_ms", payload.getJSONObject("window").getLong("start_ms"))
                if ((section.optJSONArray("records")?.length() ?: 0) > 0 && queryStart < historyStart) {
                    return Decision.BLOCK
                }
            }
        }
        return Decision.UPLOAD
    }
}
