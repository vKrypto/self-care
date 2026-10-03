import {strict as assert} from 'node:assert';
import {test} from 'node:test';
import {collectionScreen, readableBytes} from '../src/collection';
import type {Session, SyncStatus} from '../src/types';

const session: Session = {serverUrl: 'https://forma.example.com', token: 'session', user: {id: 'user', email: 'user@example.com', name: 'User', role: 'user'}, onboarded: true};
const local: SyncStatus = {
  enabled: false, lastSyncAt: null, lastError: null, pending: true, historyDays: 30,
  collectionEnabled: true, onboarded: true, connected: false, lastCollectedAt: 100,
  collectionError: null, queuedBatches: 3, queuedBytes: 2048, waitingAccountBatches: 0,
  storageLimitBytes: 100 * 1024 * 1024,
};

test('first launch opens collection setup and sign-in remains optional', () => {
  assert.equal(collectionScreen(null, {...local, onboarded: false}, false, false), 'onboarding');
  assert.equal(collectionScreen(null, {...local, onboarded: false}, true, false), 'onboarding');
  assert.equal(collectionScreen(null, {...local, onboarded: false}, false, true), 'onboarding');
});

test('persisted guest collection opens the dashboard without a session', () => {
  assert.equal(collectionScreen(null, local, false, false), 'dashboard');
  assert.equal(collectionScreen(null, {...local, collectionEnabled: false}, false, false), 'dashboard');
});

test('signed-in users can remain local and explicitly open connection consent', () => {
  assert.equal(collectionScreen(session, local, false, false), 'dashboard');
  assert.equal(collectionScreen(session, local, true, false), 'onboarding');
  assert.equal(collectionScreen(session, {...local, onboarded: false}, false, false), 'onboarding');
});

test('sign-in navigation preserves the collector and returns to its dashboard', () => {
  assert.equal(collectionScreen(null, local, false, true), 'onboarding');
  assert.equal(collectionScreen(null, local, false, false), 'dashboard');
});

test('logout or expired authentication keeps an onboarded local dashboard', () => {
  assert.equal(collectionScreen(null, {...local, authRequired: true}, false, false), 'dashboard');
  assert.equal(collectionScreen(session, {...local, connected: true, enabled: true}, false, false), 'dashboard');
});

test('queue sizes expose retained data and storage limits in readable units', () => {
  assert.equal(readableBytes(0), '0 B');
  assert.equal(readableBytes(100), '100 B');
  assert.equal(readableBytes(local.queuedBytes), '2.0 KB');
  assert.equal(readableBytes(local.storageLimitBytes), '100.0 MB');
  assert.equal(readableBytes(Number.NaN), '0 B');
});
