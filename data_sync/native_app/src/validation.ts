// Keep navigation and credentials on the configured server's origin.
export function normalizeServerUrl(input: string, development: boolean): string {
  let url: URL;
  try { url = new URL(input.trim()); }
  catch { throw new Error('Enter a valid server URL, including https://.'); }
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
  try {
    const url = new URL(destination);
    const server = new URL(serverUrl);
    return !url.username && !url.password && url.protocol === server.protocol && url.origin === server.origin;
  }
  catch { return false; }
}
