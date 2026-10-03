package com.forma.datasync.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CollectionPermissionPlanTest {
    private fun needs(
        usage: Boolean = false, runtime: Boolean = false, health: Boolean = false,
        location: Boolean = false, background: Boolean = false, battery: Boolean = false,
    ) = CollectionPermissionNeeds(usage, runtime, health, location, background, battery)

    @Test fun missingUsageStopsBeforeOptionalRequests() {
        val missing = needs(usage = true, runtime = true, health = true, location = true, background = true, battery = true)
        assertEquals(CollectionPermissionStep.USAGE, CollectionPermissionPlan.next(missing, emptySet()))
        assertNull(CollectionPermissionPlan.next(missing, setOf(CollectionPermissionStep.USAGE)))
    }

    @Test fun optionalDenialsAreNotRepeatedAndDoNotBlockOtherSources() {
        val missing = needs(runtime = true, health = true, location = true, background = true, battery = true)
        val attempted = mutableSetOf<CollectionPermissionStep>()
        val sequence = buildList {
            while (true) {
                val step = CollectionPermissionPlan.next(missing, attempted) ?: break
                add(step)
                attempted.add(step)
            }
        }
        assertEquals(listOf(CollectionPermissionStep.RUNTIME, CollectionPermissionStep.HEALTH,
            CollectionPermissionStep.BACKGROUND_LOCATION, CollectionPermissionStep.BATTERY), sequence)
    }

    @Test fun backgroundLocationRequiresActualForegroundGrant() {
        assertNull(CollectionPermissionPlan.next(needs(background = true), emptySet()))
        assertEquals(CollectionPermissionStep.BACKGROUND_LOCATION,
            CollectionPermissionPlan.next(needs(location = true, background = true), emptySet()))
    }

    @Test fun alreadyGrantedAndUnavailableSourcesHaveNoPrompt() {
        assertNull(CollectionPermissionPlan.next(needs(location = true), emptySet()))
        assertEquals(CollectionPermissionStep.BATTERY,
            CollectionPermissionPlan.next(needs(location = true, battery = true), emptySet()))
    }

    @Test fun newlyGrantedLocationEnablesSeparateBackgroundStep() {
        val attempted = setOf(CollectionPermissionStep.RUNTIME)
        assertEquals(CollectionPermissionStep.BATTERY,
            CollectionPermissionPlan.next(needs(background = true, battery = true), attempted))
        assertEquals(CollectionPermissionStep.BACKGROUND_LOCATION,
            CollectionPermissionPlan.next(needs(location = true, background = true, battery = true), attempted))
    }

    @Test fun grantedUsageIsRecheckedBeforeOptionalRequests() {
        assertEquals(CollectionPermissionStep.RUNTIME,
            CollectionPermissionPlan.next(needs(runtime = true), setOf(CollectionPermissionStep.USAGE)))
    }

    @Test fun settingsMustReallyLeaveAndReturnToApp() {
        val gate = PermissionReturnGate(settings = true, resumed = true)
        assertFalse(gate.returned())
        gate.resume() // Initial/duplicate resume cannot finish the permission screen.
        assertFalse(gate.returned())
        gate.result() // A settings result code alone is not a completed return.
        assertFalse(gate.returned())
        gate.pause()
        assertFalse(gate.returned())
        gate.resume()
        assertTrue(gate.returned())
    }

    @Test fun callbackBeforeActivityResumeWaitsForReturn() {
        val gate = PermissionReturnGate(settings = false, resumed = true)
        gate.pause()
        gate.result()
        assertFalse(gate.returned())
        gate.resume()
        assertTrue(gate.returned())
    }

    @Test fun activityResumeBeforeCallbackWaitsForResult() {
        val gate = PermissionReturnGate(settings = false, resumed = false)
        gate.resume()
        assertFalse(gate.returned())
        gate.result()
        assertTrue(gate.returned())
    }

    @Test fun runtimeDialogDoesNotNeedActivityPauseWhenResultArrivesInForeground() {
        val gate = PermissionReturnGate(settings = false, resumed = true)
        assertFalse(gate.returned())
        gate.result()
        assertTrue(gate.returned())
    }
}
