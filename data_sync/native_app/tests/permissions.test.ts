import {strict as assert} from 'node:assert';
import {test} from 'node:test';
import {missingPermissions, setupComplete} from '../src/permissions';
import type {Permissions, SyncStatus} from '../src/types';

const health = ['android.permission.health.READ_STEPS', 'android.permission.health.READ_SLEEP',
  'android.permission.health.READ_HEALTH_DATA_HISTORY'];
const allowed: Permissions = {usageAccess: true, calendar: true, location: true, backgroundLocation: true,
  activityRecognition: true, notifications: true, batteryUnrestricted: true,
  health: {status: 'ok', requested_permissions: health, granted_permissions: health}};
const denied: Permissions = {usageAccess: false, calendar: false, location: false, backgroundLocation: false,
  activityRecognition: false, notifications: false, batteryUnrestricted: false,
  health: {status: 'ok', requested_permissions: health, granted_permissions: []}};
const local: SyncStatus = {enabled: false, connected: false, onboarded: true, collectionEnabled: true,
  lastSyncAt: null, lastError: null, pending: false, historyDays: 30, lastCollectedAt: null,
  collectionError: null, queuedBatches: 0, queuedBytes: 0, waitingAccountBatches: 0, storageLimitBytes: 64 * 1024 * 1024};

test('every granted permission with configured collection completes setup', () => {
  assert.deepEqual(missingPermissions(allowed), []);
  assert.equal(setupComplete(allowed, local), true);
});

test('setup stays incomplete before local collection starts or before Android status loads', () => {
  assert.equal(setupComplete(allowed, {...local, onboarded: false}), false);
  assert.equal(setupComplete(null, local), false);
  assert.equal(setupComplete(allowed, null), false);
});

test('permissions revoked in Android settings are listed in setup-card order', () => {
  const revoked = {...allowed, batteryUnrestricted: false, notifications: false, usageAccess: false};
  assert.deepEqual(missingPermissions(revoked).map(item => item.key), ['usage', 'notifications', 'battery']);
  assert.equal(setupComplete(revoked, local), false);
  assert.deepEqual(missingPermissions(denied).map(item => item.title), ['App & screen usage', 'Calendar', 'Location',
    'Physical activity', 'Notifications', 'Health Connect', 'Background location', 'Background & battery']);
});

test('a retried prompt opens the settings screen that can still grant it', () => {
  const items = Object.fromEntries(missingPermissions(denied).map(item => [item.key, item]));
  for (const key of ['calendar', 'location', 'activity', 'notifications']) {
    assert.equal(items[key].action, 'runtime');
    assert.equal(items[key].fallback, 'appSettings');
  }
  assert.deepEqual([items.health.action, items.health.fallback], ['health', 'healthSettings']);
  assert.deepEqual([items.backgroundLocation.action, items.backgroundLocation.fallback], ['background', 'appSettings']);
  // Usage Access and battery are already settings screens; a retry reopens the same screen.
  assert.deepEqual([items.usage.action, items.usage.fallback], ['usage', 'usage']);
  assert.deepEqual([items.battery.action, items.battery.fallback], ['battery', 'battery']);
});

test('background location can be granted only after foreground location', () => {
  const blocked = missingPermissions(denied).find(item => item.key === 'backgroundLocation');
  assert.equal(blocked?.actionable, false);
  assert.equal(blocked?.detail, 'Allow Location first');
  const ready = missingPermissions({...allowed, backgroundLocation: false});
  assert.deepEqual(ready.map(item => [item.key, item.actionable, item.detail]),
    [['backgroundLocation', true, 'Not allowed all the time']]);
});

test('partly granted Health Connect is missing and reports how many permissions remain', () => {
  const partial = {...allowed, health: {...allowed.health, granted_permissions: health.slice(0, 1)}};
  assert.deepEqual(missingPermissions(partial).map(item => [item.key, item.detail]), [['health', '1 of 3 allowed']]);
  assert.equal(missingPermissions(denied).find(item => item.key === 'health')?.detail, 'Not allowed');
  // Grants outside the requested set, such as removed features, do not count toward completion.
  const extra = {...allowed, health: {...allowed.health, granted_permissions: ['android.permission.health.READ_WEIGHT']}};
  assert.equal(missingPermissions(extra)[0]?.detail, 'Not allowed');
});

test('Health Connect unsupported by the device is not missing; install and status errors stay actionable', () => {
  assert.deepEqual(missingPermissions({...allowed, health: {status: 'unavailable'}}), []);
  assert.equal(setupComplete({...allowed, health: {status: 'unavailable'}}, local), true);
  for (const status of ['update_required', 'error']) {
    const [item] = missingPermissions({...allowed, health: {status}});
    assert.deepEqual([item.key, item.action, item.actionable], ['health', 'healthSettings', true]);
  }
});
