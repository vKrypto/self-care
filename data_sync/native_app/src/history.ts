import type {HistoryJob, HistoryPage, HistorySection} from './types';

const SOURCE_LABELS: Record<string, string> = {
  usage_stats: 'App usage totals', usage_events: 'App, screen & unlock events',
  usage_event_stats: 'Usage event totals', network_usage_wifi: 'Wi-Fi usage',
  network_usage_mobile: 'Mobile network usage', visible_apps: 'Available app metadata',
  device_snapshot: 'Device snapshot', calendar_events: 'Calendar events',
  location_snapshot: 'Location snapshot', activity_snapshot: 'Step counter snapshot',
  health_status: 'Health Connect status',
};

export function sourceLabel(name: string): string {
  if (SOURCE_LABELS[name]) { return SOURCE_LABELS[name]; }
  const label = name.replace(/^health_/, '').replace(/_/g, ' ');
  return `${name.startsWith('health_') ? 'Health · ' : ''}${label.charAt(0).toUpperCase()}${label.slice(1)}`;
}

export function uploadLabel(status: HistoryJob['uploadStatus']): string {
  const labels = {queued: 'Stored locally · waiting for upload', synced: 'Server acknowledged',
    partial: 'Some batches acknowledged', no_data: 'No collected data', failed: 'Upload failed'};
  return labels[status] ?? status;
}

export function collectionLabel(status: string): string {
  if (['ok', 'success', 'completed', 'complete', 'collected'].includes(status)) { return 'Collection complete'; }
  if (['running', 'collecting', 'in_progress'].includes(status)) { return 'Collecting'; }
  if (['error', 'failed'].includes(status)) { return 'Collection failed'; }
  if (['cancelled', 'paused', 'stopped'].includes(status)) { return 'Collection stopped'; }
  if (status === 'interrupted') { return 'Collection interrupted'; }
  if (status === 'partial') { return 'Collection partial'; }
  if (status === 'legacy') { return 'Imported collection'; }
  return status.replace(/_/g, ' ');
}

export function historyJobs(pages: HistoryPage[]): HistoryJob[] {
  const jobs = new Map<string, HistoryJob>();
  for (const page of pages) { for (const job of page.jobs) { jobs.set(job.id, job); } }
  return [...jobs.values()];
}

export function mergeSections(existing: HistorySection[], incoming: HistorySection[]): HistorySection[] {
  const sections = new Map(existing.map(section => [section.batchId, section]));
  for (const section of incoming) { sections.set(section.batchId, section); }
  return [...sections.values()];
}

export function jsonEntries(value: unknown): Array<[string, unknown]> | null {
  if (Array.isArray(value)) { return value.map((child, index) => [String(index), child]); }
  if (value !== null && typeof value === 'object') { return Object.entries(value); }
  return null;
}

export function scalarLabel(value: unknown): string {
  if (typeof value === 'string') { return JSON.stringify(value); }
  if (value === null) { return 'null'; }
  return String(value);
}

export function timestampHint(key: string, value: unknown): string | null {
  if (typeof value !== 'number' || !/(?:_ms|Ms|At)$/.test(key) || value < 946684800000 || value > 4102444800000) { return null; }
  return `${new Date(value).toLocaleString()} · milliseconds since Unix epoch`;
}

/** A navigation or refresh invalidates every response belonging to its previous view. */
export class HistoryRequestGate {
  private revision = 0;
  begin(): number { return ++this.revision; }
  invalidate(): void { this.revision++; }
  isCurrent(ticket: number): boolean { return ticket === this.revision; }
}
