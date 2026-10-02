package com.forma.datasync.sync

import org.json.JSONObject

/** Revalidate persisted exports against the current account and Android permissions. */
object SyncBatchPolicy {
    enum class Decision { UPLOAD, STOP, RECOLLECT }

    fun decide(pending: JSONObject, state: JSONObject, availability: JSONObject): Decision {
        if (!state.optBoolean("enabled") || state.optString("token").isBlank()) return Decision.STOP
        val payload = pending.getJSONObject("payload")
        if (pending.optString("userId") != state.optJSONObject("user")?.optString("id") ||
            payload.optString("device_id") != state.optString("deviceId")) return Decision.RECOLLECT

        val data = payload.getJSONObject("data")
        val health = availability.optJSONObject("health_status")?.optJSONObject("details")
        val historyStart = health?.optLong("history_read_start_ms", Long.MAX_VALUE) ?: Long.MAX_VALUE
        for (source in data.keys().asSequence().filter { it != "source_status" }) {
            val access = availability.optJSONObject(source)
            if (access?.optString("status") != "ok" || !access.optBoolean("complete", true)) {
                return Decision.RECOLLECT
            }
            if (source.startsWith("health_") && source != "health_status" &&
                health?.optBoolean("history_granted") != true) {
                val section = data.getJSONObject(source)
                // History access can be revoked independently of a record-type permission.
                // Recollect rather than sending a queued broader read after that revocation.
                val queryStart = section.optLong("effective_window_start_ms", payload.getJSONObject("window").getLong("start_ms"))
                if ((section.optJSONArray("records")?.length() ?: 0) > 0 && queryStart < historyStart) {
                    return Decision.RECOLLECT
                }
            }
        }
        return Decision.UPLOAD
    }
}
