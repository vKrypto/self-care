package com.forma.datasync.sync

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CollectionStatePolicyTest {
    private val now = 1000L * SyncPlanner.DAY_MS
    private fun user(id: String) = JSONObject().put("id", id)
    private fun collecting() = JSONObject().also { CollectionStatePolicy.configureCollection(it, 30, now) }
    private fun connected() = collecting().apply {
        CollectionStatePolicy.selectAccount(this, "https://one.test", user("first"), "token")
        put("connected", true).put("enabled", true)
    }

    @Test fun guestCollectionNeverNeedsAnAccountOrServer() {
        val state = collecting()
        assertTrue(CollectionStatePolicy.canCollect(state))
        assertFalse(CollectionStatePolicy.canUpload(state))
        assertFalse(state.has("token"))
        assertFalse(state.has("serverUrl"))
        assertFalse(state.has("user"))
    }

    @Test fun loginAloneCannotAuthorizeGuestUploads() {
        val state = collecting()
        CollectionStatePolicy.selectAccount(state, "https://one.test", user("first"), "token")
        assertTrue(CollectionStatePolicy.canCollect(state))
        assertFalse(CollectionStatePolicy.canUpload(state))
    }

    @Test fun expiryAndUploadPausePreserveCollection() {
        val state = connected()
        assertTrue(CollectionStatePolicy.canUpload(state))
        state.remove("token")
        assertFalse(CollectionStatePolicy.canUpload(state))
        assertTrue(CollectionStatePolicy.canCollect(state))
        state.put("token", "renewed").put("enabled", false)
        assertFalse(CollectionStatePolicy.canUpload(state))
        assertTrue(CollectionStatePolicy.canCollect(state))
    }

    @Test fun collectionPausePreservesAnAlreadyAuthorizedUpload() {
        val state = connected().put("collectionEnabled", false)
        assertFalse(CollectionStatePolicy.canCollect(state))
        assertTrue(CollectionStatePolicy.canUpload(state))
    }

    @Test fun logoutRetainsHistoryAndCursorsAndStopsUploadAuthority() {
        val state = connected().put("cursors", JSONObject().put("usage_events", now))
            .put("lastCollectedAt", now)
        CollectionStatePolicy.disconnect(state)
        assertTrue(CollectionStatePolicy.canCollect(state))
        assertFalse(CollectionStatePolicy.canUpload(state))
        assertFalse(state.has("token"))
        assertFalse(state.has("user"))
        assertEquals(now, state.getJSONObject("cursors").getLong("usage_events"))
        assertEquals(now, state.getLong("lastCollectedAt"))
        assertEquals(30, state.getInt("historyDays"))
    }

    @Test fun accountSwitchKeepsCollectorProgressAndRestoresEachRegisteredDevice() {
        val state = connected().put("cursors", JSONObject().put("usage_events", now))
        val firstDevice = state.getString("deviceId")
        CollectionStatePolicy.selectAccount(state, "https://one.test", user("second"), "second-token")
        val secondDevice = state.getString("deviceId")
        assertNotEquals(firstDevice, secondDevice)
        assertFalse(CollectionStatePolicy.canUpload(state))
        assertEquals(now, state.getJSONObject("cursors").getLong("usage_events"))
        CollectionStatePolicy.disconnect(state)
        CollectionStatePolicy.selectAccount(state, "https://one.test", user("first"), "first-token")
        assertEquals(firstDevice, state.getString("deviceId"))
        assertFalse(CollectionStatePolicy.canUpload(state))
        CollectionStatePolicy.selectAccount(state, "https://other.test", user("first"), "other-token")
        assertNotEquals(firstDevice, state.getString("deviceId"))
    }

    @Test fun sameAccountTokenRenewalPreservesPriorUploadConsent() {
        val state = connected()
        val device = state.getString("deviceId")
        state.remove("token")
        CollectionStatePolicy.selectAccount(state, "https://one.test", user("first"), "new-token")
        assertTrue(CollectionStatePolicy.canUpload(state))
        assertEquals(device, state.getString("deviceId"))
    }

    @Test fun increasingHistoryRewindsCursorsAndStartsANewRecoveryEpoch() {
        val state = collecting().put("cursors", JSONObject().put("health_steps", now))
        CollectionStatePolicy.configureCollection(state, 90, now)
        assertEquals(now - 90 * SyncPlanner.DAY_MS, state.getJSONObject("cursors").getLong("health_steps"))
        assertEquals(1, state.getLong("collectionEpoch"))
        CollectionStatePolicy.configureCollection(state, 30, now)
        assertEquals(now - 90 * SyncPlanner.DAY_MS, state.getLong("historyStart"))
        assertEquals(1, state.getLong("collectionEpoch"))
    }

    @Test fun recoveredDurableCursorsAdvanceWithoutOverwritingNewerSources() {
        val state = collecting().put("cursors", JSONObject().put("usage_events", 200))
        CollectionStatePolicy.mergeCursors(state, JSONObject().put("usage_events", 100).put("calendar_events", 300))
        assertEquals(200, state.getJSONObject("cursors").getLong("usage_events"))
        assertEquals(300, state.getJSONObject("cursors").getLong("calendar_events"))
    }
}
