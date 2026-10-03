import type {Permissions, Session, SyncStatus} from './types';

interface SetupApi {
  requestCollectionPermissions(): Promise<Permissions>;
  startCollection(historyDays: number): Promise<SyncStatus>;
  login(serverUrl: string, email: string, password: string): Promise<Session>;
  completeOnboarding(historyDays: number): Promise<Session>;
}

export function setupHistoryDays(value: string): number {
  const days = Number(value);
  if (!Number.isInteger(days) || days < 1 || days > 365) {
    throw new Error('Choose 1–365 days of available history.');
  }
  return days;
}

async function requestPermissions(api: SetupApi, onPermissions: (permissions: Permissions) => void) {
  const permissions = await api.requestCollectionPermissions();
  onPermissions(permissions);
  if (!permissions.usageAccess) {
    throw new Error('App & screen usage needs Usage Access. Enable “Permit usage access” for Forma in Android settings, return here, and tap Start again.');
  }
}

export async function startLocalSetup(api: SetupApi, options: {
  historyDays: string; consent: boolean; alreadyCollecting: boolean;
  onPermissions: (permissions: Permissions) => void;
}): Promise<SyncStatus> {
  if (!options.consent && !options.alreadyCollecting) {
    throw new Error('Agree to collect granted data on this device first.');
  }
  const days = setupHistoryDays(options.historyDays);
  await requestPermissions(api, options.onPermissions);
  return api.startCollection(days);
}

export async function syncServerSetup(api: SetupApi, options: {
  historyDays: string; localConfigured: boolean; uploadConsent: boolean;
  session: Session | null; serverUrl: string; email: string; password: string;
  onPermissions: (permissions: Permissions) => void;
  onSession: (session: Session) => void;
}): Promise<Session> {
  if (!options.localConfigured) { throw new Error('Complete Step 1 to start collecting data first.'); }
  if (!options.uploadConsent) { throw new Error('Agree to upload granted data to this server first.'); }
  const days = setupHistoryDays(options.historyDays);
  if (!options.session && (!options.email.trim() || !options.password)) {
    throw new Error('Enter your email and password.');
  }
  await requestPermissions(api, options.onPermissions);
  if (!options.session) {
    const signedIn = await api.login(options.serverUrl, options.email.trim().toLowerCase(), options.password);
    options.onSession(signedIn);
  }
  const connected = await api.completeOnboarding(days);
  options.onSession(connected);
  return connected;
}
