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

test('dashboard cannot navigate to another origin, port, scheme or lookalike host', () => {
  const origin = 'https://forma.example.com';
  assert.equal(isDashboardNavigationAllowed(`${origin}/api/profile`, origin), true);
  assert.equal(isDashboardNavigationAllowed('https://FORMA.EXAMPLE.COM:443/dashboard', origin), true);
  for (const url of ['https://forma.example.com.evil.test', 'https://evil.test', 'http://forma.example.com', 'https://forma.example.com:444', 'file:///etc/passwd', 'intent://test', 'https://user:password@forma.example.com', 'blob:https://forma.example.com/test']) {
    assert.equal(isDashboardNavigationAllowed(url, origin), false);
  }
});
