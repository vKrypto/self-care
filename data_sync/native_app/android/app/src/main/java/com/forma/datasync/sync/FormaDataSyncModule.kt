package com.forma.datasync.sync

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.BaseActivityEventListener
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.WritableArray
import com.facebook.react.bridge.WritableMap
import com.facebook.react.modules.core.PermissionAwareActivity
import com.facebook.react.modules.core.PermissionListener
import com.forma.datasync.collectors.HealthPermissions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class FormaDataSyncModule(private val context: ReactApplicationContext) : ReactContextBaseJavaModule(context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val engine = SyncEngine(context)
    private var runtimePromise: Promise? = null
    private var healthPromise: Promise? = null

    private val activityListener = object : BaseActivityEventListener() {
        override fun onActivityResult(activity: Activity, requestCode: Int, resultCode: Int, data: Intent?) {
            if (requestCode != HEALTH_REQUEST) return
            val promise = healthPromise ?: return
            healthPromise = null
            // Reading actual granted permissions also handles denial, cancellation and permission changes.
            resolve(promise) { PermissionStatus.read(context) }
        }
    }

    init { context.addActivityEventListener(activityListener) }
    override fun getName() = "FormaDataSync"

    @ReactMethod fun restoreSession(promise: Promise) = resolve(promise) { engine.restoreSession() }
    @ReactMethod fun login(serverUrl: String, email: String, password: String, promise: Promise) =
        resolve(promise) { engine.login(serverUrl, email, password) }
    @ReactMethod fun permissionStatus(promise: Promise) = resolve(promise) { PermissionStatus.read(context) }
    @ReactMethod fun status(promise: Promise) = resolve(promise) { engine.status() }

    @ReactMethod fun completeOnboarding(historyDays: Double, promise: Promise) = resolve(promise) {
        require(historyDays.isFinite() && historyDays % 1.0 == 0.0) { "Enter a whole number of history days." }
        engine.completeOnboarding(historyDays.toInt())
    }

    @ReactMethod fun syncNow(promise: Promise) = resolve(promise) {
        val more = engine.sync(background = false)
        if (more) SyncScheduler.enqueue(context)
        engine.status()
    }

    @ReactMethod fun pauseSync(promise: Promise) = resolve(promise) { engine.pause() }
    @ReactMethod fun resumeSync(promise: Promise) = resolve(promise) { engine.resume() }
    @ReactMethod fun logout(promise: Promise) = resolve(promise) { engine.logout(); null }

    @ReactMethod fun openUsageSettings(promise: Promise) = openSettings(promise,
        Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, Uri.parse("package:${context.packageName}")))

    @ReactMethod fun openBatterySettings(promise: Promise) = openSettings(promise,
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))

    @ReactMethod fun openHealthSettings(promise: Promise) = resolve(promise) {
        withContext(Dispatchers.Main) { launchSettings(HealthPermissions.settingsIntent(context)) }
        null
    }

    @ReactMethod fun openBackgroundLocationSettings(promise: Promise) = resolve(promise) {
        withContext(Dispatchers.Main) {
            // Android 11+ requires the user to select "Allow all the time" in app settings.
            // On Android 10 the separate background permission has its own system dialog.
            if (Build.VERSION.SDK_INT == 29) requestRuntimeOnMain(promise,
                arrayOf(android.Manifest.permission.ACCESS_BACKGROUND_LOCATION))
            else launchSettings(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:${context.packageName}")))
        }
        if (Build.VERSION.SDK_INT == 29) DeferredResult else null
    }

    @ReactMethod fun requestRuntimePermissions(promise: Promise) = resolve(promise) {
        val requested = PermissionStatus.runtimePermissions(context)
        if (requested.isEmpty()) return@resolve PermissionStatus.read(context)
        withContext(Dispatchers.Main) { requestRuntimeOnMain(promise, requested) }
        DeferredResult
    }

    @ReactMethod fun requestHealthPermissions(promise: Promise) = resolve(promise) {
        val requested = HealthPermissions.requestedPermissions(context)
        require(requested.isNotEmpty()) { "Health Connect is unavailable. Install or update Health Connect, then try again." }
        withContext(Dispatchers.Main) {
            check(healthPromise == null) { "A health permission request is already open." }
            val activity = currentActivity ?: throw IllegalStateException("Open the app to request permissions.")
            val intent = HealthPermissions.permissionContract().createIntent(activity, requested)
            healthPromise = promise
            try {
                @Suppress("DEPRECATION")
                activity.startActivityForResult(intent, HEALTH_REQUEST)
            } catch (error: Exception) {
                healthPromise = null
                throw error
            }
        }
        DeferredResult
    }

    private fun requestRuntimeOnMain(promise: Promise, requested: Array<String>) {
        check(runtimePromise == null) { "A device permission request is already open." }
        val activity = currentActivity as? PermissionAwareActivity
            ?: throw IllegalStateException("Open the app to request permissions.")
        runtimePromise = promise
        try {
            activity.requestPermissions(requested, RUNTIME_REQUEST, PermissionListener { requestCode, _, _ ->
                if (requestCode != RUNTIME_REQUEST) false else {
                    val pending = runtimePromise
                    runtimePromise = null
                    if (pending != null) resolve(pending) { PermissionStatus.read(context) }
                    true
                }
            })
        } catch (error: Exception) {
            runtimePromise = null
            throw error
        }
    }

    private fun openSettings(promise: Promise, intent: Intent) = resolve(promise) {
        withContext(Dispatchers.Main) { launchSettings(intent) }
        null
    }

    private fun launchSettings(intent: Intent) {
        val activity = currentActivity ?: throw IllegalStateException("Open the app to change permissions.")
        if (intent.resolveActivity(context.packageManager) != null) activity.startActivity(intent)
        else activity.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${context.packageName}")))
    }

    private fun resolve(promise: Promise, action: suspend () -> Any?) {
        scope.launch {
            try {
                val result = action()
                if (result !== DeferredResult) promise.resolve(toBridge(result))
            } catch (error: CancellationException) {
                promise.reject("E_CANCELLED", "The operation was cancelled.")
                throw error
            } catch (error: Exception) {
                val message = when (error) {
                    is ApiException, is IllegalArgumentException, is IllegalStateException -> error.message
                    else -> "The operation could not complete. Check the server connection and device permissions."
                }
                promise.reject("E_DATA_SYNC", message)
            }
        }
    }

    override fun invalidate() {
        context.removeActivityEventListener(activityListener)
        runtimePromise?.reject("E_CANCELLED", "The permission request was cancelled.")
        healthPromise?.reject("E_CANCELLED", "The permission request was cancelled.")
        runtimePromise = null
        healthPromise = null
        scope.cancel()
        super.invalidate()
    }

    private fun toBridge(value: Any?): Any? = when (value) {
        null, JSONObject.NULL -> null
        is JSONObject -> Arguments.createMap().also { map ->
            value.keys().forEach { key -> putMap(map, key, value.opt(key)) }
        }
        is JSONArray -> Arguments.createArray().also { array ->
            for (i in 0 until value.length()) putArray(array, value.opt(i))
        }
        is Number -> value.toDouble()
        else -> value
    }

    private fun putMap(map: WritableMap, key: String, raw: Any?) {
        when (val value = toBridge(raw)) {
            null -> map.putNull(key)
            is WritableMap -> map.putMap(key, value)
            is WritableArray -> map.putArray(key, value)
            is Boolean -> map.putBoolean(key, value)
            is Number -> map.putDouble(key, value.toDouble())
            else -> map.putString(key, value.toString())
        }
    }

    private fun putArray(array: WritableArray, raw: Any?) {
        when (val value = toBridge(raw)) {
            null -> array.pushNull()
            is WritableMap -> array.pushMap(value)
            is WritableArray -> array.pushArray(value)
            is Boolean -> array.pushBoolean(value)
            is Number -> array.pushDouble(value.toDouble())
            else -> array.pushString(value.toString())
        }
    }

    private object DeferredResult
    companion object {
        private const val RUNTIME_REQUEST = 7431
        private const val HEALTH_REQUEST = 7432
    }
}
