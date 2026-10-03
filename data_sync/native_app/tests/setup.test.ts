import {strict as assert} from 'node:assert';
import {test} from 'node:test';
import {startLocalSetup, syncServerSetup} from '../src/setup';
import type {Permissions, Session, SyncStatus} from '../src/types';

const optionalDenied: Permissions = {usageAccess: true, calendar: false, location: false,
  backgroundLocation: false, activityRecognition: false, notifications: false,
  batteryUnrestricted: false, health: {status: 'unavailable'}};
const local: SyncStatus = {enabled: false, connected: false, onboarded: true, collectionEnabled: true,
  lastSyncAt: null, lastError: null, pending: false, historyDays: 30, lastCollectedAt: null,
  collectionError: null, queuedBatches: 0, queuedBytes: 0, waitingAccountBatches: 0, storageLimitBytes: 64 * 1024 * 1024};
const session: Session = {serverUrl: 'https://forma.example.com', token: 'token', onboarded: false,
  user: {id: 'user', email: 'user@example.com', name: 'User', role: 'user'}};

function fixture(permissions = optionalDenied) {
  const calls: string[] = [];
  return {calls, api: {
    requestCollectionPermissions: async () => { calls.push('permissions'); return permissions; },
    startCollection: async (days: number) => { calls.push(`local:${days}`); return local; },
    login: async (_url: string, email: string) => { calls.push(`login:${email}`); return session; },
    completeOnboarding: async (days: number) => { calls.push(`connect:${days}`); return {...session, onboarded: true}; },
  }};
}

function syncOptions(overrides = {}) {
  return {historyDays: '30', localConfigured: true, uploadConsent: true, session: null as Session | null,
    serverUrl: session.serverUrl, email: ' User@Example.com ', password: 'test',
    onPermissions: () => {}, onSession: (_saved: Session) => {}, ...overrides};
}

test('local start opens permission flow before persistence and never signs in or connects', async () => {
  const {api, calls} = fixture();
  let displayed: Permissions | undefined;
  const result = await startLocalSetup(api, {historyDays: '30', consent: true, alreadyCollecting: false,
    onPermissions: value => { displayed = value; }});
  assert.equal(result.connected, false);
  assert.equal(displayed, optionalDenied);
  assert.deepEqual(calls, ['permissions', 'local:30']);
});

test('Usage Access denial updates permission display and cannot start collection or login', async () => {
  const denied = {...optionalDenied, usageAccess: false};
  const {api, calls} = fixture(denied);
  let displayed: Permissions | undefined;
  await assert.rejects(startLocalSetup(api, {historyDays: '30', consent: true, alreadyCollecting: false,
    onPermissions: value => { displayed = value; }}), /Permit usage access/);
  assert.equal(displayed, denied);
  assert.deepEqual(calls, ['permissions']);
  await assert.rejects(syncServerSetup(api, syncOptions()), /Usage Access/);
  assert.deepEqual(calls, ['permissions', 'permissions']);
});

test('consent and history validation occur before any Android prompt or state change', async () => {
  const {api, calls} = fixture();
  await assert.rejects(startLocalSetup(api, {historyDays: '30', consent: false, alreadyCollecting: false, onPermissions: () => {}}), /Agree/);
  for (const value of ['', '0', '366', '2.5', 'no']) {
    await assert.rejects(startLocalSetup(api, {historyDays: value, consent: true, alreadyCollecting: false, onPermissions: () => {}}), /1–365/);
  }
  await assert.rejects(syncServerSetup(api, syncOptions({localConfigured: false})), /Step 1/);
  await assert.rejects(syncServerSetup(api, syncOptions({uploadConsent: false})), /Agree/);
  await assert.rejects(syncServerSetup(api, syncOptions({password: ''})), /email and password/);
  assert.deepEqual(calls, []);
});

test('explicit sync checks permissions, signs in, then enables uploads in that order', async () => {
  const {api, calls} = fixture();
  const remembered: Session[] = [];
  const connected = await syncServerSetup(api, syncOptions({onSession: (saved: Session) => { remembered.push(saved); }}));
  assert.deepEqual(calls, ['permissions', 'login:user@example.com', 'connect:30']);
  assert.equal(connected.onboarded, true);
  assert.deepEqual(remembered, [session, connected]);
});

test('reconnecting a signed-in account never repeats password login', async () => {
  const {api, calls} = fixture();
  await syncServerSetup(api, syncOptions({session, email: '', password: ''}));
  assert.deepEqual(calls, ['permissions', 'connect:30']);
});

test('failed server connection preserves the signed-in session and existing local collector', async () => {
  const {api, calls} = fixture();
  let remembered: Session | undefined;
  api.completeOnboarding = async () => { calls.push('server failed'); throw new Error('offline'); };
  await assert.rejects(syncServerSetup(api, syncOptions({onSession: (saved: Session) => { remembered = saved; }})), /offline/);
  assert.equal(remembered, session);
  assert.deepEqual(calls, ['permissions', 'login:user@example.com', 'server failed']);
});

test('reviewing an existing collector keeps its consent and does not enable uploads', async () => {
  const {api, calls} = fixture();
  await startLocalSetup(api, {historyDays: '14', consent: false, alreadyCollecting: true, onPermissions: () => {}});
  assert.deepEqual(calls, ['permissions', 'local:14']);
});
