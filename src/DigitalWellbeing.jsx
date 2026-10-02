import React, { useState } from "react";
import {
  Activity,
  ArrowRight,
  ChevronLeft,
  ChevronRight,
  Clock,
  HeartPulse,
  Smartphone,
  Trash2,
  Wifi,
} from "lucide-react";
import { labelDate, localDate, shiftDate } from "./api";
import {
  formatBytes,
  formatDuration,
  formatMetric,
  formatTime,
  sourceLabel,
  sumAvailable,
  weekBounds,
} from "./deviceData";
import {
  DataError,
  DataLoading,
  DeleteDevice,
  DeviceEmpty,
  RefreshButton,
  useDeviceRequest,
} from "./DeviceShared";

function MetricCard({ icon: Icon, label, value, hint }) {
  return (
    <div className="wellbeing-metric">
      <div>
        <Icon size={18} />
        <span>{label}</span>
      </div>
      <strong>{value}</strong>
      <small>{hint}</small>
    </div>
  );
}

function initialDate(timezone) {
  try {
    return new Intl.DateTimeFormat("en-CA", {
      timeZone: timezone,
      year: "numeric",
      month: "2-digit",
      day: "2-digit",
    }).format(new Date());
  } catch {
    return localDate();
  }
}

export default function DigitalWellbeing({
  selectedDeviceId = "",
  timezone = "Asia/Kolkata",
  onHistory,
  onRemoved,
}) {
  const [deviceId, setDeviceId] = useState(selectedDeviceId || "");
  const [period, setPeriod] = useState("daily");
  const [day, setDay] = useState(() => initialDate(timezone));
  const [revision, setRevision] = useState(0);
  const [removeDevice, setRemoveDevice] = useState(null);
  const bounds =
    period === "weekly"
      ? weekBounds(day)
      : { start: shiftDate(day, -6), end: day };
  const query = new URLSearchParams({
    period,
    start_date: bounds.start,
    end_date: bounds.end,
  });
  if (deviceId) query.set("device_id", deviceId);
  const state = useDeviceRequest(`/wellbeing?${query}`, revision, {
    poll: true,
  });
  const deviceState = useDeviceRequest("/devices", revision, { poll: true });
  const devices = deviceState.data?.devices || state.data?.devices || [];
  const selectedDevice = devices.find(
    (device) => device.device_id === deviceId,
  );
  const days = state.data?.days || state.data?.weeks?.[0]?.days || [];
  const summary =
    period === "weekly"
      ? state.data?.weeks?.[0]
      : days.find((item) => item.date === day);
  const actualTimezone = state.data?.timezone || timezone;
  const hasData =
    (summary?.sources || []).some((source) => source.record_count > 0) ||
    (summary?.metrics || []).some((metric) => metric.value != null);
  const network = summary?.network;
  const wifi = sumAvailable(network?.wifi_rx_bytes, network?.wifi_tx_bytes);
  const mobile = sumAvailable(
    network?.mobile_rx_bytes,
    network?.mobile_tx_bytes,
  );
  const healthMetrics = Object.values(summary?.health || {});
  const primaryHealthKeys = new Set([
    "steps",
    "distance",
    "active_calories",
    "sleep",
    "exercise",
    "heart_rate",
    "weight",
    "hydration",
  ]);
  const visibleHealthMetrics = healthMetrics.filter(
    (metric) => metric.value != null || primaryHealthKeys.has(metric.key),
  );
  const otherHealthMetrics = healthMetrics.filter(
    (metric) => metric.value == null && !primaryHealthKeys.has(metric.key),
  );
  const screenMetric = summary?.metrics?.find(
    (metric) => metric.key === "screen",
  );
  const highestDuration = Math.max(
    ...days.map((item) => item.usage?.foreground_ms || 0),
    1,
  );
  const apps = [...(summary?.usage?.apps || [])].sort(
    (a, b) => b.foreground_ms - a.foreground_ms,
  );
  const maxAppTime = Math.max(...apps.map((app) => app.foreground_ms || 0), 1);
  function removed() {
    setRemoveDevice(null);
    setDeviceId("");
    setRevision((value) => value + 1);
    onRemoved?.();
  }

  return (
    <div className="device-page wellbeing-page">
      <div className="page-heading">
        <div className="eyebrow">UNDERSTAND YOUR EVERYDAY</div>
        <div className="heading-row">
          <div>
            <h1>Digital wellbeing</h1>
            <p>
              Your app habits, activity, and available health data, filled from
              device syncs.
            </p>
          </div>
          <div className="device-actions">
            <RefreshButton
              loading={state.loading || deviceState.loading}
              onClick={() => setRevision((value) => value + 1)}
            />
            <button
              className="outline"
              onClick={() => onHistory(deviceId || null)}
            >
              <Smartphone size={16} />
              {deviceId ? "Device history" : "Connected devices"}
            </button>
          </div>
        </div>
      </div>
      <section className="wellbeing-toolbar" aria-label="Wellbeing filters">
        <label>
          Device
          <select
            aria-label="Filter by device"
            value={deviceId}
            onChange={(event) => setDeviceId(event.target.value)}
          >
            <option value="">All devices</option>
            {devices.map((device) => (
              <option key={device.device_id} value={device.device_id}>
                {device.name}
              </option>
            ))}
          </select>
        </label>
        <div
          className="wellbeing-period"
          role="group"
          aria-label="Summary period"
        >
          {["daily", "weekly"].map((value) => (
            <button
              key={value}
              aria-pressed={period === value}
              className={period === value ? "selected" : ""}
              onClick={() => setPeriod(value)}
            >
              {value === "daily" ? "Daily" : "Weekly"}
            </button>
          ))}
        </div>
        <div className="wellbeing-date">
          <button
            className="outline"
            aria-label={`Previous ${period === "weekly" ? "week" : "day"}`}
            onClick={() =>
              setDay(shiftDate(day, period === "weekly" ? -7 : -1))
            }
          >
            <ChevronLeft size={17} />
          </button>
          <label>
            {period === "weekly" ? "Week containing" : "Date"}
            <input
              type="date"
              aria-label="Wellbeing date"
              value={day}
              onChange={(event) => {
                if (event.target.value) setDay(event.target.value);
              }}
            />
          </label>
          <button
            className="outline"
            aria-label={`Next ${period === "weekly" ? "week" : "day"}`}
            onClick={() => setDay(shiftDate(day, period === "weekly" ? 7 : 1))}
          >
            <ChevronRight size={17} />
          </button>
          <button
            className="device-back"
            onClick={() => setDay(initialDate(actualTimezone))}
          >
            Today
          </button>
        </div>
      </section>
      <DataError
        error={state.error || deviceState.error}
        retry={() => setRevision((value) => value + 1)}
      />
      {state.loading ? (
        <DataLoading label="Loading daily and weekly data…" />
      ) : (
        state.data && (
          <>
            {state.data.backfill?.pending_batches > 0 && (
              <div className="wellbeing-empty-note" role="status">
                <Activity size={18} />
                <span>
                  Importing previous sync history. Daily and weekly readings
                  will fill in as that history is processed.
                </span>
              </div>
            )}
            <div className="wellbeing-range">
              <h2>
                {period === "weekly"
                  ? `${labelDate(bounds.start, { month: "short", day: "numeric" })} – ${labelDate(bounds.end, { month: "short", day: "numeric", year: "numeric" })}`
                  : labelDate(day, {
                      weekday: "long",
                      month: "long",
                      day: "numeric",
                      year: "numeric",
                    })}
              </h2>
              <span>
                {actualTimezone} ·{" "}
                {selectedDevice?.name || "All connected devices"}
              </span>
            </div>
            {!devices.length ? (
              <DeviceEmpty
                title="Build a picture of your day."
                action={
                  <button className="primary" onClick={() => onHistory(null)}>
                    Connect a device <ArrowRight size={16} />
                  </button>
                }
              >
                Connect your Android phone and upload its first collection to
                start filling daily and weekly data.
              </DeviceEmpty>
            ) : (
              <>
                {!hasData && (
                  <div className="wellbeing-empty-note">
                    <Activity size={18} />
                    <span>
                      No readings for this{" "}
                      {period === "weekly" ? "week" : "day"} yet. Try an earlier
                      date or sync a connected phone. Missing or denied data
                      appears as No data.
                    </span>
                  </div>
                )}
                <div className="wellbeing-metrics">
                  <MetricCard
                    icon={Clock}
                    label="App foreground time"
                    value={formatDuration(summary?.usage?.foreground_ms)}
                    hint={
                      summary?.usage?.method === "android_bucket_estimate"
                        ? "Estimated from Android usage buckets"
                        : "From collected app activity"
                    }
                  />
                  <MetricCard
                    icon={Smartphone}
                    label="Screen active time"
                    value={formatDuration(summary?.usage?.screen_ms)}
                    hint={
                      screenMetric?.method === "android_bucket_estimate"
                        ? "Estimated from Android screen summaries"
                        : "From reported screen events"
                    }
                  />
                  <MetricCard
                    icon={Activity}
                    label="Device unlocks"
                    value={
                      summary?.usage?.unlocks == null
                        ? "No data"
                        : summary.usage.unlocks.toLocaleString()
                    }
                    hint="From reported unlock events"
                  />
                  <MetricCard
                    icon={Wifi}
                    label="Network usage"
                    value={formatBytes(sumAvailable(wifi, mobile))}
                    hint={`Wi-Fi: ${formatBytes(wifi)} · Mobile: ${formatBytes(mobile)}`}
                  />
                </div>
                <div className="wellbeing-two-columns">
                  <section className="device-panel">
                    <div className="device-panel-heading">
                      <Clock size={19} />
                      <h2>Daily app activity</h2>
                      <span>Tap a day to explore</span>
                    </div>
                    <div
                      className="wellbeing-chart"
                      role="group"
                      aria-label="Daily app foreground time"
                    >
                      {days.map((item) => (
                        <button
                          key={item.date}
                          className={`wellbeing-day ${period === "daily" && item.date === day ? "selected" : ""}`}
                          onClick={() => {
                            setPeriod("daily");
                            setDay(item.date);
                          }}
                          aria-label={`${item.date}: ${formatDuration(item.usage?.foreground_ms)}`}
                          title={`${labelDate(item.date, { weekday: "long", month: "short", day: "numeric" })}: ${formatDuration(item.usage?.foreground_ms)}`}
                        >
                          <span className="wellbeing-bar-track">
                            <span
                              className={`wellbeing-bar ${item.usage?.foreground_ms == null ? "missing" : ""}`}
                              style={{
                                height: `${item.usage?.foreground_ms == null ? 3 : item.usage.foreground_ms === 0 ? 0 : Math.max(3, (item.usage.foreground_ms / highestDuration) * 100)}%`,
                              }}
                            />
                          </span>
                          <small>
                            {labelDate(item.date, { weekday: "short" })}
                          </small>
                          <span>{item.date.slice(8)}</span>
                          <small>
                            {formatDuration(item.usage?.foreground_ms)}
                          </small>
                        </button>
                      ))}
                    </div>
                    <p className="device-note">
                      App activity follows collection timestamps in{" "}
                      {actualTimezone}. An empty day does not mean zero
                      activity.
                    </p>
                  </section>
                  <section className="device-panel">
                    <div className="device-panel-heading">
                      <Smartphone size={19} />
                      <h2>Apps used</h2>
                      <span>{apps.length} apps</span>
                    </div>
                    {apps.length ? (
                      <div className="wellbeing-app-list">
                        {apps.map((app) => (
                          <div className="wellbeing-app" key={app.package_name}>
                            <div>
                              <b>{app.label || app.package_name}</b>
                              <strong>
                                {formatDuration(app.foreground_ms)}
                              </strong>
                            </div>
                            <small>
                              {app.package_name}
                              {app.launches == null
                                ? ""
                                : ` · ${app.launches} app starts`}
                            </small>
                            <span className="wellbeing-app-track">
                              <span
                                style={{
                                  width: `${Math.max(1, (app.foreground_ms / maxAppTime) * 100)}%`,
                                }}
                              />
                            </span>
                          </div>
                        ))}
                      </div>
                    ) : (
                      <p className="device-note">
                        No app activity readings are available for this period.
                      </p>
                    )}
                  </section>
                </div>
                <section className="device-panel">
                  <div className="device-panel-heading">
                    <HeartPulse size={20} />
                    <h2>Activity & health</h2>
                    <span>From granted Health Connect sources</span>
                  </div>
                  <p className="device-note">
                    Reported readings appear when your phone has permission and
                    matching records. Different health sources can overlap;
                    source details stay available in device history.
                  </p>
                  <div className="wellbeing-health-grid">
                    {visibleHealthMetrics.length ? (
                      visibleHealthMetrics.map((metric) => (
                        <div
                          className={`wellbeing-health-item ${metric.value == null ? "unavailable" : ""}`}
                          key={metric.key}
                        >
                          <span>{metric.label}</span>
                          <strong>{formatMetric(metric)}</strong>
                          <small>
                            {metric.value == null
                              ? metric.status?.replace(/_/g, " ") ||
                                "Not available"
                              : `${metric.samples || 0} readings${metric.origin ? ` · ${metric.origin}` : ""}`}
                          </small>
                            {metric.origin_count > 1 && <small>{metric.method === "reported_daily_origins" ? "Daily totals use different reported origins" : "Multiple sources · see reported origin"}</small>}
                        </div>
                      ))
                    ) : (
                      <p className="device-note">
                        Health readings have not been shared for this period.
                      </p>
                    )}
                  </div>
                  {otherHealthMetrics.length > 0 && (
                    <details className="wellbeing-health-status">
                      <summary>
                        Other health sources ({otherHealthMetrics.length})
                      </summary>
                      <div className="wellbeing-health-grid">
                        {otherHealthMetrics.map((metric) => (
                          <div
                            className="wellbeing-health-item unavailable"
                            key={metric.key}
                          >
                            <span>{metric.label}</span>
                            <strong>No data</strong>
                            <small>
                              {metric.status?.replace(/_/g, " ") ||
                                "Not available"}
                            </small>
                          </div>
                        ))}
                      </div>
                    </details>
                  )}
                </section>
                <section className="device-panel">
                  <div className="device-panel-heading">
                    <LayersIcon />
                    <h2>Data collected</h2>
                    <span>{summary?.sources?.length || 0} sources</span>
                  </div>
                  <p className="device-note">
                    Usage, health, calendar, location, sensor and device records
                    remain available in each device’s complete sync history.
                  </p>
                  <div className="wellbeing-sources">
                    {(summary?.sources || []).map((source) => (
                      <div key={source.source}>
                        <span>{sourceLabel(source.source)}</span>
                        <small>
                          {source.record_count.toLocaleString()} records
                        </small>
                        <span
                          className={`device-badge ${["ok", "available"].includes(source.status) ? "" : "muted"}`}
                        >
                          {source.status?.replace(/_/g, " ") || "Unknown"}
                          {source.complete === false ? " · Incomplete" : ""}
                        </span>
                      </div>
                    ))}
                  </div>
                </section>
              </>
            )}
            {(state.data.notes || []).length > 0 && (
              <details className="device-panel wellbeing-notes">
                <summary>How this data is counted</summary>
                <ul>
                  {state.data.notes.map((note) => (
                    <li key={note}>{note}</li>
                  ))}
                </ul>
              </details>
            )}
            <section className="device-panel">
              <div className="device-panel-heading">
                <Smartphone size={20} />
                <h2>{deviceId ? "This device" : "Your devices"}</h2>
              </div>
              <div className="wellbeing-device-list">
                {devices
                  .filter(
                    (device) => !deviceId || device.device_id === deviceId,
                  )
                  .map((device) => (
                    <div key={device.device_id}>
                      <div>
                        <b>{device.name}</b>
                        <small>
                          Last synced{" "}
                          {formatTime(device.last_synced_at_ms, actualTimezone)}{" "}
                          · {device.sync_count || 0} successful syncs
                        </small>
                      </div>
                      <div className="device-actions">
                        <button
                          className="outline"
                          onClick={() => onHistory(device.device_id)}
                        >
                          Complete history <ArrowRight size={15} />
                        </button>
                        <button
                          className="danger"
                          onClick={() => setRemoveDevice(device)}
                        >
                          <Trash2 size={15} />
                          Remove device
                        </button>
                      </div>
                    </div>
                  ))}
              </div>
              <p className="device-note">
                Daily summaries are kept for up to{" "}
                {state.data.retention?.derived_days || 365} days. Refresh or
                wait for the next sync to see newly shared readings.
              </p>
            </section>
          </>
        )
      )}
      {removeDevice && (
        <DeleteDevice
          device={removeDevice}
          onCancel={() => setRemoveDevice(null)}
          onDeleted={removed}
        />
      )}
    </div>
  );
}

function LayersIcon() {
  return <Activity size={20} />;
}
