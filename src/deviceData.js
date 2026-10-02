export const sourceLabels = {
  usage_stats: "App usage totals",
  usage_events: "App, screen & unlock events",
  usage_event_totals: "Screen & unlock summaries",
  network_usage_wifi: "Wi-Fi usage",
  network_usage_mobile: "Mobile data usage",
  visible_apps: "App information",
  device_snapshot: "Device information",
  calendar_events: "Calendar events",
  location: "Location",
  sensors: "Sensor readings",
  health_status: "Health Connect access",
  source_status: "Collection checks",
};

export function sourceLabel(key) {
  return sourceLabels[key] || key.replace(/^health_/, "").replace(/_/g, " ");
}

export function formatDuration(milliseconds) {
  if (milliseconds == null || !Number.isFinite(milliseconds)) return "No data";
  const minutes = Math.round(milliseconds / 60000);
  if (milliseconds > 0 && minutes === 0) return "<1 min";
  return minutes >= 60
    ? `${Math.floor(minutes / 60)}h ${minutes % 60}m`
    : `${minutes} min`;
}

export function formatBytes(bytes) {
  if (bytes == null || !Number.isFinite(bytes)) return "No data";
  if (bytes < 1024) return `${Math.round(bytes)} B`;
  const units = ["KB", "MB", "GB", "TB"];
  let value = bytes / 1024;
  let index = 0;
  while (value >= 1024 && index < units.length - 1) {
    value /= 1024;
    index++;
  }
  return `${value.toFixed(value < 10 ? 1 : 0)} ${units[index]}`;
}

export function formatTime(milliseconds, timezone = "Asia/Kolkata") {
  if (milliseconds == null) return "Not synced yet";
  return new Intl.DateTimeFormat("en", {
    timeZone: timezone,
    year: "numeric",
    month: "short",
    day: "numeric",
    hour: "numeric",
    minute: "2-digit",
  }).format(new Date(milliseconds));
}

export function sumAvailable(...values) {
  const available = values.filter((value) => value != null && Number.isFinite(value));
  return available.length ? available.reduce((total, value) => total + value, 0) : null;
}

export function weekBounds(day) {
  const date = new Date(`${day}T12:00:00Z`);
  const mondayOffset = (date.getUTCDay() + 6) % 7;
  date.setUTCDate(date.getUTCDate() - mondayOffset);
  const start = date.toISOString().slice(0, 10);
  date.setUTCDate(date.getUTCDate() + 6);
  return { start, end: date.toISOString().slice(0, 10) };
}

export function formatMetric(metric) {
  if (metric?.value == null || !Number.isFinite(metric.value)) return "No data";
  if (metric.unit === "ms") return formatDuration(metric.value);
  if (metric.unit === "bytes") return formatBytes(metric.value);
  const value = new Intl.NumberFormat("en", { maximumFractionDigits: 1 }).format(metric.value);
  return `${value}${metric.unit && !["count", "steps"].includes(metric.unit) ? ` ${metric.unit}` : ""}`;
}
