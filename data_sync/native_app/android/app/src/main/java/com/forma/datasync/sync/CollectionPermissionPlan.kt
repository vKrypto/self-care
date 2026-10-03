package com.forma.datasync.sync

import androidx.activity.result.contract.ActivityResultContracts

internal enum class HealthPermissionLaunch { RUNTIME, PROVIDER_ACTIVITY }

internal object HealthPermissionLaunchPlan {
    /** Android 14+ contracts return an AndroidX instruction, not a launchable activity. */
    fun forAction(action: String?): HealthPermissionLaunch =
        if (action == ActivityResultContracts.RequestMultiplePermissions.ACTION_REQUEST_PERMISSIONS) {
            HealthPermissionLaunch.RUNTIME
        } else {
            HealthPermissionLaunch.PROVIDER_ACTIVITY
        }
}

/** Android settings can emit resume before leaving the app; require a real return. */
internal class PermissionReturnGate(private val settings: Boolean, resumed: Boolean) {
    private var hostResumed = resumed
    private var hostPaused = false
    private var resultReceived = false

    fun pause() { hostResumed = false; hostPaused = true }
    fun resume() { hostResumed = true }
    fun result() { resultReceived = true }
    fun returned(): Boolean = hostResumed && if (settings) hostPaused else resultReceived
}

internal enum class CollectionPermissionStep { USAGE, RUNTIME, HEALTH, BACKGROUND_LOCATION, BATTERY }

internal data class CollectionPermissionNeeds(
    val usage: Boolean,
    val runtime: Boolean,
    val health: Boolean,
    val foregroundLocation: Boolean,
    val backgroundLocation: Boolean,
    val battery: Boolean,
)

/** A denied optional source is tried once per user action and does not prevent collection. */
internal object CollectionPermissionPlan {
    fun next(needs: CollectionPermissionNeeds, attempted: Set<CollectionPermissionStep>): CollectionPermissionStep? {
        if (needs.usage) return CollectionPermissionStep.USAGE.takeUnless { it in attempted }
        return when {
            needs.runtime && CollectionPermissionStep.RUNTIME !in attempted -> CollectionPermissionStep.RUNTIME
            needs.health && CollectionPermissionStep.HEALTH !in attempted -> CollectionPermissionStep.HEALTH
            needs.foregroundLocation && needs.backgroundLocation && CollectionPermissionStep.BACKGROUND_LOCATION !in attempted ->
                CollectionPermissionStep.BACKGROUND_LOCATION
            needs.battery && CollectionPermissionStep.BATTERY !in attempted -> CollectionPermissionStep.BATTERY
            else -> null
        }
    }
}
