// Keep navigation and credentials on the configured server's origin.
export function normalizeServerUrl(input: string, development: boolean, allowLanHttp = false): string {
  const value = input.trim();
  let url: URL;
  try { url = new URL(value); }
  catch { throw new Error('Enter a valid server URL, including https://.'); }
  if (url.username || url.password || url.search || url.hash || url.pathname !== '/') {
    throw new Error('Enter the server origin only, for example https://forma.example.com.');
  }
  if (url.port && (!Number.isInteger(Number(url.port)) || Number(url.port) < 1 || Number(url.port) > 65535)) {
    throw new Error('Enter a server port between 1 and 65535.');
  }
  // Validate the original authority: URL parsers otherwise expand shorthand,
  // hexadecimal and octal addresses into apparently valid private IPv4 hosts.
  const lanHost = /^http:\/\/([0-9]+(?:\.[0-9]+){3})(?::[0-9]+)?\/?$/i.exec(value)?.[1];
  const privateHttp = allowLanHttp && !!lanHost && isCanonicalPrivateIpv4(lanHost);
  if (url.protocol !== 'https:' && !(url.protocol === 'http:' && (development || privateHttp))) {
    throw new Error(allowLanHttp
      ? 'HTTP requires a private LAN IPv4 address, such as http://192.168.1.10:8000. Otherwise use HTTPS.'
      : 'Use an HTTPS server URL. HTTP is available in development builds.');
  }
  return url.origin;
}

function isCanonicalPrivateIpv4(host: string): boolean {
  const parts = host.split('.');
  if (parts.length !== 4 || parts.some(part => !/^(0|[1-9][0-9]*)$/.test(part))) { return false; }
  const octets = parts.map(Number);
  if (octets.some(octet => !Number.isInteger(octet) || octet < 0 || octet > 255)) { return false; }
  return octets[0] === 10 || (octets[0] === 172 && octets[1] >= 16 && octets[1] <= 31) ||
    (octets[0] === 192 && octets[1] === 168);
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
