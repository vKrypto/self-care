"""Recorded Android-shaped fixtures exercise daily projection without live data."""

from datetime import datetime, timezone
import json
import sqlite3
from uuid import uuid4

import pytest

from data_sync.native_app.server import wellbeing as w


def ms(value):
    return int(datetime.fromisoformat(value.replace("Z", "+00:00")).timestamp() * 1000)


@pytest.fixture
def con():
    connection = sqlite3.connect(":memory:")
    connection.row_factory = sqlite3.Row
    connection.execute("PRAGMA foreign_keys=ON")
    connection.executescript("""
        CREATE TABLE accounts(id TEXT PRIMARY KEY);
        CREATE TABLE profiles(tenant TEXT PRIMARY KEY REFERENCES accounts(id),data TEXT);
        CREATE TABLE native_devices(device_id TEXT PRIMARY KEY,tenant TEXT NOT NULL REFERENCES accounts(id),name TEXT,
            registered_at_ms INTEGER,UNIQUE(tenant,device_id));
        CREATE TABLE native_batches(tenant TEXT,batch_id TEXT,device_id TEXT,payload TEXT,received_at_ms INTEGER);
        INSERT INTO accounts VALUES('member');
        INSERT INTO accounts VALUES('other');
        INSERT INTO native_devices VALUES('phone','member','Phone',1);
        INSERT INTO native_devices VALUES('second','member','Second phone',2);
        INSERT INTO native_devices VALUES('outsider','other','Private phone',3);
    """)
    w.init_wellbeing_db(connection)
    yield connection
    connection.close()


def section(records=(), status="ok", **extra):
    return {"status": status, "complete": status == "ok", "records": list(records), **extra}


def event(stamp, kind, package="com.example", clazz="Main"):
    return {"timestamp_ms": ms(stamp), "event_type": kind, "package_name": package, "class_name": clazz}


def payload(data, start="2026-09-30T00:00:00Z", end="2026-10-01T00:00:00Z", collected=None):
    return {"window": {"start_ms": ms(start), "end_ms": ms(end)}, "collected_at_ms": ms(collected or end), "data": data}


def ingest(con, body, device="phone", tenant="member", identifier=None):
    return w.derive_batch(con, tenant, device, identifier or str(uuid4()), body, body["collected_at_ms"])


def day(con, selected="2026-09-30", device="phone", tenant="member"):
    return w.read_wellbeing(con, tenant, device_id=device, start_date=selected, end_date=selected)["days"][0]


def health(kind, start, end=None, origin="com.watch", identifier="record", **values):
    result = {"_type": kind, "metadata": {"id": identifier, "dataOrigin": {"packageName": origin},
              "lastModifiedTime": {"epoch_ms": ms(end or start)}, "clientRecordVersion": 1}, **values}
    if end:
        result.update(startTime={"epoch_ms": ms(start)}, endTime={"epoch_ms": ms(end)})
    else:
        result["time"] = {"epoch_ms": ms(start)}
    return result


def test_overlapping_events_and_batch_retry_do_not_multiply_and_cross_midnight_is_split(con):
    records = [event("2026-09-30T23:50:00Z", 1), event("2026-09-30T23:50:00Z", 15),
               event("2026-10-01T00:10:00Z", 2), event("2026-10-01T00:10:00Z", 16),
               event("2026-10-01T00:00:00Z", 18)]
    body = payload({"usage_events": section(list(reversed(records)))}, end="2026-10-01T01:00:00Z")
    identifier = "same-batch"
    assert ingest(con, body, identifier=identifier)
    assert not ingest(con, body, identifier=identifier)
    ingest(con, body)
    assert con.execute("SELECT COUNT(*) FROM native_wellbeing_facts").fetchone()[0] == 5
    first, second = day(con), day(con, "2026-10-01")
    assert first["usage"]["foreground_ms"] == 600_000
    assert second["usage"]["foreground_ms"] == 600_000
    assert first["usage"]["screen_ms"] == second["usage"]["screen_ms"] == 600_000
    assert first["usage"]["unlocks"] == 0
    assert second["usage"]["unlocks"] == 1


def test_new_out_of_order_export_closes_prior_cached_day(con):
    ingest(con, payload({"usage_events": section([event("2026-09-30T23:50:00Z", 1)])}))
    assert day(con)["usage"]["foreground_ms"] == 600_000
    ingest(con, payload({"usage_events": section([event("2026-10-01T00:05:00Z", 2)])},
                        start="2026-10-01T00:00:00Z", end="2026-10-01T01:00:00Z"))
    assert day(con)["usage"]["foreground_ms"] == 600_000
    assert day(con, "2026-10-01")["usage"]["foreground_ms"] == 300_000


def test_profile_timezone_overrides_device_and_dst_day_bounds(con):
    con.execute("INSERT INTO profiles VALUES('member',?)", (json.dumps({"timezone": "Asia/Kolkata"}),))
    ingest(con, payload({"device_snapshot": section([{"timezone": "UTC"}], mode="snapshot"),
                        "usage_events": section([event("2026-09-30T20:00:00Z", 1), event("2026-09-30T20:20:00Z", 2)])}))
    result = w.read_wellbeing(con, "member", device_id="phone", start_date="2026-10-01", end_date="2026-10-01")
    assert result["timezone"] == "Asia/Kolkata"
    assert result["days"][0]["usage"]["foreground_ms"] == 1_200_000
    start, end = w._day_bounds("2026-11-01", "America/New_York")
    assert end - start == 25 * 3_600_000


def test_device_zone_fallback_and_changed_profile_rebuilds_cached_days(con):
    ingest(con, payload({"device_snapshot": section([{"timezone": "Pacific/Honolulu"}], mode="snapshot"),
                        "usage_events": section([event("2026-09-30T01:00:00Z", 1), event("2026-09-30T01:10:00Z", 2)])}))
    assert w.account_zone(con, "member", "phone") == "Pacific/Honolulu"
    assert day(con, "2026-09-29")["usage"]["foreground_ms"] == 600_000
    con.execute("INSERT INTO profiles VALUES('member',?)", (json.dumps({"timezone": "UTC"}),))
    assert day(con)["usage"]["foreground_ms"] == 600_000
    assert day(con, "2026-09-29")["usage"]["foreground_ms"] is None


def test_android_daily_usage_snapshot_supersedes_not_summed(con):
    def bucket(foreground):
        return {"package_name": "com.example", "first_timestamp_ms": ms("2026-09-30T00:00:00Z"),
                "last_timestamp_ms": ms("2026-09-30T12:00:00Z"), "total_foreground_ms": foreground}
    ingest(con, payload({"usage_stats": section([bucket(1000)])}, end="2026-09-30T12:00:00Z"))
    ingest(con, payload({"usage_stats": section([bucket(2000)])}, end="2026-09-30T13:00:00Z"))
    ingest(con, payload({"usage_stats": section([bucket(300)])}, end="2026-09-30T11:00:00Z"))
    result = day(con)
    assert result["usage"]["foreground_ms"] == 2000
    assert result["usage"]["method"] == "android_bucket_estimate"
    assert result["usage"]["apps"][0]["foreground_ms"] == 2000


def test_multiday_usage_buckets_are_not_fabricated_into_daily_deltas(con):
    ingest(con, payload({"usage_stats": section([{"package_name": "example", "first_timestamp_ms": ms("2026-09-29T00:00:00Z"),
                        "last_timestamp_ms": ms("2026-10-01T00:00:00Z"), "total_foreground_ms": 8000}])}))
    assert day(con)["usage"]["foreground_ms"] is None


def test_network_overlapping_coarse_buckets_use_newest_and_different_uids_sum(con):
    def bucket(start, end, received, uid=1001):
        return {"start_ms": ms(start), "end_ms": ms(end), "rx_bytes": received, "tx_bytes": received // 2,
                "uid": uid, "state": 1, "metered": 1, "roaming": 1, "tag": 0}
    ingest(con, payload({"network_usage_wifi": section([bucket("2026-09-30T00:00:00Z", "2026-09-30T02:00:00Z", 200)])},
                        end="2026-09-30T02:00:00Z"))
    ingest(con, payload({"network_usage_wifi": section([bucket("2026-09-30T01:00:00Z", "2026-09-30T03:00:00Z", 400),
                        bucket("2026-09-30T01:00:00Z", "2026-09-30T03:00:00Z", 60, uid=1002)])}, end="2026-09-30T03:00:00Z"))
    assert day(con)["network"]["wifi_rx_bytes"] == 560
    assert day(con)["network"]["wifi_tx_bytes"] == 280


def test_health_record_ids_units_samples_and_midnight_allocation(con):
    data = {
        "health_steps": section([health("StepsRecord", "2026-09-30T23:00:00Z", "2026-10-01T01:00:00Z", count=2000)]),
        "health_distance": section([health("DistanceRecord", "2026-09-30T12:00:00Z", "2026-09-30T13:00:00Z", distance={"meters": 850})]),
        "health_active_calories_burned": section([health("ActiveCaloriesBurnedRecord", "2026-09-30T12:00:00Z", "2026-09-30T13:00:00Z", energy={"kilocalories": 100})]),
        "health_weight": section([health("WeightRecord", "2026-09-30T12:00:00Z", weight={"kilograms": 74.5, "grams": 74500})]),
        "health_heart_rate": section([health("HeartRateRecord", "2026-09-30T12:00:00Z", "2026-09-30T13:00:00Z", samples=[
            {"time": {"epoch_ms": ms("2026-09-30T12:10:00Z")}, "beatsPerMinute": 65},
            {"time": {"epoch_ms": ms("2026-09-30T12:20:00Z")}, "beatsPerMinute": 75}])]),
    }
    body = payload(data, end="2026-10-01T01:00:00Z")
    ingest(con, body)
    ingest(con, body)
    result = day(con)["health"]
    assert result["steps"]["value"] == 1000
    assert day(con, "2026-10-01")["health"]["steps"]["value"] == 1000
    assert result["distance"]["value"] == 850
    assert result["active_calories"]["value"] == 100
    assert result["weight"]["value"] == 74.5
    assert result["heart_rate"]["value"] == 70
    assert result["heart_rate"]["samples"] == 2
    assert result["heart_rate"]["min"] == 65
    assert result["heart_rate"]["max"] == 75


def test_duplicate_health_origins_and_devices_are_not_added(con):
    records = [health("StepsRecord", "2026-09-30T12:00:00Z", "2026-09-30T13:00:00Z", origin=origin, identifier=origin, count=1000)
               for origin in ("com.phone", "com.watch")]
    body = payload({"health_steps": section(records)})
    ingest(con, body)
    ingest(con, body, device="second")
    assert day(con)["health"]["steps"]["value"] == 1000
    assert day(con)["health"]["steps"]["origin_count"] == 2
    combined = day(con, device=None)
    assert combined["health"]["steps"]["value"] == 1000
    assert combined["health"]["steps"]["method"] == "reported_selected_device"


def test_overlapping_health_intervals_same_origin_do_not_add(con):
    records = [health("StepsRecord", "2026-09-30T12:00:00Z", "2026-09-30T14:00:00Z", identifier="first", count=2000),
               health("StepsRecord", "2026-09-30T13:00:00Z", "2026-09-30T15:00:00Z", identifier="second", count=3000)]
    ingest(con, payload({"health_steps": section(records)}))
    assert day(con)["health"]["steps"]["value"] == 4000


def test_sleep_and_exercise_are_unions_and_do_not_create_planned_statuses(con):
    records = [health("SleepSessionRecord", "2026-09-30T01:00:00Z", "2026-09-30T08:00:00Z", identifier="first"),
               health("SleepSessionRecord", "2026-09-30T07:00:00Z", "2026-09-30T09:00:00Z", identifier="second")]
    ingest(con, payload({"health_sleep_session": section(records),
                        "health_exercise_session": section([health("ExerciseSessionRecord", "2026-09-30T12:00:00Z", "2026-09-30T13:00:00Z")])}))
    result = day(con)
    assert result["health"]["sleep"]["value"] == 8 * 3_600_000
    assert result["health"]["exercise"]["value"] == 3_600_000
    assert "statuses" not in result


def test_denied_unavailable_empty_and_unknown_health_are_explicit(con):
    ingest(con, payload({"usage_events": section(status="denied"), "network_usage_wifi": section(status="error"),
                        "health_steps": section(status="background_denied"), "health_weight": section(),
                        "health_sleep_session": section(status="unavailable"),
                        "health_cervical_mucus": section([{"_type": "CervicalMucusRecord", "data": "preserved in raw history"}])}))
    result = day(con)
    assert result["usage"]["foreground_ms"] is None
    assert result["usage"]["status"] == "denied"
    assert result["health"]["steps"]["status"] == "background_denied"
    assert result["health"]["weight"]["status"] == "no_data"
    assert result["health"]["sleep"]["status"] == "unavailable"
    assert {r["source"] for r in result["sources"]} >= {"health_cervical_mucus"}
    assert all(m["value"] is None for m in result["metrics"])


def test_weekly_has_seven_days_and_no_data_days_stay_null(con):
    ingest(con, payload({"usage_events": section([event("2026-09-30T12:00:00Z", 1), event("2026-09-30T12:30:00Z", 2)])}))
    result = w.read_wellbeing(con, "member", "phone", "2026-09-30", "2026-09-30", "weekly")
    week = result["weeks"][0]
    assert week["start_date"] == "2026-09-28"
    assert week["end_date"] == "2026-10-04"
    assert len(week["days"]) == 7
    assert week["usage"]["foreground_ms"] == 1_800_000
    assert week["days"][0]["usage"]["foreground_ms"] is None
    assert len(result["days"]) == 1


def test_tenant_device_isolation_and_deletion_cascade(con):
    body = payload({"health_steps": section([health("StepsRecord", "2026-09-30T12:00:00Z", "2026-09-30T13:00:00Z", count=2000)])})
    ingest(con, body, device="outsider", tenant="other")
    assert day(con)["health"]["steps"]["value"] is None
    ingest(con, body)
    assert day(con)["health"]["steps"]["value"] == 2000
    con.execute("DELETE FROM native_devices WHERE tenant='member' AND device_id='phone'")
    for table in ("native_wellbeing_facts", "native_wellbeing_days", "native_wellbeing_batches", "native_wellbeing_observations"):
        assert con.execute(f"SELECT COUNT(*) FROM {table} WHERE tenant='member'").fetchone()[0] == 0
    assert day(con, tenant="other", device="outsider")["health"]["steps"]["value"] == 2000


def test_backfill_is_idempotent_and_preserves_original_payload(con):
    body = payload({"usage_events": section([event("2026-09-30T12:00:00Z", 1), event("2026-09-30T12:30:00Z", 2)])})
    raw = json.dumps(body)
    con.execute("INSERT INTO native_batches VALUES(?,?,?,?,?)", ("member", "old-batch", "phone", raw, body["collected_at_ms"]))
    assert w.backfill_wellbeing(con, "member") == 1
    assert w.backfill_wellbeing(con, "member") == 0
    assert day(con)["usage"]["foreground_ms"] == 1_800_000
    assert con.execute("SELECT payload FROM native_batches").fetchone()[0] == raw


@pytest.mark.parametrize("kwargs", [{"start_date": "bad"}, {"start_date": "2026-10-02", "end_date": "2026-10-01"},
                                    {"start_date": "2026-01-01", "end_date": "2026-10-01"}, {"period": "hourly"}])
def test_invalid_date_queries_are_bounded(con, kwargs):
    with pytest.raises(ValueError):
        w.read_wellbeing(con, "member", **kwargs)


def test_fact_retention_and_caps_keep_latest_data(con, monkeypatch):
    monkeypatch.setattr(w, "MAX_FACTS_PER_DEVICE", 2)
    ingest(con, payload({"usage_events": section([event("2026-09-30T11:00:00Z", 18), event("2026-09-30T12:00:00Z", 18),
                        event("2026-09-30T13:00:00Z", 18)])}))
    assert con.execute("SELECT COUNT(*) FROM native_wellbeing_facts").fetchone()[0] == 2
    assert day(con)["usage"]["unlocks"] == 2
    w.prune_wellbeing(con, "member", "phone", ms("2027-10-05T00:00:00Z"))
    assert con.execute("SELECT COUNT(*) FROM native_wellbeing_facts").fetchone()[0] == 0


def test_android_sdk_real_getters_pressure_glucose_cadence_and_signed_skin_delta(con):
    records = {
        "health_blood_pressure": section([health("BloodPressureRecord", "2026-09-30T12:00:00Z",
            systolic={"_type": "Pressure", "millimetersOfMercury": 120},
            diastolic={"_type": "Pressure", "millimetersOfMercury": 80})]),
        "health_blood_glucose": section([health("BloodGlucoseRecord", "2026-09-30T12:00:00Z",
            level={"_type": "BloodGlucose", "millimolesPerLiter": 5.5, "milligramsPerDeciliter": 99})]),
        "health_speed": section([health("SpeedRecord", "2026-09-30T12:00:00Z", "2026-09-30T13:00:00Z",
            samples=[{"time": {"epoch_ms": ms("2026-09-30T12:30:00Z")}, "speed": {"metersPerSecond": 2.5}}])]),
        "health_skin_temperature": section([health("SkinTemperatureRecord", "2026-09-30T12:00:00Z", "2026-09-30T13:00:00Z",
            deltas=[{"time": {"epoch_ms": ms("2026-09-30T12:30:00Z")}, "delta": {"celsius": -0.2}}])]),
        "health_basal_metabolic_rate": section([health("BasalMetabolicRateRecord", "2026-09-30T12:00:00Z",
            basalMetabolicRate={"watts": 77.48, "kilocaloriesPerDay": 1600})]),
        "health_nutrition": section([health("NutritionRecord", "2026-09-30T12:00:00Z", "2026-09-30T12:30:00Z",
            energy={"kilocalories": 550}, protein={"grams": 25}, vitaminC={"grams": 0.05})]),
    }
    ingest(con, payload(records))
    result = day(con)["health"]
    assert result["systolic_pressure"]["value"] == 120
    assert result["diastolic_pressure"]["value"] == 80
    assert result["blood_glucose"]["value"] == 5.5
    assert result["speed"]["value"] == 2.5
    assert result["skin_temperature_delta"]["value"] == -0.2
    assert result["basal_metabolic_rate"]["value"] == 1600
    assert result["nutrition_energy"]["value"] == 550
    assert result["nutrition_protein"]["value"] == 25
    assert result["nutrition_vitamin_c"]["value"] == 0.05


def test_multiactivity_foreground_is_union_and_collection_gaps_do_not_bridge(con):
    ingest(con, payload({"usage_events": section([
        event("2026-09-30T12:00:00Z", 1, clazz="Main"),
        event("2026-09-30T12:05:00Z", 1, clazz="Child"),
        event("2026-09-30T12:06:00Z", 2, clazz="Main"),
        event("2026-09-30T12:10:00Z", 2, clazz="Child"),
    ])}, start="2026-09-30T12:00:00Z", end="2026-09-30T12:30:00Z"))
    assert day(con)["usage"]["foreground_ms"] == 600_000
    ingest(con, payload({"usage_events": section([event("2026-09-30T13:00:00Z", 1)])},
                        start="2026-09-30T13:00:00Z", end="2026-09-30T13:10:00Z"))
    ingest(con, payload({"usage_events": section([event("2026-09-30T14:05:00Z", 2)])},
                        start="2026-09-30T14:00:00Z", end="2026-09-30T14:10:00Z"))
    assert day(con)["usage"]["foreground_ms"] == 1_200_000


def test_malformed_future_export_fields_never_break_receipt_derivation(con):
    huge = 10 ** 350
    ingest(con, payload({
        "usage_events": section([{ "timestamp_ms": huge, "event_type": huge, "package_name": {} },
                                 event("2026-09-30T12:00:00Z", 18)]),
        "usage_stats": section([{ "first_timestamp_ms": huge, "last_timestamp_ms": huge, "total_foreground_ms": huge}]),
        "health_steps": section([{ "_type": [] }, { "_type": {} }, health("StepsRecord", "2026-09-30T12:00:00Z",
                                   "2026-09-30T13:00:00Z", count=huge)]),
        "device_snapshot": section([{"timezone": []}], mode="snapshot", captured_at_ms=253_402_300_799_000),
        "future_source": section([{"arbitrary": huge}]),
    }))
    assert day(con)["usage"]["unlocks"] == 1
    assert day(con)["health"]["steps"]["value"] is None
    assert not w.derive_batch(con, "member", "phone", "invalid-window", {"window": []}, ms("2026-10-01T00:00:00Z"))


def test_fact_byte_limit_is_bounded_and_preserves_latest(con, monkeypatch):
    monkeypatch.setattr(w, "MAX_FACT_BYTES_PER_DEVICE", 100)
    ingest(con, payload({"usage_events": section([event("2026-09-30T11:00:00Z", 18), event("2026-09-30T12:00:00Z", 18)])}))
    assert con.execute("SELECT SUM(length(payload)) FROM native_wellbeing_facts").fetchone()[0] <= 100
    assert con.execute("SELECT MAX(start_ms) FROM native_wellbeing_facts").fetchone()[0] == ms("2026-09-30T12:00:00Z")


def test_daily_screen_and_unlock_bucket_fallback_is_latest_snapshot(con):
    def records(count, total):
        return [{"event_type": kind, "count": count, "total_time_ms": total,
                 "first_timestamp_ms": ms("2026-09-30T00:00:00Z"), "last_timestamp_ms": ms("2026-09-30T12:00:00Z")}
                for kind in (15, 18)]
    ingest(con, payload({"usage_event_stats": section(records(2, 20_000))}, end="2026-09-30T12:00:00Z"))
    ingest(con, payload({"usage_event_stats": section(records(4, 50_000))}, end="2026-09-30T13:00:00Z"))
    result = day(con)
    assert result["usage"]["screen_ms"] == 50_000
    assert result["usage"]["unlocks"] == 4
    assert result["usage"]["foreground_ms"] is None
    assert next(m for m in result["metrics"] if m["key"] == "screen")["method"] == "android_bucket_estimate"


def test_backfill_byte_budget_returns_progress_then_completes(con, monkeypatch):
    body = payload({"usage_events": section([event("2026-09-30T12:00:00Z", 18)])})
    raw = json.dumps(body)
    monkeypatch.setattr(w, "MAX_BACKFILL_BYTES_PER_REQUEST", len(raw.encode()) + 1)
    con.executemany("INSERT INTO native_batches VALUES(?,?,?,?,?)", [("member", str(i), "phone", raw, body["collected_at_ms"]) for i in range(3)])
    assert w.backfill_wellbeing(con, "member") == 1
    assert w.backfill_wellbeing(con, "member") == 1
    assert w.backfill_wellbeing(con, "member") == 1
    assert w.backfill_wellbeing(con, "member") == 0
    assert day(con)["usage"]["unlocks"] == 1


def test_pruned_facts_do_not_erase_unrelated_materialized_historical_day(con, monkeypatch):
    ingest(con, payload({"usage_events": section([event("2026-09-30T12:00:00Z", 1), event("2026-09-30T12:30:00Z", 2)])}))
    assert day(con)["usage"]["foreground_ms"] == 1_800_000
    monkeypatch.setattr(w, "MAX_FACTS_PER_DEVICE", 2)
    ingest(con, payload({"usage_events": section([event("2026-10-03T12:00:00Z", 1), event("2026-10-03T12:10:00Z", 2)])},
                        start="2026-10-03T00:00:00Z", end="2026-10-04T00:00:00Z"))
    assert con.execute("SELECT MIN(start_ms) FROM native_wellbeing_facts").fetchone()[0] == ms("2026-10-03T12:00:00Z")
    assert day(con)["usage"]["foreground_ms"] == 1_800_000
    assert day(con, "2026-10-03")["usage"]["foreground_ms"] == 600_000


def test_weekly_mixed_health_origins_are_reported_as_daily_origins(con):
    ingest(con, payload({"health_steps": section([health("StepsRecord", "2026-09-30T12:00:00Z", "2026-09-30T13:00:00Z", origin="com.phone", count=1000)])}))
    ingest(con, payload({"health_steps": section([health("StepsRecord", "2026-10-01T12:00:00Z", "2026-10-01T13:00:00Z", origin="com.watch", count=2000)])},
                        start="2026-10-01T00:00:00Z", end="2026-10-02T00:00:00Z"))
    result = w.read_wellbeing(con, "member", "phone", "2026-09-30", "2026-10-01", "weekly")
    steps = result["weeks"][0]["health"]["steps"]
    assert steps["value"] == 3000
    assert steps["origin"] is None
    assert steps["method"] == "reported_daily_origins"
    assert steps["origins"] == ["com.phone", "com.watch"]


def test_targeted_refresh_after_fact_pruning_keeps_original_daily_observations(con, monkeypatch):
    ingest(con, payload({"usage_events": section([event("2026-09-30T12:00:00Z", 1), event("2026-09-30T12:30:00Z", 2)])}))
    assert day(con)["usage"]["foreground_ms"] == 1_800_000
    monkeypatch.setattr(w, "MAX_FACTS_PER_DEVICE", 2)
    ingest(con, payload({"usage_events": section([event("2026-10-01T12:00:00Z", 1), event("2026-10-01T12:10:00Z", 2)])},
                        start="2026-10-01T00:00:00Z", end="2026-10-02T00:00:00Z"))
    result = day(con)
    assert result["usage"]["foreground_ms"] == 1_800_000
    assert next(m for m in result["metrics"] if m["key"] == "foreground")["retained_daily_summary"]
    con.execute("DELETE FROM native_devices WHERE device_id='phone'")
    assert con.execute("SELECT COUNT(*) FROM native_wellbeing_pruned_sources").fetchone()[0] == 0


def test_trace_micronutrient_precision_survives_daily_and_weekly_aggregation(con):
    ingest(con, payload({"health_nutrition": section([health("NutritionRecord", "2026-09-30T12:00:00Z", "2026-09-30T12:30:00Z",
        vitaminD={"grams": 0.00001}, vitaminB12={"grams": 0.0000024})])}))
    daily = day(con)["health"]
    assert daily["nutrition_vitamin_d"]["value"] == 0.00001
    assert daily["nutrition_vitamin_b12"]["value"] == 0.0000024
    weekly = w.read_wellbeing(con, "member", "phone", "2026-09-30", "2026-09-30", "weekly")["weeks"][0]["health"]
    assert weekly["nutrition_vitamin_d"]["value"] == 0.00001
    assert weekly["nutrition_vitamin_b12"]["value"] == 0.0000024


def test_health_record_update_replaces_source_id_and_stale_exports_cannot_overwrite(con):
    original = health("StepsRecord", "2026-09-30T12:00:00Z", "2026-09-30T13:00:00Z", count=1000)
    revised = {**original, "count": 700, "metadata": {**original["metadata"], "lastModifiedTime": {"epoch_ms": ms("2026-09-30T14:00:00Z")}}}
    ingest(con, payload({"health_steps": section([original])}, end="2026-09-30T13:00:00Z"))
    ingest(con, payload({"health_steps": section([revised])}, end="2026-09-30T14:00:00Z"))
    ingest(con, payload({"health_steps": section([original])}, end="2026-09-30T15:00:00Z"))
    assert con.execute("SELECT COUNT(*) FROM native_wellbeing_facts").fetchone()[0] == 1
    assert day(con)["health"]["steps"]["value"] == 700
