package com.forma.datasync.sync

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.facebook.react.bridge.BaseActivityEventListener
import com.facebook.react.bridge.LifecycleEventListener
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.common.LifecycleState
import com.facebook.react.modules.core.PermissionAwareActivity
import com.facebook.react.modules.core.PermissionListener
import com.forma.datasync.collectors.HealthPermissions
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Owns one visible permission flow, so Android dialogs and settings never overlap. */
internal class PermissionRequestCoordinator(private val context: ReactApplicationContext) : LifecycleEventListener {
    private data class PendingReturn(
        val continuation: CancellableContinuation<Unit>,
        val gate: PermissionReturnGate,
        val requestCode: Int?,
    )

    private var hostResumed = context.lifecycleState == LifecycleState.RESUMED
    private var running = false
    private var activeJob: Job? = null
    private var pending: PendingReturn? = null
    private var nextRequestCode = 7430
    private var closed = false

    private val activityListener = object : BaseActivityEventListener() {
        override fun onActivityResult(activity: Activity, requestCode: Int, resultCode: Int, data: Intent?) {
            val waiting = pending ?: return
            if (waiting.requestCode != requestCode) return
            // Result codes are not permission grants. Read actual status after the app resumes.
            waiting.gate.result()
            completeReturn()
        }
    }

    init {
        context.addActivityEventListener(activityListener)
        context.addLifecycleEventListener(this)
    }

    suspend fun collection(): JSONObject = exclusive {
        val attempted = mutableSetOf<CollectionPermissionStep>()
        val errors = mutableListOf<String>()
        while (true) {
            val status = PermissionStatus.read(context)
            val healthPermissions = missingHealthPermissions(status)
            val needs = CollectionPermissionNeeds(
                usage = !status.optBoolean("usageAccess"),
                runtime = PermissionStatus.runtimePermissions(context).isNotEmpty(),
                health = healthPermissions.isNotEmpty(),
                foregroundLocation = status.optBoolean("location"),
                backgroundLocation = Build.VERSION.SDK_INT >= 29 && !status.optBoolean("backgroundLocation"),
                battery = !status.optBoolean("batteryUnrestricted"),
            )
            val step = CollectionPermissionPlan.next(needs, attempted) ?: break
            attempted.add(step)
            try {
                when (step) {
                    CollectionPermissionStep.USAGE -> usageSettings()
                    CollectionPermissionStep.RUNTIME -> runtime(PermissionStatus.runtimePermissions(context))
                    CollectionPermissionStep.HEALTH -> health(healthPermissions)
                    CollectionPermissionStep.BACKGROUND_LOCATION -> backgroundLocation()
                    CollectionPermissionStep.BATTERY -> batterySettings()
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (step == CollectionPermissionStep.USAGE) throw IllegalStateException(
                    "Usage Access could not be opened. Enable Forma in Android Settings > Apps > Special app access > Usage access, then try again.", error)
                // An unavailable optional settings screen must not block the required usage collector.
                errors.add(when (step) {
                    CollectionPermissionStep.RUNTIME -> "Device permission prompts could not open. Retry from the permission list."
                    CollectionPermissionStep.HEALTH -> "Health Connect permissions could not open. Retry from the permission list."
                    CollectionPermissionStep.BACKGROUND_LOCATION -> "Background location settings could not open. Retry from the permission list."
                    else -> "Battery settings could not open. Retry from the permission list."
                })
            }
        }
        PermissionStatus.read(context).put("requestErrors", JSONArray(errors))
    }

    suspend fun usage(): JSONObject = exclusive { usageSettings(); PermissionStatus.read(context) }
    suspend fun battery(): JSONObject = exclusive { batterySettings(); PermissionStatus.read(context) }
    suspend fun runtime(): JSONObject = exclusive {
        runtime(PermissionStatus.runtimePermissions(context))
        PermissionStatus.read(context)
    }
    suspend fun health(): JSONObject = exclusive {
        val status = PermissionStatus.read(context)
        check(status.optJSONObject("health")?.optString("status") == "ok") {
            "Health Connect is unavailable. Install or update Health Connect, then try again."
        }
        health(missingHealthPermissions(status))
        PermissionStatus.read(context)
    }
    suspend fun healthSettings(): JSONObject = exclusive {
        settings(listOf(HealthPermissions.settingsIntent(context)))
        PermissionStatus.read(context)
    }
    suspend fun background(): JSONObject = exclusive {
        val status = PermissionStatus.read(context)
        check(status.optBoolean("location")) { "Allow foreground location before enabling background location." }
        if (!status.optBoolean("backgroundLocation")) backgroundLocation()
        PermissionStatus.read(context)
    }

    // Dispatch resumed continuations after Android/React finishes delivering each callback.
    // React clears its runtime permission listener after our listener returns; an inline
    // continuation could register the next dialog's listener before that cleanup.
    private suspend fun <T> exclusive(action: suspend () -> T): T = withContext(Dispatchers.Main) {
        check(!closed) { "Open the app to request permissions." }
        check(!running) { "A permission request is already open. Return to the app to finish it first." }
        activity()
        running = true
        activeJob = currentCoroutineContext()[Job]
        try { action() } finally {
            pending = null
            activeJob = null
            running = false
        }
    }

    private fun activity(): Activity = context.currentActivity?.takeUnless { it.isFinishing || it.isDestroyed }
        ?: throw IllegalStateException("Open the app to request permissions.")

    private suspend fun usageSettings() = settings(listOf(
        Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, Uri.parse("package:${context.packageName}")),
        Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS),
    ))

    private suspend fun batterySettings() = settings(listOf(
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS), appSettings(),
    ))

    private fun appSettings() = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.parse("package:${context.packageName}"))

    private suspend fun backgroundLocation() {
        if (Build.VERSION.SDK_INT == 29) runtime(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION))
        else if (Build.VERSION.SDK_INT >= 30) settings(listOf(appSettings()))
    }

    private suspend fun settings(intents: List<Intent>) {
        awaitForeground()
        awaitReturn(settings = true) {
            var failure: Exception? = null
            for (intent in intents) {
                try {
                    // resolveActivity is filtered by package visibility and can hide a valid Settings screen.
                    activity().startActivity(intent)
                    return@awaitReturn
                } catch (error: ActivityNotFoundException) {
                    failure = error
                } catch (error: SecurityException) {
                    failure = error
                }
            }
            throw IllegalStateException("This Android device cannot open that permission screen.", failure)
        }
    }

    private suspend fun runtime(requested: Array<String>) {
        if (requested.isEmpty()) return
        awaitForeground()
        val code = requestCode()
        awaitReturn(settings = false, requestCode = null) { waiting ->
            val permissionActivity = activity() as? PermissionAwareActivity
                ?: throw IllegalStateException("Open the app to request permissions.")
            permissionActivity.requestPermissions(requested, code, PermissionListener { returnedCode, _, _ ->
                if (returnedCode != code) false else {
                    if (pending === waiting) {
                        waiting.gate.result()
                        completeReturn()
                    }
                    true
                }
            })
        }
    }

    private suspend fun health(requested: Set<String>) {
        if (requested.isEmpty()) return
        awaitForeground()
        val host = activity()
        val intent = HealthPermissions.permissionContract().createIntent(host, requested)
        if (HealthPermissionLaunchPlan.forAction(intent.action) == HealthPermissionLaunch.RUNTIME) {
            // Health Connect is part of the framework on Android 14+. Its contract's
            // synthetic REQUEST_PERMISSIONS intent is normally interpreted by the
            // AndroidX result registry; ReactActivity uses its native runtime bridge.
            runtime(requested.toTypedArray())
            return
        }
        val code = requestCode()
        awaitReturn(settings = false, requestCode = code) {
            @Suppress("DEPRECATION")
            host.startActivityForResult(intent, code)
        }
    }

    private fun missingHealthPermissions(status: JSONObject): Set<String> {
        val health = status.optJSONObject("health") ?: return emptySet()
        if (health.optString("status") != "ok") return emptySet()
        fun strings(array: JSONArray?): Set<String> = buildSet {
            if (array != null) for (index in 0 until array.length()) add(array.getString(index))
        }
        return strings(health.optJSONArray("requested_permissions")) - strings(health.optJSONArray("granted_permissions"))
    }

    private fun requestCode(): Int {
        nextRequestCode = if (nextRequestCode >= 30000) 7431 else nextRequestCode + 1
        return nextRequestCode
    }

    private suspend fun awaitForeground() {
        if (hostResumed) { activity(); return }
        awaitReturn(settings = false) { waiting -> waiting.gate.result() }
        activity()
    }

    private suspend fun awaitReturn(
        settings: Boolean,
        requestCode: Int? = null,
        launch: (PendingReturn) -> Unit,
    ) = suspendCancellableCoroutine<Unit> { continuation ->
        check(pending == null) { "A permission screen is already open." }
        val waiting = PendingReturn(continuation, PermissionReturnGate(settings, hostResumed), requestCode)
        pending = waiting
        continuation.invokeOnCancellation {
            context.runOnUiQueueThread { if (pending === waiting) pending = null }
        }
        try {
            launch(waiting)
            completeReturn()
        } catch (error: Exception) {
            if (pending === waiting) pending = null
            if (continuation.isActive) continuation.resumeWithException(error)
        }
    }

    private fun completeReturn() {
        val waiting = pending ?: return
        if (!waiting.gate.returned()) return
        pending = null
        if (waiting.continuation.isActive) waiting.continuation.resume(Unit)
    }

    override fun onHostResume() {
        hostResumed = true
        pending?.gate?.resume()
        completeReturn()
    }
    override fun onHostPause() {
        hostResumed = false
        pending?.gate?.pause()
    }
    override fun onHostDestroy() {
        hostResumed = false
        activeJob?.cancel(CancellationException("The activity closed before permissions were completed."))
    }

    fun close() {
        closed = true
        context.removeLifecycleEventListener(this)
        context.removeActivityEventListener(activityListener)
        context.runOnUiQueueThread {
            activeJob?.cancel(CancellationException("The permission request was cancelled."))
        }
    }
}
