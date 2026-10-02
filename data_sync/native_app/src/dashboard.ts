import {isDashboardNavigationAllowed} from './validation';

// The web dashboard uses fetch for sign-out and session checks. WebView's
// onHttpError only observes main-page requests, so forward those auth events.
// No session tokens or response bodies cross this bridge.
export const DASHBOARD_AUTH_BRIDGE = `
(function () {
  if (window.__formaNativeAuthBridge || typeof window.fetch !== 'function') return;
  window.__formaNativeAuthBridge = true;
  var originalFetch = window.fetch;
  window.fetch = function () {
    return originalFetch.apply(this, arguments).then(function (response) {
      try {
        var url = new URL(response.url, window.location.href);
        if (url.origin === window.location.origin && url.pathname.indexOf('/api/') === 0) {
          var type = response.status === 401 ? 'auth-required'
            : response.ok && url.pathname === '/api/auth/logout' ? 'signed-out' : null;
          if (type && window.ReactNativeWebView) {
            window.ReactNativeWebView.postMessage(JSON.stringify({type: type}));
          }
        }
      } catch (_) {}
      return response;
    });
  };
})();
true;
`;

export type DashboardAuthEvent = 'auth-required' | 'signed-out';

export function dashboardAuthEvent(message: string, pageUrl: string, serverUrl: string): DashboardAuthEvent | null {
  if (pageUrl === 'about:blank' || !isDashboardNavigationAllowed(pageUrl, serverUrl)) { return null; }
  try {
    const event = JSON.parse(message);
    return event?.type === 'auth-required' || event?.type === 'signed-out' ? event.type : null;
  } catch { return null; }
}
