import assert from 'node:assert/strict';
import test from 'node:test';
import {collectionLabel, historyJobs, HistoryRequestGate, jsonEntries, mergeSections, scalarLabel, sourceLabel, timestampHint, uploadLabel} from '../src/history';
import type {HistoryJob, HistoryPage} from '../src/types';

const job: HistoryJob = {id: 'job', startedAt: 100, finishedAt: 200, background: true, status: 'completed',
  error: null, batchCount: 2, recordCount: 12, sourceCount: 3, queuedBatches: 2, syncedBatches: 0,
  uploadStatus: 'queued', lastSyncedAt: null, detailsAvailable: true};
const page = (jobs: HistoryJob[]): HistoryPage => ({jobs, nextOffset: null, total: 2,
  retention: {syncedDays: 30, syncedBytes: 67108864, maxJobs: 500}});

test('successful collection stays distinguishable from server acknowledgement', () => {
  assert.equal(collectionLabel(job.status), 'Collection complete');
  assert.equal(uploadLabel(job.uploadStatus), 'Stored locally · waiting for upload');
  assert.equal(uploadLabel('synced'), 'Server acknowledged');
  assert.equal(uploadLabel('partial'), 'Some batches acknowledged');
  assert.equal(uploadLabel('no_data'), 'No collected data');
  assert.equal(uploadLabel('failed'), 'Upload failed');
  assert.equal(collectionLabel('legacy'), 'Imported collection');
  assert.equal(collectionLabel('interrupted'), 'Collection interrupted');
});

test('concurrent collection inserts cannot duplicate overlapping history pages', () => {
  const older = {...job, id: 'older', startedAt: 50};
  const acknowledged = {...job, uploadStatus: 'synced' as const, syncedBatches: 2, queuedBatches: 0};
  const jobs = historyJobs([page([job]), page([acknowledged, older])]);
  assert.deepEqual(jobs.map(item => item.id), ['job', 'older']);
  assert.equal(jobs[0].uploadStatus, 'synced');
});

test('record pagination combines each batch metadata once and retains Health details', () => {
  const original = [{batchId: 'first', metadata: {status: 'ok', details: {history_granted: false}}}];
  const updated = {batchId: 'first', metadata: {status: 'ok', details: {history_granted: true, sdk_status: 3}}};
  const combined = mergeSections(original, [updated, {batchId: 'second', metadata: {status: 'denied'}}]);
  assert.deepEqual(combined, [updated, {batchId: 'second', metadata: {status: 'denied'}}]);
  assert.deepEqual(original[0].metadata.details, {history_granted: false});
});

test('source names get readable labels while unknown categories remain accessible', () => {
  assert.equal(sourceLabel('health_status'), 'Health Connect status');
  assert.equal(sourceLabel('usage_events'), 'App, screen & unlock events');
  assert.equal(sourceLabel('health_heart_rate'), 'Health · Heart rate');
  assert.equal(sourceLabel('new_android_source'), 'New android source');
});

test('JSON fields retain arrays, nulls, numeric and string distinctions', () => {
  const raw = {count: 12, text: '12', enabled: false, absent: null, samples: [{value: 8.25}]};
  assert.deepEqual(jsonEntries(raw), Object.entries(raw));
  assert.deepEqual(jsonEntries(raw.samples), [['0', {value: 8.25}]]);
  assert.equal(jsonEntries(null), null);
  assert.equal(scalarLabel(raw.count), '12');
  assert.equal(scalarLabel(raw.text), '"12"');
  assert.equal(scalarLabel(raw.absent), 'null');
  assert.equal(scalarLabel(raw.enabled), 'false');
});

test('timestamp explanations do not reinterpret durations or replace raw values', () => {
  const instant = 1790000000000;
  assert.match(timestampHint('start_ms', instant) ?? '', /milliseconds since Unix epoch/);
  assert.equal(scalarLabel(instant), String(instant));
  assert.equal(timestampHint('duration_ms', 60000), null);
  assert.equal(timestampHint('steps', instant), null);
  assert.equal(timestampHint('start_ms', String(instant)), null);
});

test('a later view or retry invalidates pending replies from the previous view', async () => {
  const gate = new HistoryRequestGate();
  let firstResolve!: (value: string) => void;
  const firstReply = new Promise<string>(resolve => { firstResolve = resolve; });
  const oldTicket = gate.begin();
  let visible = '';
  const applyOldReply = firstReply.then(value => { if (gate.isCurrent(oldTicket)) { visible = value; } });
  const sourceTicket = gate.begin();
  if (gate.isCurrent(sourceTicket)) { visible = 'new source records'; }
  firstResolve('old job records');
  await applyOldReply;
  assert.equal(visible, 'new source records');
  gate.invalidate();
  assert.equal(gate.isCurrent(sourceTicket), false);
  const retry = gate.begin();
  assert.equal(gate.isCurrent(retry), true);
});
