package com.forma.datasync.collectors

import android.content.Context
import androidx.activity.result.contract.ActivityResultContract
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.feature.ExperimentalMindfulnessSessionApi
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.*
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import kotlin.reflect.KClass

/** Read-only permissions: no health data is created or changed by this application. */
@OptIn(ExperimentalMindfulnessSessionApi::class)
object HealthPermissions {
    const val PROVIDER_PACKAGE = "com.google.android.apps.healthdata"

    // Keep this list aligned with the manifest and connect-client 1.1.0's permission map.
    val recordTypes: List<KClass<out Record>> = listOf(
        ActiveCaloriesBurnedRecord::class, BasalBodyTemperatureRecord::class,
        BasalMetabolicRateRecord::class, BloodGlucoseRecord::class,
        BloodPressureRecord::class, BodyFatRecord::class, BodyTemperatureRecord::class,
        BodyWaterMassRecord::class, BoneMassRecord::class, CervicalMucusRecord::class,
        CyclingPedalingCadenceRecord::class, DistanceRecord::class,
        ElevationGainedRecord::class, ExerciseSessionRecord::class, FloorsClimbedRecord::class,
        HeartRateRecord::class, HeartRateVariabilityRmssdRecord::class, HeightRecord::class,
        HydrationRecord::class, IntermenstrualBleedingRecord::class, LeanBodyMassRecord::class,
        MenstruationFlowRecord::class, MenstruationPeriodRecord::class,
        MindfulnessSessionRecord::class, NutritionRecord::class, OvulationTestRecord::class,
        OxygenSaturationRecord::class, PlannedExerciseSessionRecord::class, PowerRecord::class,
        RespiratoryRateRecord::class, RestingHeartRateRecord::class, SexualActivityRecord::class,
        SleepSessionRecord::class, SpeedRecord::class, SkinTemperatureRecord::class,
        StepsCadenceRecord::class, StepsRecord::class, TotalCaloriesBurnedRecord::class,
        Vo2MaxRecord::class, WeightRecord::class, WheelchairPushesRecord::class,
    )

    fun recordKey(type: KClass<out Record>): String = "health_" +
        type.java.simpleName.removeSuffix("Record")
            .replace(Regex("([a-z0-9])([A-Z])"), "$1_$2").lowercase()

    fun featureAvailable(client: HealthConnectClient, feature: Int): Boolean =
        client.features.getFeatureStatus(feature) == HealthConnectFeatures.FEATURE_STATUS_AVAILABLE

    fun supported(client: HealthConnectClient, type: KClass<out Record>): Boolean = when (type) {
        SkinTemperatureRecord::class -> featureAvailable(client, HealthConnectFeatures.FEATURE_SKIN_TEMPERATURE)
        PlannedExerciseSessionRecord::class -> featureAvailable(client, HealthConnectFeatures.FEATURE_PLANNED_EXERCISE)
        MindfulnessSessionRecord::class -> featureAvailable(client, HealthConnectFeatures.FEATURE_MINDFULNESS_SESSION)
        else -> true
    }

    fun requestedPermissions(context: Context): Set<String> {
        if (HealthConnectClient.getSdkStatus(context) != HealthConnectClient.SDK_AVAILABLE) return emptySet()
        val client = HealthConnectClient.getOrCreate(context)
        return buildSet {
            recordTypes.filter { supported(client, it) }.forEach { add(HealthPermission.getReadPermission(it)) }
            if (featureAvailable(client, HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_IN_BACKGROUND)) {
                add(HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND)
            }
            if (featureAvailable(client, HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_HISTORY)) {
                add(HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY)
            }
        }
    }

    fun permissionContract(): ActivityResultContract<Set<String>, Set<String>> =
        PermissionController.createRequestPermissionResultContract(PROVIDER_PACKAGE)

    suspend fun status(context: Context): JSONObject {
        val sdkStatus = HealthConnectClient.getSdkStatus(context)
        val result = JSONObject()
            .put("sdk_status", sdkStatus)
            .put("provider_package", PROVIDER_PACKAGE)
            .put("status", when (sdkStatus) {
                HealthConnectClient.SDK_AVAILABLE -> "ok"
                HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> "update_required"
                else -> "unavailable"
            })
            .put("requested_permissions", JSONArray())
            .put("granted_permissions", JSONArray())
            .put("background_supported", false)
            .put("background_granted", false)
            .put("history_supported", false)
            .put("history_granted", false)
        if (sdkStatus != HealthConnectClient.SDK_AVAILABLE) return result
        try {
            val client = HealthConnectClient.getOrCreate(context)
            val granted = client.permissionController.getGrantedPermissions()
            result.put("requested_permissions", JSONArray(requestedPermissions(context).sorted()))
                .put("granted_permissions", JSONArray(granted.sorted()))
                .put("background_supported", featureAvailable(client, HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_IN_BACKGROUND))
                .put("background_granted", HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND in granted)
                .put("history_supported", featureAvailable(client, HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_HISTORY))
                .put("history_granted", HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY in granted)
                .put("history_limit_note", "Without history access Android normally exposes at most 30 days before the first permission grant; available source history may be shorter.")
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            result.put("status", "error").put("error_type", exception.javaClass.simpleName)
        }
        return result
    }
}
