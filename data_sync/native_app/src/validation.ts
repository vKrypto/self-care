// Keep navigation and credentials on the configured server's origin.
export function normalizeServerUrl(input: string, development: boolean): string {
  const url = new URL(input.trim());
  if (url.username || url.password || url.search || url.hash || url.pathname !== '/') {
    throw new Error('Enter the server origin only, for example https://forma.example.com.');
  }
  if (url.protocol !== 'https:' && !(development && url.protocol === 'http:')) {
    throw new Error('Use an HTTPS server URL. HTTP is available in development builds.');
  }
  return url.origin;
}

export function isDashboardNavigationAllowed(destination: string, serverUrl: string): boolean {
  if (destination === 'about:blank') { return true; }
  try { return new URL(destination).origin === new URL(serverUrl).origin; }
  catch { return false; }
}
