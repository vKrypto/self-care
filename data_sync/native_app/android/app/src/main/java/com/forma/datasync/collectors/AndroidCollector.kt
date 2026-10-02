package com.forma.datasync.collectors

import android.Manifest
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.AppOpsManager
import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.CancellationSignal
import android.os.Environment
import android.os.PowerManager
import android.os.Process
import android.os.StatFs
import android.os.SystemClock
import android.os.UserManager
import android.provider.CalendarContract
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.lang.reflect.Modifier
import java.time.Instant
import java.util.TimeZone
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.reflect.KClass

/** Collects only public Android data sources with granted permissions. No upload or cursor writes. */
class AndroidCollector(context: Context) {
    private val context = context.applicationContext

    /**
     * startMs/endMs are an inclusive/exclusive history window of at most one day. Snapshots
     * explicitly carry their real capture time and cannot reconstruct historical device state.
     * Each source fails independently; callers should only advance a source cursor after an
     * acknowledged upload of a section whose status is ok and complete is true.
     */
    suspend fun collect(startMs: Long, endMs: Long, background: Boolean, sections: Set<String>? = null): JSONObject = withContext(Dispatchers.IO) {
        require(startMs >= 0 && endMs > startMs && endMs - startMs <= DAY_MS) {
            "Collector history window must be positive and no longer than one day"
        }
        val data = JSONObject()
        if (wanted(sections, "usage_stats")) data.put("usage_stats", safely { usageStats(startMs, endMs) })
        if (wanted(sections, "usage_events")) data.put("usage_events", safely { usageEvents(startMs, endMs) })
        if (wanted(sections, "usage_event_stats")) data.put("usage_event_stats", safely { usageEventStats(startMs, endMs) })
        if (wanted(sections, "network_usage_wifi")) data.put("network_usage_wifi", safely { networkUsage(startMs, endMs, "wifi") })
        if (wanted(sections, "network_usage_mobile")) data.put("network_usage_mobile", safely { networkUsage(startMs, endMs, "mobile") })
        if (wanted(sections, "visible_apps")) data.put("visible_apps", safely { visibleApps() })
        if (wanted(sections, "device_snapshot")) data.put("device_snapshot", safely { deviceSnapshot() })
        if (wanted(sections, "calendar_events")) data.put("calendar_events", safely { calendarEvents(startMs, endMs) })
        if (wanted(sections, "location_snapshot")) data.put("location_snapshot", safely { locationSnapshot(background) })
        if (wanted(sections, "activity_snapshot")) data.put("activity_snapshot", safely { activitySnapshot() })
        if (sections == null || sections.any { it.startsWith("health_") }) collectHealth(data, startMs, endMs, background, sections)
        data
    }

    /** Permission-only planning probe. It does not consume Health Connect read quotas. */
    suspend fun availability(background: Boolean): JSONObject = withContext(Dispatchers.IO) {
        val data = JSONObject()
        val usage = safely { usageUnavailable() ?: section("ok") }
        listOf("usage_stats", "usage_events", "network_usage_wifi", "network_usage_mobile").forEach {
            data.put(it, JSONObject(usage.toString()))
        }
        data.put("usage_event_stats", if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            JSONObject(usage.toString())
        } else {
            section("unavailable").put("reason", "android_api_level_not_supported").put("minimum_sdk_int", 28)
        })
        data.put("visible_apps", section("ok").snapshot())
        data.put("device_snapshot", section("ok").snapshot())
        data.put("calendar_events", section(if (granted(Manifest.permission.READ_CALENDAR)) "ok" else "denied"))
        val locationStatus = when {
            !granted(Manifest.permission.ACCESS_FINE_LOCATION) && !granted(Manifest.permission.ACCESS_COARSE_LOCATION) -> "denied"
            background && Build.VERSION.SDK_INT >= 29 && !granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION) -> "background_denied"
            else -> "ok"
        }
        data.put("location_snapshot", section(locationStatus).snapshot())
        val activityStatus = when {
            Build.VERSION.SDK_INT >= 29 && !granted(Manifest.permission.ACTIVITY_RECOGNITION) -> "denied"
            (context.getSystemService(Context.SENSOR_SERVICE) as SensorManager).getDefaultSensor(Sensor.TYPE_STEP_COUNTER) == null -> "unavailable"
            else -> "ok"
        }
        data.put("activity_snapshot", section(activityStatus).snapshot())
        val health = safely { HealthPermissions.status(context) }
        data.put("health_status", section(if (health.optString("status") == "ok") "ok" else "unavailable").snapshot().put("details", health))
        val healthAvailable = health.optString("status") == "ok"
        val grantedPermissions = health.optJSONArray("granted_permissions")?.let { array ->
            (0 until array.length()).map { array.getString(it) }.toSet()
        } ?: emptySet()
        val client = if (healthAvailable) HealthConnectClient.getOrCreate(context) else null
        HealthPermissions.recordTypes.forEach { type ->
            val permission = HealthPermission.getReadPermission(type)
            val status = when {
                client == null || !HealthPermissions.supported(client, type) -> "unavailable"
                permission !in grantedPermissions -> "denied"
                background && HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND !in grantedPermissions -> "background_denied"
                else -> "ok"
            }
            data.put(HealthPermissions.recordKey(type), section(status).put("permission", permission))
        }
        data
    }

    private suspend fun safely(block: suspend () -> JSONObject): JSONObject = try {
        block()
    } catch (exception: CancellationException) {
        throw exception
    } catch (exception: SecurityException) {
        section("denied").put("error_type", exception.javaClass.simpleName)
    } catch (exception: Exception) {
        // Error messages can contain source data. Export a class name without logging contents.
        section("error").put("error_type", exception.javaClass.simpleName)
    }

    @Suppress("DEPRECATION")
    private fun hasUsageAccess(): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= 29) {
            appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        } else {
            appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun usageUnavailable(): JSONObject? {
        if (!hasUsageAccess()) return section("denied").put("permission", "android.settings.USAGE_ACCESS_SETTINGS")
        if (!(context.getSystemService(Context.USER_SERVICE) as UserManager).isUserUnlocked) {
            return section("unavailable").put("reason", "device_user_locked")
        }
        return null
    }

    private fun usageStats(startMs: Long, endMs: Long): JSONObject {
        usageUnavailable()?.let { return it }
        val manager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val records = JSONArray()
        manager.queryAndAggregateUsageStats(startMs, endMs).values.sortedBy { it.packageName }.forEach { stats ->
            val item = JSONObject()
                .put("package_name", stats.packageName)
                .put("first_timestamp_ms", stats.firstTimeStamp)
                .put("last_timestamp_ms", stats.lastTimeStamp)
                .put("last_time_used_ms", stats.lastTimeUsed)
                .put("total_foreground_ms", stats.totalTimeInForeground)
            if (Build.VERSION.SDK_INT >= 29) {
                item.put("total_visible_ms", stats.totalTimeVisible)
                    .put("total_foreground_service_ms", stats.totalTimeForegroundServiceUsed)
                    .put("last_visible_ms", stats.lastTimeVisible)
                    .put("last_foreground_service_ms", stats.lastTimeForegroundServiceUsed)
            }
            records.put(item)
        }
        return section("ok", records)
            .put("aggregation", "android_query_and_aggregate_usage_stats")
            .put("interval_note", "Android aggregates overlapping retained buckets; timestamps may extend outside the requested window. Use chronological events for precise transitions.")
            .put("retention_note", "Android controls usage history retention; an empty window does not establish that no usage occurred.")
    }

    private fun usageEvents(startMs: Long, endMs: Long): JSONObject {
        usageUnavailable()?.let { return it }
        val manager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val events = manager.queryEvents(startMs, endMs)
            ?: return section("unavailable").put("reason", "android_returned_null")
        val records = JSONArray()
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            if (records.length() >= MAX_RECORDS) return overflow("usage_events", records.length())
            if (!events.getNextEvent(event)) return section("error").put("reason", "event_iteration_failed")
            val item = JSONObject()
                .put("timestamp_ms", event.timeStamp)
                .put("event_type", event.eventType)
                .put("event_names", EVENT_NAMES[event.eventType] ?: JSONArray())
                .put("package_name", event.packageName ?: JSONObject.NULL)
                .put("class_name", event.className ?: JSONObject.NULL)
            // No filter: screen, unlock, notification interaction, service, and future numeric
            // event types are preserved alongside activity foreground/background transitions.
            if (event.eventType == UsageEvents.Event.SHORTCUT_INVOCATION) item.put("shortcut_id", event.shortcutId ?: JSONObject.NULL)
            if (Build.VERSION.SDK_INT >= 28) {
                if (event.eventType == UsageEvents.Event.STANDBY_BUCKET_CHANGED) item.put("app_standby_bucket", event.appStandbyBucket)
            }
            if (event.eventType == UsageEvents.Event.CONFIGURATION_CHANGE) {
                event.configuration?.let { config ->
                    item.put("configuration", JSONObject()
                        .put("orientation", config.orientation).put("screen_layout", config.screenLayout)
                        .put("screen_width_dp", config.screenWidthDp).put("screen_height_dp", config.screenHeightDp)
                        .put("ui_mode", config.uiMode).put("font_scale", config.fontScale.toDouble()))
                }
            }
            records.put(item)
        }
        return section("ok", records).put("retention_note", "Android usually retains detailed events for only a few days; retention varies by device.")
    }

    private fun usageEventStats(startMs: Long, endMs: Long): JSONObject {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return section("unavailable").put("reason", "android_api_level_not_supported").put("minimum_sdk_int", 28)
        }
        usageUnavailable()?.let { return it }
        val manager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val records = JSONArray()
        manager.queryEventStats(UsageStatsManager.INTERVAL_DAILY, startMs, endMs)?.forEach { stats ->
            records.put(JSONObject().put("event_type", stats.eventType)
                .put("event_names", EVENT_NAMES[stats.eventType] ?: JSONArray())
                .put("count", stats.count).put("total_time_ms", stats.totalTime)
                .put("first_timestamp_ms", stats.firstTimeStamp).put("last_timestamp_ms", stats.lastTimeStamp)
                .put("last_event_time_ms", stats.lastEventTime))
        } ?: return section("unavailable").put("reason", "android_returned_null")
        return section("ok", records).put("aggregation", "android_daily_event_buckets")
    }

    @Suppress("DEPRECATION")
    private fun networkUsage(startMs: Long, endMs: Long, transport: String): JSONObject {
        if (!hasUsageAccess()) return section("denied").put("permission", "android.settings.USAGE_ACCESS_SETTINGS")
        val manager = context.getSystemService(Context.NETWORK_STATS_SERVICE) as NetworkStatsManager
        val type = if (transport == "wifi") ConnectivityManager.TYPE_WIFI else ConnectivityManager.TYPE_MOBILE
        val records = JSONArray()
        // Null subscriber ID avoids collecting SIM identifiers and, on API 29+, means all
        // mobile networks. Older Android builds may reject it; expose that transport's status.
        val summary = manager.querySummary(type, null, startMs, endMs)
            ?: return section("unavailable").put("reason", "android_returned_null").put("transport", transport)
        try {
            val bucket = NetworkStats.Bucket()
            while (summary.hasNextBucket()) {
                if (records.length() >= MAX_RECORDS) return overflow("network_usage_$transport", records.length())
                if (!summary.getNextBucket(bucket)) return section("error").put("reason", "bucket_iteration_failed")
                val packages = context.packageManager.getPackagesForUid(bucket.uid)?.toList() ?: emptyList()
                records.put(JSONObject()
                    .put("transport", transport).put("uid", bucket.uid).put("package_names", JSONArray(packages))
                    .put("start_ms", bucket.startTimeStamp).put("end_ms", bucket.endTimeStamp)
                    .put("rx_bytes", bucket.rxBytes).put("tx_bytes", bucket.txBytes)
                    .put("rx_packets", bucket.rxPackets).put("tx_packets", bucket.txPackets)
                    .put("state", bucket.state).put("metered", bucket.metered)
                    .put("roaming", bucket.roaming).put("tag", bucket.tag))
            }
        } finally {
            summary.close()
        }
        return section("ok", records).put("transport", transport)
            .put("aggregation", "android_uid_summary_buckets")
            .put("interval_note", "Android network accounting uses coarse buckets and may lag live traffic; shared UIDs can identify several packages.")
    }

    @Suppress("DEPRECATION")
    private fun visibleApps(): JSONObject {
        val manager = context.packageManager
        val records = JSONArray()
        val installed = if (Build.VERSION.SDK_INT >= 33) {
            manager.getInstalledPackages(PackageManager.PackageInfoFlags.of(0))
        } else manager.getInstalledPackages(0)
        installed.sortedBy { it.packageName }.forEach { info ->
            val app = info.applicationInfo
            val item = JSONObject().put("package_name", info.packageName)
                .put("version_name", info.versionName ?: JSONObject.NULL)
                .put("version_code", if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong())
                .put("first_install_ms", info.firstInstallTime).put("last_update_ms", info.lastUpdateTime)
            if (app != null) item.put("uid", app.uid).put("label", manager.getApplicationLabel(app).toString())
                .put("enabled", app.enabled).put("system_app", app.flags and ApplicationInfo.FLAG_SYSTEM != 0)
            records.put(item)
        }
        return section("ok", records).snapshot()
            .put("visibility_note", "Android package visibility limits this list. Usage/network records can include packages absent from metadata.")
    }

    private fun deviceSnapshot(): JSONObject {
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val capabilities = connectivity.activeNetwork?.let { connectivity.getNetworkCapabilities(it) }
        val storage = StatFs(Environment.getDataDirectory().absolutePath)
        val memory = ActivityManager.MemoryInfo().also {
            (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(it)
        }
        val transports = JSONArray()
        capabilities?.let { network ->
            listOf("wifi" to NetworkCapabilities.TRANSPORT_WIFI, "cellular" to NetworkCapabilities.TRANSPORT_CELLULAR,
                "ethernet" to NetworkCapabilities.TRANSPORT_ETHERNET, "vpn" to NetworkCapabilities.TRANSPORT_VPN,
                "bluetooth" to NetworkCapabilities.TRANSPORT_BLUETOOTH).forEach { (name, type) ->
                if (network.hasTransport(type)) transports.put(name)
            }
        }
        val item = JSONObject()
            .put("captured_at_ms", System.currentTimeMillis()).put("sdk_int", Build.VERSION.SDK_INT)
            .put("android_release", Build.VERSION.RELEASE).put("manufacturer", Build.MANUFACTURER)
            .put("model", Build.MODEL).put("timezone", TimeZone.getDefault().id)
            .put("elapsed_realtime_ms", SystemClock.elapsedRealtime())
            .put("interactive", power.isInteractive).put("power_save_mode", power.isPowerSaveMode)
            .put("battery_optimization_exempt", power.isIgnoringBatteryOptimizations(context.packageName))
            .put("device_idle_mode", power.isDeviceIdleMode)
            .put("internal_storage_total_bytes", storage.totalBytes).put("internal_storage_available_bytes", storage.availableBytes)
            .put("ram_total_bytes", memory.totalMem).put("ram_available_bytes", memory.availMem)
            .put("network", JSONObject().put("transports", transports).put("metered", connectivity.isActiveNetworkMetered)
                .put("internet_capable", capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ?: false)
                .put("validated", capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) ?: false))
        val batteryData = JSONObject().put("charging", batteryManager.isCharging)
        if (battery != null) {
            batteryData.put("level", battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1))
                .put("scale", battery.getIntExtra(BatteryManager.EXTRA_SCALE, -1))
                .put("status", battery.getIntExtra(BatteryManager.EXTRA_STATUS, -1))
                .put("health", battery.getIntExtra(BatteryManager.EXTRA_HEALTH, -1))
                .put("plugged", battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0))
                .put("voltage_mv", battery.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1))
                .put("temperature_tenths_celsius", battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1))
        }
        item.put("battery", batteryData)
        return section("ok", JSONArray().put(item)).snapshot()
    }

    private fun calendarEvents(startMs: Long, endMs: Long): JSONObject {
        if (!granted(Manifest.permission.READ_CALENDAR)) return section("denied").put("permission", Manifest.permission.READ_CALENDAR)
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, startMs)
            ContentUris.appendId(it, endMs)
        }.build()
        val columns = arrayOf(
            CalendarContract.Instances.EVENT_ID, CalendarContract.Instances.CALENDAR_ID,
            CalendarContract.Instances.BEGIN, CalendarContract.Instances.END,
            CalendarContract.Instances.TITLE, CalendarContract.Instances.DESCRIPTION,
            CalendarContract.Instances.EVENT_LOCATION, CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.EVENT_TIMEZONE, CalendarContract.Instances.STATUS,
            CalendarContract.Instances.AVAILABILITY, CalendarContract.Instances.SELF_ATTENDEE_STATUS,
        )
        val names = arrayOf("event_id", "calendar_id", "begin_ms", "end_ms", "title", "description",
            "location", "all_day", "event_timezone", "status", "availability", "self_attendee_status")
        val records = JSONArray()
        context.contentResolver.query(uri, columns, null, null, CalendarContract.Instances.BEGIN + " ASC")?.use { cursor ->
            while (cursor.moveToNext()) {
                if (records.length() >= MAX_RECORDS) return overflow("calendar_events", records.length())
                val item = JSONObject()
                names.forEachIndexed { index, name ->
                    item.put(name, if (cursor.isNull(index)) JSONObject.NULL else when (cursor.getType(index)) {
                        android.database.Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(index)
                        android.database.Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(index)
                        else -> cursor.getString(index)
                    })
                }
                records.put(item)
            }
        } ?: return section("unavailable").put("reason", "calendar_provider_returned_null")
        return section("ok", records).put("history_note", "Calendar entries describe scheduled events; they do not establish attendance or user activity.")
    }

    @SuppressLint("MissingPermission")
    private suspend fun locationSnapshot(background: Boolean): JSONObject {
        if (!granted(Manifest.permission.ACCESS_FINE_LOCATION) && !granted(Manifest.permission.ACCESS_COARSE_LOCATION)) {
            return section("denied").put("permission", "android.permission.ACCESS_COARSE_LOCATION or ACCESS_FINE_LOCATION")
        }
        if (background && Build.VERSION.SDK_INT >= 29 && !granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)) {
            return section("background_denied").put("permission", Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        }
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val records = JSONArray()
        val providers = JSONObject()
        manager.getProviders(true).forEach { provider ->
            try {
                manager.getLastKnownLocation(provider)?.let { records.put(locationJson(it, "last_known")) }
                providers.put(provider, "ok")
            } catch (_: SecurityException) {
                providers.put(provider, "denied")
            } catch (exception: Exception) {
                providers.put(provider, exception.javaClass.simpleName)
            }
        }
        // A short foreground request enriches the snapshot when the user actively starts sync.
        // Background workers use caches so they do not initiate continuous location tracking.
        if (!background && Build.VERSION.SDK_INT >= 30) {
            val provider = if (granted(Manifest.permission.ACCESS_FINE_LOCATION) && manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                LocationManager.GPS_PROVIDER
            } else if (manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) LocationManager.NETWORK_PROVIDER else null
            if (provider != null) {
                val current = withTimeoutOrNull(8_000) {
                    suspendCancellableCoroutine<Location?> { continuation ->
                        val cancellation = CancellationSignal()
                        continuation.invokeOnCancellation { cancellation.cancel() }
                        try {
                            manager.getCurrentLocation(provider, cancellation, context.mainExecutor) { location ->
                                if (continuation.isActive) continuation.resume(location)
                            }
                        } catch (_: Exception) {
                            if (continuation.isActive) continuation.resume(null)
                        }
                    }
                }
                current?.let { records.put(locationJson(it, "current")) }
            }
        }
        return section("ok", records).snapshot().put("providers", providers)
            .put("precise_permission", granted(Manifest.permission.ACCESS_FINE_LOCATION))
            .put("history_note", "Android does not provide device-wide location history. These are available cached/current positions, possibly stale.")
    }

    private fun locationJson(location: Location, source: String): JSONObject = JSONObject()
        .put("source", source).put("provider", location.provider ?: JSONObject.NULL)
        .put("timestamp_ms", location.time).put("elapsed_realtime_nanos", location.elapsedRealtimeNanos)
        .put("latitude", location.latitude).put("longitude", location.longitude)
        .put("accuracy_m", if (location.hasAccuracy()) location.accuracy.toDouble() else JSONObject.NULL)
        .put("altitude_m", if (location.hasAltitude()) location.altitude else JSONObject.NULL)
        .put("speed_mps", if (location.hasSpeed()) location.speed.toDouble() else JSONObject.NULL)
        .put("bearing_degrees", if (location.hasBearing()) location.bearing.toDouble() else JSONObject.NULL)
        .put("mock", if (Build.VERSION.SDK_INT >= 31) location.isMock else @Suppress("DEPRECATION") location.isFromMockProvider)

    @SuppressLint("MissingPermission")
    private suspend fun activitySnapshot(): JSONObject {
        if (Build.VERSION.SDK_INT >= 29 && !granted(Manifest.permission.ACTIVITY_RECOGNITION)) {
            return section("denied").put("permission", Manifest.permission.ACTIVITY_RECOGNITION)
        }
        val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val sensor = manager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
            ?: return section("unavailable").put("reason", "step_counter_sensor_absent")
        val reading = withTimeoutOrNull(2_000) {
            suspendCancellableCoroutine<JSONObject?> { continuation ->
                val listener = object : SensorEventListener {
                    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
                    override fun onSensorChanged(event: SensorEvent) {
                        manager.unregisterListener(this)
                        if (continuation.isActive) continuation.resume(JSONObject()
                            .put("captured_at_ms", System.currentTimeMillis())
                            .put("sensor_timestamp_nanos", event.timestamp)
                            .put("steps_since_reboot", event.values[0].toDouble())
                            .put("sensor_name", sensor.name).put("accuracy", event.accuracy))
                    }
                }
                continuation.invokeOnCancellation { manager.unregisterListener(listener) }
                if (!manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL)) {
                    if (continuation.isActive) continuation.resume(null)
                }
            }
        }
        if (reading == null) return section("unavailable").snapshot().put("reason", "no_sensor_reading_within_timeout")
        return section("ok", JSONArray().put(reading)).snapshot()
            .put("history_note", "A counter snapshot since reboot, not historical steps by day or an inferred activity classification. Health Connect supplies recorded history.")
    }

    private suspend fun collectHealth(data: JSONObject, startMs: Long, endMs: Long, background: Boolean, sections: Set<String>?) {
        val healthStatus = safely { HealthPermissions.status(context) }
        if (wanted(sections, "health_status")) data.put("health_status", section(if (healthStatus.optString("status") == "ok") "ok" else "unavailable")
            .snapshot().put("details", healthStatus))
        val types = HealthPermissions.recordTypes.filter { wanted(sections, HealthPermissions.recordKey(it)) }
        val sdkStatus = HealthConnectClient.getSdkStatus(context)
        if (sdkStatus != HealthConnectClient.SDK_AVAILABLE) {
            types.forEach { type ->
                data.put(HealthPermissions.recordKey(type), section("unavailable").put("sdk_status", sdkStatus))
            }
            return
        }
        var client: HealthConnectClient? = null
        var permissions: Set<String> = emptySet()
        val setup = safely {
            client = HealthConnectClient.getOrCreate(context)
            permissions = client!!.permissionController.getGrantedPermissions()
            section("ok")
        }
        if (setup.optString("status") != "ok") {
            types.forEach { data.put(HealthPermissions.recordKey(it), JSONObject(setup.toString())) }
            return
        }
        val healthClient = client!!
        types.forEach { type ->
            val permission = HealthPermission.getReadPermission(type)
            val result = safely {
                when {
                    !HealthPermissions.supported(healthClient, type) -> section("unavailable").put("reason", "health_feature_not_supported")
                    permission !in permissions -> section("denied").put("permission", permission)
                    background && HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND !in permissions ->
                        section("background_denied").put("permission", HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND)
                    else -> readHealthType(healthClient, type, startMs, endMs, healthStatus.optLong("history_read_start_ms", System.currentTimeMillis() - 30L * DAY_MS))
                }
            }
            result.put("record_type", type.java.simpleName)
                .put("history_permission_granted", HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY in permissions)
            data.put(HealthPermissions.recordKey(type), result)
        }
    }

    private suspend fun <T : Record> readHealthType(client: HealthConnectClient, type: KClass<T>, startMs: Long, endMs: Long, lowerBoundMs: Long): JSONObject {
        val records = JSONArray()
        val effectiveStart = maxOf(startMs, lowerBoundMs)
        if (effectiveStart >= endMs) {
            return section("ok").put("reason", "outside_granted_health_history")
                .put("history_read_start_ms", lowerBoundMs).put("history_window_limited", true)
                .put("history_bound_is_conservative", lowerBoundMs > 0)
        }
        var token: String? = null
        var pages = 0
        val seenTokens = mutableSetOf<String>()
        do {
            if (pages >= MAX_HEALTH_PAGES) return overflow(type.java.simpleName, records.length()).put("pages_read", pages)
            val response = client.readRecords(ReadRecordsRequest(
                recordType = type,
                timeRangeFilter = TimeRangeFilter.between(Instant.ofEpochMilli(effectiveStart), Instant.ofEpochMilli(endMs)),
                ascendingOrder = true, pageSize = HEALTH_PAGE_SIZE, pageToken = token,
            ))
            response.records.forEach { records.put(HealthRecordJson.encode(it)) }
            pages += 1
            token = response.pageToken
            if (token != null && !seenTokens.add(token!!)) {
                return section("error").put("reason", "health_pagination_token_repeated").put("pages_read", pages)
            }
        } while (token != null)
        return section("ok", records).put("pages_read", pages)
            .put("history_read_start_ms", lowerBoundMs).put("history_window_limited", effectiveStart != startMs)
            .put("effective_window_start_ms", effectiveStart)
            .put("record_note", "Raw records preserve each data origin and source ID. Cumulative records from multiple apps can overlap; use Health Connect aggregation for deduplicated totals.")
            .put("route_note", "Exercise route data is included only when Android returns it; third-party routes may require separate per-session foreground consent.")
    }

    private fun granted(permission: String): Boolean = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun wanted(sections: Set<String>?, key: String): Boolean = sections == null || key in sections

    private fun section(status: String, records: JSONArray = JSONArray()): JSONObject = JSONObject()
        .put("status", status).put("complete", status == "ok").put("records", records)

    private fun overflow(source: String, count: Int): JSONObject = section("error")
        .put("reason", "window_exceeds_record_limit").put("source", source).put("observed_count", count)
        .put("retry_hint", "split_window")
        .put("retry_note", "Split the history window and retry; the source cursor must remain unchanged.")

    private fun JSONObject.snapshot(): JSONObject = put("mode", "snapshot").put("captured_at_ms", System.currentTimeMillis())

    companion object {
        private const val DAY_MS = 86_400_000L
        private const val MAX_RECORDS = 100_000
        private const val MAX_HEALTH_PAGES = 100
        private const val HEALTH_PAGE_SIZE = 1000

        private val EVENT_NAMES: Map<Int, JSONArray> = UsageEvents.Event::class.java.fields.asSequence()
            .filter { Modifier.isStatic(it.modifiers) && it.type == Int::class.javaPrimitiveType }
            .mapNotNull { field -> runCatching { field.getInt(null) to field.name }.getOrNull() }
            .groupBy({ it.first }, { it.second }).mapValues { JSONArray(it.value.sorted()) }
    }
}
