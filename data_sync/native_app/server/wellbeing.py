"""Daily views of consented Android exports, independent of retained raw batches.

Usage buckets are snapshots, not hourly deltas. Event identities and Health
Connect source IDs are retained once, so retries and overlapping exports do not
multiply totals. Raw Health Connect records cannot reproduce its user-selected
origin priorities: health values here explicitly describe one reporting origin.
"""

from collections import defaultdict
from datetime import date, datetime, time as day_time, timedelta, timezone
import hashlib
import heapq
import json
import math
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError


DAY_MS = 86_400_000
RETENTION_DAYS = 365
MAX_QUERY_DAYS = 93
MAX_FACTS_PER_DEVICE = 100_000
MAX_FACT_BYTES_PER_DEVICE = 64 * 1024 * 1024
MAX_OBSERVATIONS_PER_DEVICE = 60_000
MAX_BATCH_RECORDS = 100_000
MAX_BACKFILL_BYTES_PER_REQUEST = 16 * 1024 * 1024

# Stable public metric keys. Units refer to the decoded SDK properties, not
# inferred conversions from counters or a member's manually entered check-ins.
METRICS = {
    "foreground": ("App foreground time", "ms"),
    "screen": ("Screen interactive time", "ms"),
    "unlocks": ("Unlock events", "count"),
    "wifi_rx": ("Wi-Fi received", "bytes"),
    "wifi_tx": ("Wi-Fi sent", "bytes"),
    "mobile_rx": ("Mobile received", "bytes"),
    "mobile_tx": ("Mobile sent", "bytes"),
    "steps": ("Reported steps", "steps"),
    "distance": ("Reported distance", "m"),
    "active_calories": ("Reported active energy", "kcal"),
    "total_calories": ("Reported total energy", "kcal"),
    "sleep": ("Reported sleep session time", "ms"),
    "exercise": ("Reported exercise session time", "ms"),
    "heart_rate": ("Heart rate", "bpm"),
    "resting_heart_rate": ("Resting heart rate", "bpm"),
    "weight": ("Recorded weight", "kg"),
    "height": ("Recorded height", "m"),
    "body_fat": ("Recorded body fat", "%"),
    "hydration": ("Reported hydration", "L"),
    "oxygen_saturation": ("Oxygen saturation", "%"),
    "blood_glucose": ("Blood glucose", "mmol/L"),
    "respiratory_rate": ("Respiratory rate", "breaths/min"),
    "body_temperature": ("Body temperature", "°C"),
    "systolic_pressure": ("Systolic blood pressure", "mmHg"),
    "diastolic_pressure": ("Diastolic blood pressure", "mmHg"),
    "basal_body_temperature": ("Basal body temperature", "°C"),
    "basal_metabolic_rate": ("Recorded basal energy rate", "kcal/day"),
    "body_water_mass": ("Recorded body water mass", "kg"),
    "bone_mass": ("Recorded bone mass", "kg"),
    "lean_body_mass": ("Recorded lean body mass", "kg"),
    "elevation_gained": ("Reported elevation gained", "m"),
    "floors_climbed": ("Reported floors climbed", "floors"),
    "heart_rate_variability": ("Heart rate variability RMSSD", "ms"),
    "mindfulness": ("Reported mindfulness session time", "ms"),
    "vo2_max": ("Recorded VO₂ max", "mL/kg/min"),
    "wheelchair_pushes": ("Reported wheelchair pushes", "pushes"),
    "cycling_cadence": ("Cycling cadence", "rpm"),
    "steps_cadence": ("Steps cadence", "steps/min"),
    "speed": ("Recorded speed", "m/s"),
    "power": ("Recorded power", "W"),
    "skin_temperature_delta": ("Skin temperature change", "°C"),
}
HEALTH = {
    "StepsRecord": [("steps", "count", None, "cumulative")],
    "DistanceRecord": [("distance", "distance", "meters", "cumulative")],
    "ActiveCaloriesBurnedRecord": [("active_calories", "energy", "kilocalories", "cumulative")],
    "TotalCaloriesBurnedRecord": [("total_calories", "energy", "kilocalories", "cumulative")],
    "SleepSessionRecord": [("sleep", None, None, "duration")],
    "ExerciseSessionRecord": [("exercise", None, None, "duration")],
    "HeartRateRecord": [("heart_rate", "beatsPerMinute", None, "sample")],
    "RestingHeartRateRecord": [("resting_heart_rate", "beatsPerMinute", None, "instant")],
    "WeightRecord": [("weight", "weight", "kilograms", "instant")],
    "HeightRecord": [("height", "height", "meters", "instant")],
    "BodyFatRecord": [("body_fat", "percentage", "value", "instant")],
    "HydrationRecord": [("hydration", "volume", "liters", "cumulative")],
    "OxygenSaturationRecord": [("oxygen_saturation", "percentage", "value", "instant")],
    "BloodGlucoseRecord": [("blood_glucose", "level", "millimolesPerLiter", "instant")],
    "RespiratoryRateRecord": [("respiratory_rate", "rate", None, "instant")],
    "BodyTemperatureRecord": [("body_temperature", "temperature", "celsius", "instant")],
    "BloodPressureRecord": [("systolic_pressure", "systolic", "millimetersOfMercury", "instant"),
                            ("diastolic_pressure", "diastolic", "millimetersOfMercury", "instant")],
    "BasalBodyTemperatureRecord": [("basal_body_temperature", "temperature", "celsius", "instant")],
    "BasalMetabolicRateRecord": [("basal_metabolic_rate", "basalMetabolicRate", "kilocaloriesPerDay", "instant")],
    "BodyWaterMassRecord": [("body_water_mass", "mass", "kilograms", "instant")],
    "BoneMassRecord": [("bone_mass", "mass", "kilograms", "instant")],
    "LeanBodyMassRecord": [("lean_body_mass", "mass", "kilograms", "instant")],
    "ElevationGainedRecord": [("elevation_gained", "elevation", "meters", "cumulative")],
    "FloorsClimbedRecord": [("floors_climbed", "floors", None, "cumulative")],
    "HeartRateVariabilityRmssdRecord": [("heart_rate_variability", "heartRateVariabilityMillis", None, "instant")],
    "MindfulnessSessionRecord": [("mindfulness", None, None, "duration")],
    "Vo2MaxRecord": [("vo2_max", "vo2MillilitersPerMinuteKilogram", None, "instant")],
    "WheelchairPushesRecord": [("wheelchair_pushes", "count", None, "cumulative")],
    "CyclingPedalingCadenceRecord": [("cycling_cadence", "revolutionsPerMinute", None, "sample")],
    "StepsCadenceRecord": [("steps_cadence", "rate", None, "sample")],
    "SpeedRecord": [("speed", "speed", "metersPerSecond", "sample")],
    "PowerRecord": [("power", "power", "watts", "sample")],
    "SkinTemperatureRecord": [("skin_temperature_delta", "delta", "celsius", "sample")],
}
# Nutrition getters use explicit SDK energy/mass units. Missing nutrients remain
# absent instead of being defaulted to zero. These are reported intake records.
HEALTH["NutritionRecord"] = []
for _field in ("energy", "energyFromFat", "biotin", "caffeine", "calcium", "chloride", "cholesterol", "chromium",
               "copper", "dietaryFiber", "folate", "folicAcid", "iodine", "iron", "magnesium", "manganese",
               "molybdenum", "monounsaturatedFat", "niacin", "pantothenicAcid", "phosphorus", "polyunsaturatedFat",
               "potassium", "protein", "riboflavin", "saturatedFat", "selenium", "sodium", "sugar", "thiamin",
               "totalCarbohydrate", "totalFat", "transFat", "unsaturatedFat", "vitaminA", "vitaminB12", "vitaminB6",
               "vitaminC", "vitaminD", "vitaminE", "vitaminK", "zinc"):
    _name = "".join(("_" + c.lower()) if c.isupper() else c for c in _field)
    _key = "nutrition_" + _name
    _unit = "kilocalories" if _field in ("energy", "energyFromFat") else "grams"
    METRICS[_key] = ("Reported nutrition " + _name.replace("_", " "), "kcal" if _unit == "kilocalories" else "g")
    HEALTH["NutritionRecord"].append((_key, _field, _unit, "reported_sum"))
HEALTH_SOURCES = {metric: "health_" + "".join(("_" + c.lower()) if c.isupper() else c for c in record.removesuffix("Record")).lstrip("_")
                  for record, fields in HEALTH.items() for metric, *_ in fields}
CUMULATIVE_METRICS = {metric for fields in HEALTH.values() for metric, _, _, mode in fields
                      if mode in ("cumulative", "duration", "reported_sum")}
NOTES = [
    "Missing, denied, and unavailable sources are shown without invented zero values.",
    "Foreground and screen time use observed transitions; incomplete history can omit sessions.",
    "App launches count observed ACTIVITY_RESUMED events, which can include transitions between activities in one app.",
    "Android usage and network buckets are estimates, not additive hourly measurements.",
    "Health values are reported records from a selected origin, not Health Connect priority-deduplicated totals; overlapping origins are not added.",
    "Intervals crossing midnight are allocated proportionally; sleep describes session time, not a medical sleep assessment.",
    "All-device health values select one device per metric; duplicate wearable records across phones are not added.",
    "Summaries use retained facts for up to 365 days, 100,000 facts and 64 MiB per device; large histories can be incomplete.",
    "Source record counts describe the most recent read for each day; weekly source counts are daily observations, not unique raw records.",
]


def init_wellbeing_db(con):
    # execute individually: executescript would commit the caller's transaction.
    for sql in (
        """CREATE TABLE IF NOT EXISTS native_wellbeing_facts (
            tenant TEXT NOT NULL, device_id TEXT NOT NULL, source TEXT NOT NULL,
            record_key TEXT NOT NULL, start_ms INTEGER NOT NULL, end_ms INTEGER NOT NULL,
            collected_at_ms INTEGER NOT NULL, revision_ms INTEGER NOT NULL, payload TEXT NOT NULL,
            PRIMARY KEY(tenant,device_id,source,record_key),
            FOREIGN KEY(tenant,device_id) REFERENCES native_devices(tenant,device_id) ON DELETE CASCADE)""",
        """CREATE INDEX IF NOT EXISTS native_wellbeing_fact_window
            ON native_wellbeing_facts(tenant,device_id,start_ms,end_ms)""",
        """CREATE TABLE IF NOT EXISTS native_wellbeing_observations (
            tenant TEXT NOT NULL, device_id TEXT NOT NULL, batch_id TEXT NOT NULL, source TEXT NOT NULL,
            start_ms INTEGER NOT NULL,end_ms INTEGER NOT NULL,collected_at_ms INTEGER NOT NULL,
            status TEXT NOT NULL,complete INTEGER NOT NULL,record_count INTEGER NOT NULL,
            PRIMARY KEY(tenant,device_id,batch_id,source),
            FOREIGN KEY(tenant,device_id) REFERENCES native_devices(tenant,device_id) ON DELETE CASCADE)""",
        """CREATE INDEX IF NOT EXISTS native_wellbeing_observation_window
            ON native_wellbeing_observations(tenant,device_id,start_ms,end_ms)""",
        """CREATE TABLE IF NOT EXISTS native_wellbeing_days (
            tenant TEXT NOT NULL,device_id TEXT NOT NULL,date TEXT NOT NULL,zone TEXT NOT NULL,
            dirty INTEGER NOT NULL DEFAULT 1,payload TEXT,updated_at_ms INTEGER NOT NULL,
            PRIMARY KEY(tenant,device_id,date),
            FOREIGN KEY(tenant,device_id) REFERENCES native_devices(tenant,device_id) ON DELETE CASCADE)""",
        """CREATE TABLE IF NOT EXISTS native_wellbeing_batches (
            tenant TEXT NOT NULL,device_id TEXT NOT NULL,batch_id TEXT NOT NULL,received_at_ms INTEGER NOT NULL,
            PRIMARY KEY(tenant,device_id,batch_id),
            FOREIGN KEY(tenant,device_id) REFERENCES native_devices(tenant,device_id) ON DELETE CASCADE)""",
        """CREATE TABLE IF NOT EXISTS native_wellbeing_zones (
            tenant TEXT NOT NULL,device_id TEXT NOT NULL,zone TEXT NOT NULL,collected_at_ms INTEGER NOT NULL,
            PRIMARY KEY(tenant,device_id),
            FOREIGN KEY(tenant,device_id) REFERENCES native_devices(tenant,device_id) ON DELETE CASCADE)""",
    ):
        con.execute(sql)


def _json(value):
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False, allow_nan=False)


def _number(value):
    # JSON accepts arbitrarily large integers; do not coerce them to a float
    # before bounding them (math.isfinite itself can overflow on those ints).
    if isinstance(value, int) and not isinstance(value, bool) and 0 <= value <= 9_223_372_036_854_775_807:
        return value
    if isinstance(value, float) and math.isfinite(value) and 0 <= value <= 9_223_372_036_854_775_807:
        return value
    return None


def _measurement(value, key):
    if key in ("skin_temperature_delta", "body_temperature", "basal_body_temperature"):
        if isinstance(value, int) and not isinstance(value, bool) and abs(value) <= 9_223_372_036_854_775_807:
            return value
        if isinstance(value, float) and math.isfinite(value) and abs(value) <= 9_223_372_036_854_775_807:
            return value
        return None
    return _number(value)


def _stamp(value):
    if isinstance(value, dict):
        value = value.get("epoch_ms")
    value = _number(value)
    return int(value) if value is not None and value <= 253_402_300_799_000 else None


def _valid_zone(value):
    if not isinstance(value, str) or len(value) > 100:
        return None
    try:
        return ZoneInfo(value).key
    except (ZoneInfoNotFoundError, ValueError):
        return None


def account_zone(con, tenant, device_id=None):
    row = con.execute("SELECT data FROM profiles WHERE tenant=?", (tenant,)).fetchone()
    if row:
        try:
            zone = _valid_zone(json.loads(row["data"]).get("timezone"))
            if zone:
                return zone
        except (ValueError, TypeError, AttributeError):
            pass
    sql = "SELECT zone FROM native_wellbeing_zones WHERE tenant=?"
    params = [tenant]
    if device_id:
        sql += " AND device_id=?"
        params.append(device_id)
    row = con.execute(sql + " ORDER BY collected_at_ms DESC LIMIT 1", params).fetchone()
    return row["zone"] if row else "UTC"


def _day_bounds(selected, zone):
    d = date.fromisoformat(selected)
    tz = ZoneInfo(zone)
    start = datetime.combine(d, day_time.min, tz)
    end = datetime.combine(d + timedelta(days=1), day_time.min, tz)
    return int(start.timestamp() * 1000), int(end.timestamp() * 1000)


def _date_at(stamp, zone):
    return datetime.fromtimestamp(stamp / 1000, ZoneInfo(zone)).date().isoformat()


def _health_fact(source, record, collected):
    kind = record.get("_type")
    if not isinstance(kind, str) or kind not in HEALTH:
        return None
    metadata = record.get("metadata") if isinstance(record.get("metadata"), dict) else {}
    origin_data = metadata.get("dataOrigin")
    origin = origin_data.get("packageName") if isinstance(origin_data, dict) else None
    origin = origin if isinstance(origin, str) and 0 < len(origin) <= 300 else "unknown_origin"
    identifier = metadata.get("id") or metadata.get("clientRecordId")
    identity = _json([origin, kind, identifier]) if identifier else _json(record)
    start = _stamp(record.get("startTime", record.get("time")))
    end = _stamp(record.get("endTime", record.get("time")))
    if start is None or end is None or end < start or end > collected + DAY_MS:
        return None
    # Compact only derived fields; the complete original stays in batch history.
    values = []
    for metric, field, unit, mode in HEALTH[kind]:
        if mode == "sample":
            samples = record.get("deltas" if kind == "SkinTemperatureRecord" else "samples", [])
            if isinstance(samples, list):
                for sample in samples[:MAX_BATCH_RECORDS]:
                    if not isinstance(sample, dict):
                        continue
                    stamp = _stamp(sample.get("time"))
                    value = sample.get(field)
                    if unit and isinstance(value, dict):
                        value = value.get(unit)
                    value = _measurement(value, metric)
                    if stamp is not None and value is not None:
                        values.append([metric, "instant", stamp, stamp, value])
            continue
        value = end - start if mode == "duration" else record.get(field)
        if unit and isinstance(value, dict):
            value = value.get(unit)
        value = _measurement(value, metric)
        if value is not None:
            values.append([metric, mode, start, end, value])
    revision = _stamp(metadata.get("lastModifiedTime")) or collected
    return hashlib.sha256(identity.encode()).hexdigest(), start, end, revision, {
        "kind": "health", "origin": origin, "values": values,
        "version": _number(metadata.get("clientRecordVersion")) or 0,
    }


def _fact(source, record, collected):
    if not isinstance(record, dict):
        return None
    if source.startswith("health_"):
        return _health_fact(source, record, collected)
    if source == "usage_events":
        stamp = _stamp(record.get("timestamp_ms"))
        event_type = _number(record.get("event_type"))
        if stamp is None or event_type is None:
            return None
        package, clazz = record.get("package_name"), record.get("class_name")
        package = package if isinstance(package, str) and len(package) <= 512 else None
        clazz = clazz if isinstance(clazz, str) and len(clazz) <= 512 else None
        value = {"kind": "event", "type": int(event_type), "package": package, "class": clazz}
        return hashlib.sha256(_json([stamp, value]).encode()).hexdigest(), stamp, stamp, collected, value
    if source == "usage_stats":
        start, end = _stamp(record.get("first_timestamp_ms")), _stamp(record.get("last_timestamp_ms"))
        foreground = _number(record.get("total_foreground_ms"))
        package = record.get("package_name")
        if start is None or end is None or end < start or foreground is None or not isinstance(package, str) or len(package) > 512:
            return None
        value = {"kind": "usage_bucket", "package": package, "foreground": foreground}
        # The end of a daily bucket advances on every query; it is not an identity.
        identity = _json([package, start])
        return hashlib.sha256(identity.encode()).hexdigest(), start, end, collected, value
    if source == "usage_event_stats":
        start, end = _stamp(record.get("first_timestamp_ms")), _stamp(record.get("last_timestamp_ms"))
        kind, count, total = (_number(record.get(key)) for key in ("event_type", "count", "total_time_ms"))
        if start is None or end is None or end < start or kind is None or count is None or total is None:
            return None
        value = {"kind": "event_bucket", "type": int(kind), "count": count, "total": total}
        return hashlib.sha256(_json([start, kind]).encode()).hexdigest(), start, end, collected, value
    if source.startswith("network_usage_"):
        start, end = _stamp(record.get("start_ms")), _stamp(record.get("end_ms"))
        rx, tx = _number(record.get("rx_bytes")), _number(record.get("tx_bytes"))
        if start is None or end is None or end <= start or rx is None or tx is None:
            return None
        stream = [record.get(key) if isinstance(record.get(key), int) and not isinstance(record.get(key), bool) else None
                  for key in ("uid", "state", "metered", "roaming", "tag")]
        value = {"kind": "network", "stream": stream, "rx": rx, "tx": tx}
        identity = _json([start, end, stream])
        return hashlib.sha256(identity.encode()).hexdigest(), start, end, collected, value
    return None


def derive_batch(con, tenant, device_id, batch_id, payload, received_at_ms):
    """Persist compact facts and day views within the receipt's transaction.

    A historical backfill and a repeated export use exactly the same path. Unknown
    fields remain in raw history and never cause the acknowledgement to fail.
    """
    if con.execute("SELECT 1 FROM native_wellbeing_batches WHERE tenant=? AND device_id=? AND batch_id=?",
                   (tenant, device_id, batch_id)).fetchone():
        return False
    if not isinstance(payload, dict):
        return False
    window = payload.get("window", {})
    if not isinstance(window, dict):
        return False
    start, end = _stamp(window.get("start_ms")), _stamp(window.get("end_ms"))
    collected = _stamp(payload.get("collected_at_ms")) or received_at_ms
    if start is None or end is None or end <= start:
        return False
    data = payload.get("data", {})
    if not isinstance(data, dict):
        return False
    snapshot = data.get("device_snapshot", {})
    if isinstance(snapshot, dict) and isinstance(snapshot.get("records"), list):
        for record in snapshot["records"][:10]:
            if not isinstance(record, dict):
                continue
            zone = _valid_zone(record.get("timezone"))
            if zone:
                con.execute("""INSERT INTO native_wellbeing_zones VALUES(?,?,?,?)
                    ON CONFLICT(tenant,device_id) DO UPDATE SET zone=excluded.zone,collected_at_ms=excluded.collected_at_ms
                    WHERE excluded.collected_at_ms>=native_wellbeing_zones.collected_at_ms""", (tenant, device_id, zone, collected))
    zone = account_zone(con, tenant, device_id)
    cutoff = max(0, received_at_ms - RETENTION_DAYS * DAY_MS)
    facts, observations, touched = [], [], set()
    processed = 0
    for source, section in list(data.items())[:128]:
        if not isinstance(source, str) or len(source) > 100 or not isinstance(section, dict):
            continue
        records = section.get("records", [])
        records = records if isinstance(records, list) else []
        source_start, source_end = start, end
        if section.get("mode") == "snapshot":
            source_start = _stamp(section.get("captured_at_ms")) or collected
            if source_start > collected + DAY_MS:
                source_start = collected
            source_end = source_start + 1
        source_start = max(source_start, cutoff)
        if source_end <= source_start:
            continue
        status = section.get("status", "unavailable")
        status = status if status in ("ok", "denied", "background_denied", "unavailable", "error") else "unavailable"
        observations.append((tenant, device_id, batch_id, source, source_start, source_end, collected,
                             status, int(section.get("complete") is True), len(records)))
        touched.update((_date_at(source_start, zone), _date_at(source_end - 1, zone)))
        if status != "ok":
            continue
        for record in records:
            processed += 1
            if processed > MAX_BATCH_RECORDS:
                break
            value = _fact(source, record, collected)
            if not value:
                continue
            key, fact_start, fact_end, revision, compact = value
            if fact_end < cutoff or fact_start > collected + DAY_MS or fact_end > collected + DAY_MS:
                continue
            if source == "usage_events" and not (source_start <= fact_start < source_end):
                continue
            facts.append((tenant, device_id, source, key, fact_start, fact_end, collected, revision, _json(compact)))
            touched.update((_date_at(max(cutoff, fact_start), zone), _date_at(fact_end, zone)))
    con.executemany("""INSERT INTO native_wellbeing_facts VALUES(?,?,?,?,?,?,?,?,?)
        ON CONFLICT(tenant,device_id,source,record_key) DO UPDATE SET start_ms=excluded.start_ms,end_ms=excluded.end_ms,
        collected_at_ms=excluded.collected_at_ms,revision_ms=excluded.revision_ms,payload=excluded.payload
        WHERE excluded.revision_ms>native_wellbeing_facts.revision_ms OR
        (excluded.revision_ms=native_wellbeing_facts.revision_ms AND excluded.collected_at_ms>=native_wellbeing_facts.collected_at_ms)""", facts)
    con.executemany("INSERT OR IGNORE INTO native_wellbeing_observations VALUES(?,?,?,?,?,?,?,?,?,?)", observations)
    con.execute("INSERT INTO native_wellbeing_batches VALUES(?,?,?,?)", (tenant, device_id, batch_id, received_at_ms))
    # Any changed event can close a session in an already cached adjacent day.
    con.execute("UPDATE native_wellbeing_days SET dirty=1 WHERE tenant=? AND device_id=?", (tenant, device_id))
    for selected in touched:
        if selected < _date_at(cutoff, zone):
            continue
        con.execute("""INSERT INTO native_wellbeing_days VALUES(?,?,?,?,1,NULL,?)
            ON CONFLICT(tenant,device_id,date) DO UPDATE SET dirty=1,updated_at_ms=excluded.updated_at_ms""",
                    (tenant, device_id, selected, zone, received_at_ms))
    prune_wellbeing(con, tenant, device_id, received_at_ms)
    # Ordinary one-day exports materialize immediately. Large initial backfills
    # defer materialization to the bounded date query to keep ingestion bounded.
    if len(touched) <= 7:
        for selected in sorted(touched):
            _device_day(con, tenant, device_id, selected, zone)
    return True


def prune_wellbeing(con, tenant, device_id, now_ms):
    cutoff = now_ms - RETENTION_DAYS * DAY_MS
    for table, stamp in (("native_wellbeing_facts", "end_ms"), ("native_wellbeing_observations", "end_ms"),
                         ("native_wellbeing_batches", "received_at_ms")):
        con.execute(f"DELETE FROM {table} WHERE tenant=? AND device_id=? AND {stamp}<?", (tenant, device_id, cutoff))
    con.execute("DELETE FROM native_wellbeing_days WHERE tenant=? AND device_id=? AND date<?",
                (tenant, device_id, _date_at(max(0, cutoff), account_zone(con, tenant, device_id))))
    for table, maximum, order in (("native_wellbeing_facts", MAX_FACTS_PER_DEVICE, "end_ms DESC"),
                                 ("native_wellbeing_observations", MAX_OBSERVATIONS_PER_DEVICE, "end_ms DESC,collected_at_ms DESC")):
        con.execute(f"DELETE FROM {table} WHERE tenant=? AND device_id=? AND rowid NOT IN "
                    f"(SELECT rowid FROM {table} WHERE tenant=? AND device_id=? ORDER BY {order},rowid DESC LIMIT ?)",
                    (tenant, device_id, tenant, device_id, maximum))
    total = con.execute("SELECT COALESCE(SUM(length(payload)),0) FROM native_wellbeing_facts WHERE tenant=? AND device_id=?",
                        (tenant, device_id)).fetchone()[0]
    if total > MAX_FACT_BYTES_PER_DEVICE:
        for row in con.execute("SELECT rowid,length(payload) AS size FROM native_wellbeing_facts WHERE tenant=? AND device_id=? ORDER BY end_ms,rowid",
                               (tenant, device_id)).fetchall():
            con.execute("DELETE FROM native_wellbeing_facts WHERE rowid=?", (row["rowid"],))
            total -= row["size"]
            if total <= MAX_FACT_BYTES_PER_DEVICE:
                break


def backfill_wellbeing(con, tenant, device_id=None, limit=500):
    params = [tenant]
    selected = ""
    if device_id:
        selected = " AND b.device_id=?"
        params.append(device_id)
    params.append(min(max(int(limit), 1), 500))
    rows = con.execute("""SELECT b.device_id,b.batch_id,b.payload,b.received_at_ms FROM native_batches b
        WHERE b.tenant=? AND b.payload IS NOT NULL""" + selected + """ AND NOT EXISTS(
        SELECT 1 FROM native_wellbeing_batches w WHERE w.tenant=b.tenant AND w.device_id=b.device_id AND w.batch_id=b.batch_id)
        ORDER BY b.received_at_ms,b.rowid LIMIT ?""", params)
    processed, size = 0, 0
    for row in rows:
        # Bound work by bytes as well as receipt count. One 8 MiB receipt is
        # always allowed, and the browser receives a pending progress count.
        if processed and size + len(row["payload"].encode("utf-8")) > MAX_BACKFILL_BYTES_PER_REQUEST:
            break
        size += len(row["payload"].encode("utf-8"))
        try:
            payload = json.loads(row["payload"])
        except (ValueError, TypeError):
            continue
        derive_batch(con, tenant, row["device_id"], row["batch_id"], payload, row["received_at_ms"])
        processed += 1
    return processed


def _metric(key, value=None, status="no_data", method="unavailable", **extra):
    label, unit = METRICS[key]
    return {"key": key, "label": label, "unit": unit, "status": status, "value": value,
            "method": method, "origin": None, "origin_count": 0, "samples": 0, "min": None, "max": None, **extra}


def _merged_intervals(intervals):
    result = []
    for start, end in sorted(intervals):
        if end <= start:
            continue
        if result and start <= result[-1][1]:
            result[-1][1] = max(end, result[-1][1])
        else:
            result.append([start, end])
    return result


def _covered_segments(start, end, coverage):
    return [(max(start, a), min(end, b)) for a, b in coverage if a < end and b > start]


def _session_segments(opened, closed, day_start, day_end, coverage):
    # A resumed state remains known only in its contiguous observed interval.
    # A later window after a gap cannot establish that it stayed foreground.
    for a, b in coverage:
        if a <= opened < b:
            left, right = max(opened, day_start), min(closed, day_end, b)
            return [(left, right)] if right > left else []
    return []


def _duration(intervals):
    return sum(end - start for start, end in _merged_intervals(intervals))


def _allocated_sum(rows, day_start, day_end, field):
    """Choose the newest reported value in overlapping intervals of one stream.

    This sweep is O(n log n), avoids hourly snapshot addition and explicitly
    prorates intervals that cross day boundaries.
    """
    events = []
    for index, row in enumerate(rows):
        start, end = max(day_start, row["start_ms"]), min(day_end, row["end_ms"])
        if end <= start or row["end_ms"] <= row["start_ms"]:
            continue
        rate = row[field] / (row["end_ms"] - row["start_ms"])
        priority = (-row.get("revision_ms", 0), -row.get("collected_at_ms", 0), -(1 / max(1, row["end_ms"] - row["start_ms"])), index)
        events.append((start, 1, index, rate, priority))
        events.append((end, -1, index, rate, priority))
    active, heap, total, previous = set(), [], 0.0, None
    for stamp, direction, index, rate, priority in sorted(events):
        while heap and heap[0][-1] not in active:
            heapq.heappop(heap)
        if previous is not None and stamp > previous and heap:
            total += (stamp - previous) * heap[0][-2]
        if direction == 1:
            active.add(index)
            heapq.heappush(heap, (*priority[:-1], rate, index))
        else:
            active.discard(index)
        previous = stamp
    return round(total, 4)


def _source_status(observations, source, has_records=False):
    matching = [r for r in observations if r["source"] == source]
    if has_records:
        return "available"
    if not matching:
        return "no_data"
    latest = max(matching, key=lambda r: r["collected_at_ms"])
    if latest["status"] == "ok":
        return "no_data"
    return latest["status"]


def _blank_day(selected):
    metrics = [_metric(key) for key in METRICS]
    return _assemble(selected, metrics, [], [], 0, None, "unavailable")


def _assemble(selected, metrics, apps, sources, count, updated, usage_method):
    values = {entry["key"]: entry for entry in metrics}
    status = values["foreground"]["status"]
    network_keys = ("wifi_rx", "wifi_tx", "mobile_rx", "mobile_tx")
    network_status = "available" if any(values[k]["value"] is not None for k in network_keys) else values["wifi_rx"]["status"]
    return {"date": selected, "updated_at_ms": updated, "device_count": count,
            "usage": {"status": status, "foreground_ms": values["foreground"]["value"],
                      "screen_ms": values["screen"]["value"], "unlocks": values["unlocks"]["value"],
                      "method": usage_method, "apps": apps},
            "network": {"status": network_status, "wifi_rx_bytes": values["wifi_rx"]["value"],
                        "wifi_tx_bytes": values["wifi_tx"]["value"], "mobile_rx_bytes": values["mobile_rx"]["value"],
                        "mobile_tx_bytes": values["mobile_tx"]["value"]},
            "health": {key: values[key] for key in HEALTH_SOURCES}, "metrics": metrics, "sources": sources}


def _device_day(con, tenant, device_id, selected, zone):
    cached = con.execute("SELECT * FROM native_wellbeing_days WHERE tenant=? AND device_id=? AND date=?",
                         (tenant, device_id, selected)).fetchone()
    if cached and not cached["dirty"] and cached["zone"] == zone and cached["payload"]:
        return json.loads(cached["payload"])
    start, end = _day_bounds(selected, zone)
    rows = [dict(row) for row in con.execute("""SELECT * FROM native_wellbeing_facts WHERE tenant=? AND device_id=?
        AND start_ms<? AND end_ms>=? ORDER BY start_ms,record_key""", (tenant, device_id, end + DAY_MS, start - DAY_MS))]
    for row in rows:
        row["decoded"] = json.loads(row["payload"])
    observations = [dict(row) for row in con.execute("""SELECT * FROM native_wellbeing_observations WHERE tenant=? AND device_id=?
        AND start_ms<? AND end_ms>?""", (tenant, device_id, end + DAY_MS, start - DAY_MS))]
    daily_obs = [r for r in observations if r["start_ms"] < end and r["end_ms"] > start]
    updated = max((r["collected_at_ms"] for r in daily_obs), default=None)
    metrics = {key: _metric(key) for key in METRICS}
    event_rows = [r for r in rows if r["source"] == "usage_events"]
    daily_events = [r for r in event_rows if start <= r["start_ms"] < end]
    event_coverage = _merged_intervals([(r["start_ms"], r["end_ms"]) for r in observations
                                      if r["source"] == "usage_events" and r["status"] == "ok" and r["complete"]])
    apps, global_intervals, screen_intervals = defaultdict(lambda: {"intervals": [], "launches": 0}), [], []
    active, screen_start = {}, None

    def close_app(activity, close):
        if activity in active:
            opened = active.pop(activity)
            package = activity[0]
            intervals = _session_segments(opened, close, start, end, event_coverage)
            apps[package]["intervals"].extend(intervals)
            global_intervals.extend(intervals)

    for row in event_rows:
        stamp, event = row["start_ms"], row["decoded"]
        package, kind = event.get("package"), event["type"]
        activity = (package, event.get("class"))
        if kind == 1 and isinstance(package, str):
            active.setdefault(activity, stamp)
            if start <= stamp < end:
                apps[package]["launches"] += 1
        elif kind in (2, 23) and isinstance(package, str):
            if activity in active:
                close_app(activity, stamp)
            elif event.get("class") is None:
                for opened_activity in list(active):
                    if opened_activity[0] == package:
                        close_app(opened_activity, stamp)
        elif kind in (16, 26):
            for opened_package in list(active):
                close_app(opened_package, stamp)
        elif kind == 27:
            # Startup cannot prove what happened to an unfinished session
            # across an unobserved shutdown.
            active.clear()
            screen_start = None
        if kind == 15:
            screen_start = screen_start if screen_start is not None else stamp
        elif kind in (16, 26) and screen_start is not None:
            screen_intervals.extend(_session_segments(screen_start, stamp, start, end, event_coverage))
            screen_start = None
    # An open activity contributes only through a known contiguous collection
    # window. It cannot silently span a denied/gapped source interval.
    coverage_end = max((b for a, b in event_coverage if a < end), default=start)
    for package in list(active):
        close_app(package, min(end, coverage_end))
    if screen_start is not None:
        screen_intervals.extend(_session_segments(screen_start, coverage_end, start, end, event_coverage))
    app_list = [{"package_name": package, "label": None, "foreground_ms": _duration(info["intervals"]), "launches": info["launches"]}
                for package, info in apps.items() if info["intervals"] or info["launches"]]
    app_list.sort(key=lambda item: (-item["foreground_ms"], item["package_name"]))
    usage_method = "events" if app_list or global_intervals else "unavailable"
    usage_status = _source_status(daily_obs, "usage_events", bool(daily_events))
    if app_list and event_coverage:
        metrics["foreground"] = _metric("foreground", _duration(global_intervals), "available", "observed_events")
    else:
        # A growing daily aggregate snapshot supersedes its earlier snapshots.
        # Multi-day Android aggregates cannot establish per-day activity.
        buckets = [r for r in rows if r["source"] == "usage_stats" and start <= r["start_ms"] < end
                   and r["end_ms"] <= end and r["end_ms"] - r["start_ms"] <= 26 * 3_600_000]
        selected_buckets = {}
        for row in buckets:
            package = row["decoded"]["package"]
            if package not in selected_buckets or row["collected_at_ms"] > selected_buckets[package]["collected_at_ms"]:
                selected_buckets[package] = row
        if selected_buckets:
            app_list = [{"package_name": package, "label": None,
                         "foreground_ms": min(end - start, row["decoded"]["foreground"]), "launches": None}
                        for package, row in selected_buckets.items()]
            app_list.sort(key=lambda item: (-item["foreground_ms"], item["package_name"]))
            metrics["foreground"] = _metric("foreground", min(end - start, sum(a["foreground_ms"] for a in app_list)), "available", "android_bucket_estimate")
            usage_method = "android_bucket_estimate"
        else:
            metrics["foreground"] = _metric("foreground", status=usage_status)
    metrics["screen"] = (_metric("screen", _duration(screen_intervals), "available", "observed_events") if screen_intervals
                         else _metric("screen", status=_source_status(daily_obs, "usage_events")))
    metrics["unlocks"] = (_metric("unlocks", sum(r["decoded"]["type"] == 18 for r in daily_events), "available", "observed_events")
                          if daily_events else _metric("unlocks", status=usage_status))
    event_buckets = [r for r in rows if r["source"] == "usage_event_stats" and start <= r["start_ms"] < end
                     and r["end_ms"] <= end and r["end_ms"] - r["start_ms"] <= 26 * 3_600_000]
    for key, kind, field in (("screen", 15, "total"), ("unlocks", 18, "count")):
        if metrics[key]["value"] is not None:
            continue
        choices = [r for r in event_buckets if r["decoded"]["type"] == kind]
        if choices:
            chosen = max(choices, key=lambda row: row["collected_at_ms"])
            value = chosen["decoded"][field]
            metrics[key] = _metric(key, min(end - start, value) if key == "screen" else value,
                                   "available", "android_bucket_estimate")
    for transport in ("wifi", "mobile"):
        source = "network_usage_" + transport
        network_rows = [r for r in rows if r["source"] == source and r["start_ms"] < end and r["end_ms"] > start]
        streams = defaultdict(list)
        for row in network_rows:
            streams[_json(row["decoded"]["stream"])].append({**row, **row["decoded"]})
        for field in ("rx", "tx"):
            key = transport + "_" + field
            metrics[key] = (_metric(key, sum(_allocated_sum(v, start, end, field) for v in streams.values()),
                                    "available", "android_bucket_estimate") if streams
                            else _metric(key, status=_source_status(daily_obs, source)))
    health_values = defaultdict(lambda: defaultdict(list))
    for row in rows:
        value = row["decoded"]
        if value.get("kind") != "health":
            continue
        for key, mode, a, b, measurement in value["values"]:
            if (mode == "instant" and start <= a < end) or (mode != "instant" and a < end and b > start):
                health_values[key][value["origin"]].append({**row, "start_ms": a, "end_ms": b, "value": measurement, "mode": mode})
    for key, source in HEALTH_SOURCES.items():
        origins = health_values[key]
        if not origins:
            metrics[key] = _metric(key, status=_source_status(daily_obs, source))
            continue
        origin_summaries = {}
        for origin, entries in origins.items():
            if entries[0]["mode"] == "duration":
                value = _duration([(max(start, r["start_ms"]), min(end, r["end_ms"])) for r in entries])
            elif entries[0]["mode"] == "cumulative":
                value = _allocated_sum(entries, start, end, "value")
            elif entries[0]["mode"] == "reported_sum":
                value = sum(r["value"] * (min(end, r["end_ms"]) - max(start, r["start_ms"])) /
                            max(1, r["end_ms"] - r["start_ms"]) for r in entries)
            else:
                # The same timestamp/value sample can occur in overlapping
                # heart-rate records; count it once for averages.
                unique = {(r["start_ms"], r["value"]): r for r in entries}
                entries = list(unique.values())
                value = sum(r["value"] for r in entries) / len(entries)
                if key in ("weight", "height", "body_fat"):
                    value = max(entries, key=lambda r: (r["start_ms"], r["revision_ms"]))["value"]
            origin_summaries[origin] = (value, entries)
        # Preserve one origin to avoid adding phone + wearable measurements.
        # This is stated in method/origin fields, not represented as HC totals.
        chosen = max(origin_summaries, key=lambda origin: (len(origin_summaries[origin][1]), origin))
        value, entries = origin_summaries[chosen]
        instantaneous = entries[0]["mode"] == "instant"
        metrics[key] = _metric(key, round(value, 4), "available", "reported_origin_records", origin=chosen,
                               origin_count=len(origins), samples=len(entries),
                               min=min(r["value"] for r in entries) if instantaneous else None,
                               max=max(r["value"] for r in entries) if instantaneous else None)
    sources = []
    for source in sorted({r["source"] for r in daily_obs}):
        latest = max((r for r in daily_obs if r["source"] == source), key=lambda r: r["collected_at_ms"])
        sources.append({"source": source, "status": "available" if latest["status"] == "ok" and latest["record_count"] else
                        "no_data" if latest["status"] == "ok" else latest["status"],
                        "record_count": latest["record_count"], "complete": bool(latest["complete"])})
    result = _assemble(selected, list(metrics.values()), app_list, sources, int(bool(daily_obs)), updated, usage_method)
    if daily_obs or cached:
        con.execute("""INSERT INTO native_wellbeing_days VALUES(?,?,?,?,0,?,?)
            ON CONFLICT(tenant,device_id,date) DO UPDATE SET zone=excluded.zone,dirty=0,payload=excluded.payload,
            updated_at_ms=excluded.updated_at_ms""", (tenant, device_id, selected, zone, _json(result), updated or 0))
    return result


def _combine_days(selected, entries, across_devices=False):
    if not entries:
        return _blank_day(selected)
    metrics = []
    for key in METRICS:
        values = [next(m for m in entry["metrics"] if m["key"] == key) for entry in entries]
        available = [value for value in values if value["value"] is not None]
        if not available:
            status = next((v["status"] for v in values if v["status"] != "no_data"), "no_data")
            metrics.append(_metric(key, status=status))
            continue
        if across_devices and key in HEALTH_SOURCES:
            # One selected device avoids duplicated HC stores across phones.
            chosen = max(available, key=lambda value: (value["samples"], value["value"]))
            metrics.append({**chosen, "method": "reported_selected_device" if len(available) > 1 else chosen["method"]})
            continue
        instant = key in HEALTH_SOURCES and key not in CUMULATIVE_METRICS
        chosen = available[-1]
        if instant:
            samples = sum(value["samples"] for value in available)
            total = sum(value["value"] * max(1, value["samples"]) for value in available) / sum(max(1, v["samples"]) for v in available)
            if key in ("weight", "height", "body_fat"):
                total = chosen["value"]
        else:
            samples = sum(value["samples"] for value in available)
            total = sum(value["value"] for value in available)
        metrics.append({**chosen, "value": round(total, 4), "samples": samples,
                        "min": min((v["min"] for v in available if v["min"] is not None), default=None),
                        "max": max((v["max"] for v in available if v["max"] is not None), default=None)})
    packages = defaultdict(lambda: {"foreground_ms": 0, "launches": None})
    for entry in entries:
        for app in entry["usage"]["apps"]:
            value = packages[app["package_name"]]
            value["foreground_ms"] += app["foreground_ms"]
            if app["launches"] is not None:
                value["launches"] = (value["launches"] or 0) + app["launches"]
    apps = sorted([{"package_name": package, "label": None, **value} for package, value in packages.items()],
                  key=lambda app: (-app["foreground_ms"], app["package_name"]))
    source_values = defaultdict(list)
    for entry in entries:
        for source in entry["sources"]:
            source_values[source["source"]].append(source)
    sources = [{"source": source, "status": "available" if any(v["status"] == "available" for v in values) else values[-1]["status"],
                "record_count": sum(v["record_count"] for v in values), "complete": all(v["complete"] for v in values)}
               for source, values in sorted(source_values.items())]
    method = "android_bucket_estimate" if any(entry["usage"]["method"] == "android_bucket_estimate" for entry in entries) else "events"
    return _assemble(selected, metrics, apps, sources, sum(entry["device_count"] for entry in entries) if across_devices else
                     max((entry["device_count"] for entry in entries), default=0),
                     max((entry["updated_at_ms"] for entry in entries if entry["updated_at_ms"]), default=None), method)


def read_wellbeing(con, tenant, device_id=None, start_date=None, end_date=None, period="daily"):
    """Return complete calendar days and seven-day weeks in the member's zone."""
    if period not in ("daily", "weekly"):
        raise ValueError("Period must be daily or weekly.")
    zone = account_zone(con, tenant, device_id)
    current = datetime.now(ZoneInfo(zone)).date()
    try:
        last = date.fromisoformat(end_date) if end_date else current
        first = date.fromisoformat(start_date) if start_date else last - timedelta(days=13)
    except (ValueError, TypeError):
        raise ValueError("Dates must use YYYY-MM-DD.") from None
    if last < first or (last - first).days >= MAX_QUERY_DAYS:
        raise ValueError("Select an inclusive date range of 1 to 93 days.")
    params = [tenant]
    where = ""
    if device_id:
        where = " AND device_id=?"
        params.append(device_id)
    devices = [dict(row) for row in con.execute("SELECT device_id,name FROM native_devices WHERE tenant=?" + where + " ORDER BY registered_at_ms,device_id", params)]
    expanded_first = first - timedelta(days=first.weekday())
    expanded_last = last + timedelta(days=6 - last.weekday())
    all_days = {}
    cursor = expanded_first
    while cursor <= expanded_last:
        selected = cursor.isoformat()
        per_device = [_device_day(con, tenant, device["device_id"], selected, zone) for device in devices]
        all_days[selected] = _combine_days(selected, per_device, across_devices=True)
        cursor += timedelta(days=1)
    weeks = []
    cursor = expanded_first
    while cursor <= expanded_last:
        days = [all_days[(cursor + timedelta(days=i)).isoformat()] for i in range(7)]
        summary = _combine_days(cursor.isoformat(), days)
        weeks.append({**summary, "start_date": cursor.isoformat(), "end_date": (cursor + timedelta(days=6)).isoformat(), "days": days})
        cursor += timedelta(days=7)
    return {"timezone": zone, "period": period, "start_date": first.isoformat(), "end_date": last.isoformat(),
            "device_id": device_id, "devices": devices,
            "days": [entry for selected, entry in all_days.items() if first.isoformat() <= selected <= last.isoformat()],
            "weeks": weeks, "notes": NOTES,
            "retention": {"derived_days": RETENTION_DAYS, "max_query_days": MAX_QUERY_DAYS,
                          "max_facts_per_device": MAX_FACTS_PER_DEVICE,
                          "max_fact_bytes_per_device": MAX_FACT_BYTES_PER_DEVICE}}
