export interface User { id: string; email: string; name: string; role: string }
export interface Session { serverUrl: string; token: string; user: User; onboarded: boolean }
export interface HealthStatus {
  granted_permissions?: string[]; requested_permissions?: string[];
  background_supported?: boolean; background_granted?: boolean;
  history_supported?: boolean; history_granted?: boolean;
  availability?: string; status?: string; available?: boolean;
  grantedPermissions?: string[]; requestedPermissions?: string[];
  granted?: string[]; backgroundRead?: boolean; historyRead?: boolean;
  backgroundSupported?: boolean; historySupported?: boolean;
  [key: string]: unknown;
}
export interface Permissions {
  usageAccess: boolean; notifications: boolean; activityRecognition: boolean;
  calendar: boolean; location: boolean; backgroundLocation: boolean;
  batteryUnrestricted: boolean; health: HealthStatus;
}
export interface SyncStatus {
  enabled: boolean; lastSyncAt: number | null; lastError: string | null;
  pending: boolean; historyDays: number; authRequired?: boolean;
  collectionEnabled: boolean; onboarded: boolean; connected: boolean;
  connectionRemoved?: boolean;
  lastCollectedAt: number | null; collectionError: string | null;
  queuedBatches: number; queuedBytes: number; waitingAccountBatches: number;
  waitingConnectionBatches?: number;
  storageLimitBytes: number;
}
export interface HistoryJob {
  id: string; startedAt: number; finishedAt: number | null; background: boolean;
  status: string; error: string | null; batchCount: number; recordCount: number;
  sourceCount: number; queuedBatches: number; syncedBatches: number;
  uploadStatus: 'queued' | 'synced' | 'partial' | 'no_data' | 'failed';
  lastSyncedAt: number | null; detailsAvailable: boolean;
}
export interface HistorySource {
  name: string; status: string; recordCount: number; collected: boolean;
  complete: boolean; reason: string | null; detailsAvailable: boolean;
}
export interface HistoryBatch {
  id: string; windowStart: number; windowEnd: number; collectedAt: number;
  syncedAt: number | null; status: 'queued' | 'synced' | 'failed';
  error: string | null; serverUrl: string | null; userId: string | null;
  bytes: number; detailsAvailable: boolean;
}
export interface HistoryPage {
  jobs: HistoryJob[]; nextOffset: number | null; total: number;
  retention: {syncedDays: number; syncedBytes: number; maxJobs: number};
}
export interface HistoryDetails extends HistoryJob {
  sources: HistorySource[]; batches: HistoryBatch[];
  permissions: Record<string, unknown> | null;
}
export interface HistoryRecord {batchId: string; value: unknown}
export interface HistorySection {batchId: string; metadata: Record<string, unknown>}
export interface HistoryRecordsPage {
  jobId: string; source: string; records: HistoryRecord[];
  nextOffset: number | null; total: number; available: number; detailsAvailable: boolean;
  sections: HistorySection[];
}
