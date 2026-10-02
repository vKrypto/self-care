import assert from 'node:assert/strict';
import test from 'node:test';
import {runInNewContext} from 'node:vm';
import {DASHBOARD_AUTH_BRIDGE, dashboardAuthEvent} from '../src/dashboard';

const SERVER = 'https://forma.example.com';

function dashboard(response: {url: string; status: number; ok: boolean}) {
  const messages: string[] = [];
  const calls: unknown[][] = [];
  const window = {
    location: {href: `${SERVER}/`, origin: SERVER},
    ReactNativeWebView: {postMessage: (message: string) => messages.push(message)},
    fetch: async (...args: unknown[]) => { calls.push(args); return response; },
  };
  runInNewContext(DASHBOARD_AUTH_BRIDGE, {window, URL});
  // Before-load and after-load injection must not wrap fetch twice.
  runInNewContext(DASHBOARD_AUTH_BRIDGE, {window, URL});
  return {window, messages, calls};
}

test('successful dashboard sign-out reaches native without changing request or response', async () => {
  const response = {url: `${SERVER}/api/auth/logout`, status: 200, ok: true};
  const {window, messages, calls} = dashboard(response);
  const options = {method: 'POST', headers: {'Content-Type': 'application/json'}, body: '{"test":1}'};
  assert.equal(await window.fetch('/api/auth/logout', options), response);
  assert.deepEqual(calls, [['/api/auth/logout', options]]);
  assert.deepEqual(messages.map(message => JSON.parse(message)), [{type: 'signed-out'}]);
});

test('AJAX authentication expiry reaches native even without a page navigation', async () => {
  const {window, messages} = dashboard({url: `${SERVER}/api/me`, status: 401, ok: false});
  await window.fetch('/api/me');
  assert.deepEqual(messages.map(message => JSON.parse(message)), [{type: 'auth-required'}]);
});

test('other origins, non-API resources, failed sign-out and server errors do not sign out the native app', async () => {
  for (const response of [
    {url: 'https://other.example.com/api/me', status: 401, ok: false},
    {url: `${SERVER}/assets/image.png`, status: 401, ok: false},
    {url: `${SERVER}/api/auth/logout`, status: 500, ok: false},
    {url: `${SERVER}/api/me`, status: 503, ok: false},
  ]) {
    const {window, messages} = dashboard(response);
    await window.fetch(response.url);
    assert.deepEqual(messages, []);
  }
});

test('native dashboard bridge only accepts known events from the configured origin', () => {
  assert.equal(dashboardAuthEvent('{"type":"signed-out"}', `${SERVER}/`, SERVER), 'signed-out');
  assert.equal(dashboardAuthEvent('{"type":"auth-required"}', `${SERVER}/`, SERVER), 'auth-required');
  for (const [message, page] of [
    ['{"type":"signed-out"}', 'https://other.example.com/'],
    ['{"type":"signed-out"}', 'about:blank'],
    ['{"type":"signed-out"}', 'blob:https://forma.example.com/123'],
    ['{"type":"unknown"}', `${SERVER}/`],
    ['invalid-json', `${SERVER}/`],
    ['null', `${SERVER}/`],
  ]) {
    assert.equal(dashboardAuthEvent(message, page, SERVER), null);
  }
});
