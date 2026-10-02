package com.forma.datasync.collectors

import androidx.health.connect.client.records.*
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Mass
import java.time.Instant
import java.time.ZoneOffset
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class HealthRecordJsonTest {
    private val start = Instant.parse("2026-09-01T08:00:00Z")
    private val end = start.plusSeconds(3600)
    private val metadata = Metadata.manualEntry(clientRecordId = "source-fixture", clientRecordVersion = 7)

    @Test fun heartRateKeepsEverySampleAndSourceMetadata() {
        val record = HeartRateRecord(start, ZoneOffset.UTC, end, ZoneOffset.UTC,
            listOf(HeartRateRecord.Sample(start.plusSeconds(10), 65),
                HeartRateRecord.Sample(start.plusSeconds(20), 72)), metadata)
        val json = HealthRecordJson.encode(record) as JSONObject
        assertEquals(2, json.getJSONArray("samples").length())
        assertEquals(72L, json.getJSONArray("samples").getJSONObject(1).getLong("beatsPerMinute"))
        assertEquals(start.toEpochMilli(), json.getJSONObject("startTime").getLong("epoch_ms"))
        assertEquals(start.toString(), json.getJSONObject("startTime").getString("iso"))
        assertEquals("source-fixture", json.getJSONObject("metadata").getString("clientRecordId"))
        assertEquals(7L, json.getJSONObject("metadata").getLong("clientRecordVersion"))
        assertTrue(json.getJSONObject("metadata").has("dataOrigin"))
    }

    @Test fun sleepKeepsStageBoundariesAndUnknownZoneOffsets() {
        val record = SleepSessionRecord(start, null, end, null, metadata,
            stages = listOf(SleepSessionRecord.Stage(start, start.plusSeconds(1200), SleepSessionRecord.STAGE_TYPE_LIGHT),
                SleepSessionRecord.Stage(start.plusSeconds(1200), end, SleepSessionRecord.STAGE_TYPE_DEEP)))
        val json = HealthRecordJson.encode(record) as JSONObject
        assertEquals(2, json.getJSONArray("stages").length())
        assertEquals(SleepSessionRecord.STAGE_TYPE_DEEP, json.getJSONArray("stages").getJSONObject(1).getInt("stage"))
        assertEquals(end.toEpochMilli(), json.getJSONArray("stages").getJSONObject(1).getJSONObject("endTime").getLong("epoch_ms"))
        assertTrue(json.isNull("startZoneOffset"))
    }

    @Test fun bodyMassCarriesExplicitUnits() {
        val json = HealthRecordJson.encode(WeightRecord(start, ZoneOffset.UTC, Mass.kilograms(74.5), metadata)) as JSONObject
        assertEquals(74.5, json.getJSONObject("weight").getDouble("kilograms"), 0.00001)
        assertEquals(74500.0, json.getJSONObject("weight").getDouble("grams"), 0.00001)
    }

    @Test fun plannedExerciseKeepsItsNonstandardPublicProperty() {
        val record = PlannedExerciseSessionRecord(start, ZoneOffset.UTC, end, ZoneOffset.UTC, metadata,
            blocks = emptyList(), exerciseType = ExerciseSessionRecord.EXERCISE_TYPE_WALKING)
        assertTrue((HealthRecordJson.encode(record) as JSONObject).getBoolean("hasExplicitTime"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidMeasurementFailsInsteadOfDroppingData() { HealthRecordJson.encode(Double.NaN) }

    @Test(expected = IllegalArgumentException::class)
    fun unknownPropertyFailsInsteadOfLosingItsValue() { HealthRecordJson.encode(java.util.Locale.US) }
}
