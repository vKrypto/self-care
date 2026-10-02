package com.forma.datasync.sync

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class SyncBatchPolicyTest {
    private fun state() = JSONObject().put("enabled", true).put("connected", true)
        .put("serverUrl", "https://example.test").put("token", "session")
        .put("deviceId", "device").put("user", JSONObject().put("id", "user"))

    private fun pending(queryStart: Long = 100, records: Boolean = true) = JSONObject()
        .put("userId", "user").put("payload", JSONObject().put("device_id", "device")
            .put("window", JSONObject().put("start_ms", 100).put("end_ms", 300))
            .put("data", JSONObject().put("health_steps", JSONObject().put("status", "ok")
                .put("effective_window_start_ms", queryStart).put("history_permission_granted", true)
                .put("records", if (records) JSONArray().put(JSONObject().put("count", 12)) else JSONArray()))))

    private fun access(historyGranted: Boolean = true, lowerBound: Long = 200) = JSONObject()
        .put("health_steps", JSONObject().put("status", "ok").put("complete", true))
        .put("health_status", JSONObject().put("status", "ok").put("details", JSONObject()
            .put("history_granted", historyGranted).put("history_read_start_ms", lowerBound)))

    @Test fun pausedAndExpiredSessionsCannotUploadAnAlreadyCollectedBatch() {
        assertEquals(SyncBatchPolicy.Decision.STOP, SyncBatchPolicy.decide(pending(), state().put("enabled", false), access()))
        assertEquals(SyncBatchPolicy.Decision.STOP, SyncBatchPolicy.decide(pending(), state().apply { remove("token") }, access()))
        assertEquals(SyncBatchPolicy.Decision.STOP, SyncBatchPolicy.decide(pending(), state().put("connected", false), access()))
    }

    @Test fun anOwnedBatchCannotCrossServersWithTheSameUserId() {
        val batch = pending().put("owner", JSONObject().put("serverUrl", "https://other.test").put("userId", "user"))
        assertEquals(SyncBatchPolicy.Decision.STOP, SyncBatchPolicy.decide(batch, state(), access()))
    }

    @Test fun anotherAccountOrInstallationCannotReplayThePendingBatch() {
        assertEquals(SyncBatchPolicy.Decision.BLOCK, SyncBatchPolicy.decide(pending(), state().put("user", JSONObject().put("id", "other")), access()))
        assertEquals(SyncBatchPolicy.Decision.BLOCK, SyncBatchPolicy.decide(pending(), state().put("deviceId", "other"), access()))
    }

    @Test fun revokedRecordPermissionInvalidatesTheQueuedBatch() {
        val permissions = access().put("health_steps", JSONObject().put("status", "denied"))
        assertEquals(SyncBatchPolicy.Decision.BLOCK, SyncBatchPolicy.decide(pending(), state(), permissions))
    }

    @Test fun revokedHistoryPermissionInvalidatesBroaderQueuedReads() {
        assertEquals(SyncBatchPolicy.Decision.BLOCK, SyncBatchPolicy.decide(pending(), state(), access(historyGranted = false)))
    }

    @Test fun historyRevocationStillPermitsRecentReadsAndEmptyOldWindows() {
        assertEquals(SyncBatchPolicy.Decision.UPLOAD, SyncBatchPolicy.decide(pending(queryStart = 200), state(), access(historyGranted = false)))
        assertEquals(SyncBatchPolicy.Decision.UPLOAD, SyncBatchPolicy.decide(pending(records = false), state(), access(historyGranted = false)))
    }

    @Test fun missingCurrentHistoryBoundCannotAuthorizeHistoricalData() {
        val permissions = access(historyGranted = false)
        permissions.getJSONObject("health_status").getJSONObject("details").remove("history_read_start_ms")
        assertEquals(SyncBatchPolicy.Decision.BLOCK, SyncBatchPolicy.decide(pending(), state(), permissions))
    }

    @Test fun validPermissionsPreserveTheExactRetryPayload() {
        val batch = pending()
        val original = batch.toString()
        assertEquals(SyncBatchPolicy.Decision.UPLOAD, SyncBatchPolicy.decide(batch, state(), access()))
        assertEquals(original, batch.toString())
    }
}
