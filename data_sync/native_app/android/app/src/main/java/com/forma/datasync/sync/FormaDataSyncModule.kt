package com.forma.datasync.sync

import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.WritableArray
import com.facebook.react.bridge.WritableMap
import com.forma.datasync.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

class FormaDataSyncModule(private val context: ReactApplicationContext) : ReactContextBaseJavaModule(context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val engine = SyncEngine(context)
    private val permissionRequests = PermissionRequestCoordinator(context)
    override fun getName() = "FormaDataSync"
    override fun getConstants(): Map<String, Any> = mapOf(
        "allowLanHttp" to BuildConfig.ALLOW_LAN_HTTP,
        "defaultServerUrl" to BuildConfig.DEFAULT_SERVER_URL,
        "defaultEmail" to BuildConfig.DEFAULT_EMAIL,
        "defaultPassword" to BuildConfig.DEFAULT_PASSWORD,
        "appVersion" to BuildConfig.VERSION_NAME,
    )

    @ReactMethod fun restoreSession(promise: Promise) = resolve(promise) { engine.restoreSession() }
    @ReactMethod fun login(serverUrl: String, email: String, password: String, promise: Promise) =
        resolve(promise) { engine.login(serverUrl, email, password) }
    @ReactMethod fun permissionStatus(promise: Promise) = resolve(promise) { PermissionStatus.read(context) }
    @ReactMethod fun status(promise: Promise) = resolve(promise) { engine.status() }

    @ReactMethod fun syncHistory(offset: Double, limit: Double, promise: Promise) = resolve(promise) {
        val page = historyPage(offset, limit)
        engine.syncHistory(page.first, page.second)
    }

    @ReactMethod fun collectionDetails(jobId: String, promise: Promise) = resolve(promise) {
        engine.collectionDetails(jobId)
    }

    @ReactMethod fun collectionRecords(jobId: String, source: String, offset: Double, limit: Double, promise: Promise) = resolve(promise) {
        val page = historyPage(offset, limit)
        engine.collectionRecords(jobId, source, page.first, page.second)
    }

    private fun historyPage(offset: Double, limit: Double): Pair<Int, Int> {
        require(offset.isFinite() && offset % 1.0 == 0.0 && offset >= 0.0 && offset <= Int.MAX_VALUE.toDouble()) {
            "History offset must be a nonnegative whole number."
        }
        require(limit.isFinite() && limit % 1.0 == 0.0 && limit >= 1.0 && limit <= 100.0) {
            "History page size must be a whole number between 1 and 100."
        }
        return offset.toInt() to limit.toInt()
    }

    @ReactMethod fun completeOnboarding(historyDays: Double, promise: Promise) = resolve(promise) {
        require(historyDays.isFinite() && historyDays % 1.0 == 0.0) { "Enter a whole number of history days." }
        engine.completeOnboarding(historyDays.toInt())
    }

    @ReactMethod fun startCollection(historyDays: Double, promise: Promise) = resolve(promise) {
        require(historyDays.isFinite() && historyDays % 1.0 == 0.0) { "Enter a whole number of history days." }
        engine.startCollection(historyDays.toInt())
    }

    @ReactMethod fun collectNow(promise: Promise) = resolve(promise) {
        val more = engine.collect(background = false)
        if (more) CollectionScheduler.enqueue(context)
        if (engine.status().optBoolean("enabled")) SyncScheduler.enqueue(context)
        engine.status()
    }

    @ReactMethod fun syncNow(promise: Promise) = resolve(promise) {
        val collectingMore = engine.collect(background = false)
        if (collectingMore) CollectionScheduler.enqueue(context)
        val more = engine.sync(background = false)
        if (more) SyncScheduler.enqueue(context)
        engine.status()
    }

    @ReactMethod fun pauseSync(promise: Promise) = resolve(promise) { engine.pause() }
    @ReactMethod fun resumeSync(promise: Promise) = resolve(promise) { engine.resume() }
    @ReactMethod fun pauseCollection(promise: Promise) = resolve(promise) { engine.pauseCollection() }
    @ReactMethod fun resumeCollection(promise: Promise) = resolve(promise) { engine.resumeCollection() }
    @ReactMethod fun logout(promise: Promise) = resolve(promise) { engine.logout(); null }

    @ReactMethod fun requestCollectionPermissions(promise: Promise) = resolve(promise) { permissionRequests.collection() }
    @ReactMethod fun openUsageSettings(promise: Promise) = resolve(promise) { permissionRequests.usage() }
    @ReactMethod fun openBatterySettings(promise: Promise) = resolve(promise) { permissionRequests.battery() }
    @ReactMethod fun openHealthSettings(promise: Promise) = resolve(promise) { permissionRequests.healthSettings() }
    @ReactMethod fun openBackgroundLocationSettings(promise: Promise) = resolve(promise) { permissionRequests.background() }
    @ReactMethod fun requestRuntimePermissions(promise: Promise) = resolve(promise) { permissionRequests.runtime() }
    @ReactMethod fun requestHealthPermissions(promise: Promise) = resolve(promise) { permissionRequests.health() }

    private fun resolve(promise: Promise, action: suspend () -> Any?) {
        scope.launch {
            try {
                val result = action()
                promise.resolve(toBridge(result))
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
        permissionRequests.close()
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

}
