package com.forma.datasync.sync

import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

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

    @Test fun removedConnectionKeepsTokenCollectionCursorsAndOriginalDeviceIdentity() {
        val state = connected().put("cursors", JSONObject().put("usage_events", now)).put("lastCollectedAt", now)
        val device = state.getString("deviceId")
        CollectionStatePolicy.markConnectionRemoved(state)
        assertTrue(state.getBoolean("connectionRemoved"))
        assertFalse(state.getBoolean("connected"))
        assertFalse(state.getBoolean("enabled"))
        assertTrue(CollectionStatePolicy.canCollect(state))
        assertFalse(CollectionStatePolicy.canUpload(state))
        assertEquals("token", state.getString("token"))
        assertEquals(device, state.getString("deviceId"))
        assertEquals(now, state.getJSONObject("cursors").getLong("usage_events"))
        assertEquals(now, state.getLong("lastCollectedAt"))
        state.put("connected", true).put("enabled", true)
        assertFalse(CollectionStatePolicy.canUpload(state))
    }

    @Test fun removedConnectionCannotRestoreOldConsentThroughLoginLogoutOrAccountSwitch() {
        val state = connected()
        val device = state.getString("deviceId")
        CollectionStatePolicy.markConnectionRemoved(state)
        CollectionStatePolicy.selectAccount(state, "https://one.test", user("first"), "renewed-token")
        assertTrue(state.getBoolean("connectionRemoved"))
        assertFalse(CollectionStatePolicy.canUpload(state))
        CollectionStatePolicy.disconnect(state)
        CollectionStatePolicy.selectAccount(state, "https://one.test", user("second"), "other-token")
        assertFalse(state.getBoolean("connectionRemoved"))
        CollectionStatePolicy.selectAccount(state, "https://other.test", user("first"), "other-server-token")
        assertFalse(state.getBoolean("connectionRemoved"))
        CollectionStatePolicy.selectAccount(state, "https://one.test", user("first"), "returned-token")
        assertTrue(state.getBoolean("connectionRemoved"))
        assertFalse(CollectionStatePolicy.canUpload(state))
        assertEquals(device, state.getString("deviceId"))
    }

    @Test fun failedRegistrationPreparationCannotRotateRemovedIdentityOrRememberedMapping() {
        val state = connected()
        CollectionStatePolicy.markConnectionRemoved(state)
        val original = state.toString()
        val candidate = CollectionStatePolicy.registrationDeviceId(state)
        assertNotEquals(state.getString("deviceId"), candidate)
        // No successful server acknowledgment means the commit policy is never called.
        assertEquals(original, state.toString())
        assertFalse(CollectionStatePolicy.canUpload(state))
    }

    @Test fun explicitAcknowledgedRegistrationReplacesOnlyTheCurrentAccountIdentity() {
        val state = connected().put("cursors", JSONObject().put("usage_events", now))
        val old = state.getString("deviceId")
        CollectionStatePolicy.markConnectionRemoved(state)
        val fresh = CollectionStatePolicy.registrationDeviceId(state)
        CollectionStatePolicy.completeRegistration(state, fresh)
        assertTrue(CollectionStatePolicy.canUpload(state))
        assertFalse(state.getBoolean("connectionRemoved"))
        assertEquals(fresh, state.getString("deviceId"))
        assertEquals(now, state.getJSONObject("cursors").getLong("usage_events"))
        CollectionStatePolicy.disconnect(state)
        CollectionStatePolicy.selectAccount(state, "https://one.test", user("first"), "new-session")
        assertEquals(fresh, state.getString("deviceId"))
        assertNotEquals(old, state.getString("deviceId"))
        assertFalse(state.getBoolean("connectionRemoved"))
        assertFalse(CollectionStatePolicy.canUpload(state))
    }

    @Test fun aLateRemovalResponseCannotDisconnectAnotherAccountOrNewConnection() {
        val state = connected()
        val old = JSONObject(state.toString())
        CollectionStatePolicy.selectAccount(state, "https://one.test", user("second"), "second-token")
        CollectionStatePolicy.completeRegistration(state, state.getString("deviceId"))
        CollectionStatePolicy.markConnectionRemoved(state, old)
        assertFalse(state.getBoolean("connectionRemoved"))
        assertTrue(CollectionStatePolicy.canUpload(state))
        CollectionStatePolicy.selectAccount(state, "https://one.test", user("first"), "returned-token")
        assertTrue(state.getBoolean("connectionRemoved"))
        val fresh = CollectionStatePolicy.registrationDeviceId(state)
        CollectionStatePolicy.completeRegistration(state, fresh)
        CollectionStatePolicy.markConnectionRemoved(state, old)
        assertFalse(state.getBoolean("connectionRemoved"))
        assertTrue(CollectionStatePolicy.canUpload(state))
        assertEquals(fresh, state.getString("deviceId"))
    }

    @Test fun malformedStatusResponsesCannotProveRemovalButAValidEmptyListCan() {
        val state = connected()
        val present = JSONObject().put("devices", JSONArray().put(JSONObject().put("device_id", state.getString("deviceId"))))
        assertFalse(CollectionStatePolicy.missingFromServerStatus(state, present))
        assertTrue(CollectionStatePolicy.missingFromServerStatus(state, JSONObject().put("devices", JSONArray())))
        assertTrue(CollectionStatePolicy.missingFromServerStatus(state, JSONObject().put("devices",
            JSONArray().put(JSONObject().put("device_id", UUID.randomUUID().toString())))))
        for (response in listOf(JSONObject(), JSONObject().put("devices", JSONObject()),
                JSONObject().put("devices", JSONArray().put("invalid")),
                JSONObject().put("devices", JSONArray().put(JSONObject())),
                JSONObject().put("devices", JSONArray().put(JSONObject().put("device_id", "invalid"))))) {
            assertFalse(CollectionStatePolicy.missingFromServerStatus(state, response))
        }
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
