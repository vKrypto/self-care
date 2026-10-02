export interface User { id: string; email: string; name: string; role: string }
export interface Session { serverUrl: string; token: string; user: User; onboarded: boolean }
export interface HealthStatus {
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
}
