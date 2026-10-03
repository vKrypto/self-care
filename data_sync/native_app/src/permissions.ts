import type {HealthStatus, PermissionAction, Permissions, SyncStatus} from './types';

export interface MissingPermission {
  key: 'usage' | 'calendar' | 'location' | 'activity' | 'notifications' | 'health' | 'backgroundLocation' | 'battery';
  title: string; detail: string;
  action: PermissionAction;
  /** Opened on a retry: Android stops showing a prompt after repeated denials. */
  fallback: PermissionAction;
  /** False until a prerequisite permission is granted. */
  actionable: boolean;
}

function missing(key: MissingPermission['key'], title: string, detail: string, action: PermissionAction,
  fallback: PermissionAction = action, actionable = true): MissingPermission {
  return {key, title, detail, action, fallback, actionable};
}

function missingHealth(health: HealthStatus): MissingPermission | null {
  if (health.status === 'update_required') { return missing('health', 'Health Connect', 'Install or update', 'healthSettings'); }
  if (health.status === 'error') { return missing('health', 'Health Connect', 'Status unavailable', 'healthSettings'); }
  // A device without Health Connect support has nothing to grant.
  if (health.status !== 'ok') { return null; }
  const requested = health.requested_permissions ?? [];
  const granted = requested.filter(permission => health.granted_permissions?.includes(permission)).length;
  if (granted === requested.length) { return null; }
  return missing('health', 'Health Connect', granted ? `${granted} of ${requested.length} allowed` : 'Not allowed', 'health', 'healthSettings');
}

/** Permissions Android currently reports as off, in setup-card order. */
export function missingPermissions(p: Permissions): MissingPermission[] {
  return [
    !p.usageAccess && missing('usage', 'App & screen usage', 'Required', 'usage'),
    !p.calendar && missing('calendar', 'Calendar', 'Not allowed', 'runtime', 'appSettings'),
    !p.location && missing('location', 'Location', 'Not allowed', 'runtime', 'appSettings'),
    !p.activityRecognition && missing('activity', 'Physical activity', 'Not allowed', 'runtime', 'appSettings'),
    !p.notifications && missing('notifications', 'Notifications', 'Off', 'runtime', 'appSettings'),
    missingHealth(p.health),
    !p.backgroundLocation && missing('backgroundLocation', 'Background location',
      p.location ? 'Not allowed all the time' : 'Allow Location first', 'background', 'appSettings', p.location),
    !p.batteryUnrestricted && missing('battery', 'Background & battery', 'Restricted', 'battery'),
  ].filter((item): item is MissingPermission => !!item);
}

/** Setup is complete once local collection is configured and Android reports no missing permission. */
export function setupComplete(permissions: Permissions | null, status: SyncStatus | null): boolean {
  return !!status?.onboarded && !!permissions && missingPermissions(permissions).length === 0;
}
