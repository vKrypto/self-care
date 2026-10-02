import assert from 'node:assert/strict';
import test from 'node:test';
import {isDashboardNavigationAllowed, normalizeServerUrl} from '../src/validation';

test('release server URLs require HTTPS and an origin without credentials or path', () => {
  assert.equal(normalizeServerUrl(' https://forma.example.com/ ', false), 'https://forma.example.com');
  for (const url of ['http://forma.example.com', 'https://user:secret@forma.example.com', 'https://forma.example.com/api', 'https://forma.example.com?token=x', 'javascript:alert(1)', 'https://', 'https://forma.example.com:70000', 'https://forma.example.com#token', 'https://forma example.com']) {
    assert.throws(() => normalizeServerUrl(url, false));
  }
  assert.equal(normalizeServerUrl('http://10.0.2.2:8000', true), 'http://10.0.2.2:8000');
  assert.equal(normalizeServerUrl('HTTPS://FORMA.EXAMPLE.COM:443', false), 'https://forma.example.com');
  assert.equal(normalizeServerUrl('https://[2001:db8::1]:8443/', false), 'https://[2001:db8::1]:8443');
});

test('LAN HTTP is enabled explicitly and accepts only canonical private IPv4 origins', () => {
  for (const host of ['10.0.0.0', '10.255.255.255', '172.16.0.0', '172.31.255.255', '192.168.0.0', '192.168.255.255']) {
    const origin = `http://${host}:8000`;
    assert.equal(normalizeServerUrl(origin, false, true), origin);
    assert.throws(() => normalizeServerUrl(origin, false, false));
  }
  assert.equal(normalizeServerUrl(' HTTP://192.168.1.10:80/ ', false, true), 'http://192.168.1.10');
  assert.equal(normalizeServerUrl('HTTPS://FORMA.EXAMPLE.COM:443/', false, true), 'https://forma.example.com');
});

test('LAN HTTP rejects public, loopback, hostnames and alternative numeric representations', () => {
  for (const host of [
    '9.255.255.255', '11.0.0.0', '172.15.255.255', '172.32.0.0', '192.167.255.255', '192.169.0.0',
    '127.0.0.1', '169.254.1.1', '0.0.0.0', '8.8.8.8', 'localhost', 'forma.local', '[::1]', '[fd00::1]',
    '10.1', '167772161', '0x0a000001', '012.0.0.1', '10.00.0.1', '10.0.0.256', '%31%30.0.0.1',
  ]) {
    assert.throws(() => normalizeServerUrl(`http://${host}:8000`, false, true), host);
  }
  // Development behavior remains explicit and separate from the LAN flag.
  assert.equal(normalizeServerUrl('http://localhost:8000', true, false), 'http://localhost:8000');
});

test('LAN HTTP rejects credentials, other paths and invalid ports', () => {
  for (const suffix of [':0', ':65536', ':8000/api', ':8000?token=x', ':8000#token']) {
    assert.throws(() => normalizeServerUrl(`http://192.168.1.10${suffix}`, false, true));
  }
  for (const authority of ['user@192.168.1.10', 'user:secret@192.168.1.10', '192.168.1.10.evil.test']) {
    assert.throws(() => normalizeServerUrl(`http://${authority}:8000`, false, true));
  }
  assert.equal(normalizeServerUrl('http://192.168.1.10:65535', false, true), 'http://192.168.1.10:65535');
});

test('dashboard cannot navigate to another origin, port, scheme or lookalike host', () => {
  const origin = 'https://forma.example.com';
  assert.equal(isDashboardNavigationAllowed(`${origin}/api/profile`, origin), true);
  assert.equal(isDashboardNavigationAllowed('https://FORMA.EXAMPLE.COM:443/dashboard', origin), true);
  for (const url of ['https://forma.example.com.evil.test', 'https://evil.test', 'http://forma.example.com', 'https://forma.example.com:444', 'file:///etc/passwd', 'intent://test', 'https://user:password@forma.example.com', 'blob:https://forma.example.com/test']) {
    assert.equal(isDashboardNavigationAllowed(url, origin), false);
  }
});

test('LAN dashboard still cannot navigate to another host, scheme or port', () => {
  const origin = 'http://192.168.1.10:8000';
  assert.equal(isDashboardNavigationAllowed(`${origin}/api/native/dashboard`, origin), true);
  for (const url of ['http://192.168.1.11:8000', 'http://192.168.1.10:8001', 'https://192.168.1.10:8000', 'http://forma.local:8000', 'http://user:password@192.168.1.10:8000']) {
    assert.equal(isDashboardNavigationAllowed(url, origin), false);
  }
});
