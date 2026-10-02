package com.forma.datasync.sync

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.os.Process
import androidx.core.content.ContextCompat
import com.forma.datasync.collectors.HealthPermissions
import org.json.JSONObject

object PermissionStatus {
    suspend fun read(context: Context): JSONObject {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val usageMode = if (Build.VERSION.SDK_INT >= 29) {
            appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        }
        val location = granted(context, Manifest.permission.ACCESS_FINE_LOCATION) || granted(context, Manifest.permission.ACCESS_COARSE_LOCATION)
        val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return JSONObject()
            .put("usageAccess", usageMode == AppOpsManager.MODE_ALLOWED)
            .put("notifications", Build.VERSION.SDK_INT < 33 || granted(context, Manifest.permission.POST_NOTIFICATIONS))
            .put("activityRecognition", Build.VERSION.SDK_INT < 29 || granted(context, Manifest.permission.ACTIVITY_RECOGNITION))
            .put("calendar", granted(context, Manifest.permission.READ_CALENDAR))
            .put("location", location)
            .put("backgroundLocation", location && (Build.VERSION.SDK_INT < 29 || granted(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION)))
            .put("batteryUnrestricted", power.isIgnoringBatteryOptimizations(context.packageName))
            .put("health", HealthPermissions.status(context))
    }

    fun runtimePermissions(context: Context): Array<String> {
        val permissions = mutableListOf(Manifest.permission.READ_CALENDAR,
            Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 29) permissions.add(Manifest.permission.ACTIVITY_RECOGNITION)
        if (Build.VERSION.SDK_INT >= 33) permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        // Background location must be requested separately, after a foreground location grant.
        return permissions.filterNot { granted(context, it) }.toTypedArray()
    }

    private fun granted(context: Context, permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}
