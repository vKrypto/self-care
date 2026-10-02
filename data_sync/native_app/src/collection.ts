import type {Session, SyncStatus} from './types';

export function collectionScreen(session: Session | null, status: SyncStatus | null, settings: boolean, signIn: boolean): 'login' | 'onboarding' | 'dashboard' {
  if (signIn || (!session && !status?.onboarded && !settings)) { return 'login'; }
  if (settings || !status?.onboarded) { return 'onboarding'; }
  return 'dashboard';
}

export function readableBytes(bytes: number): string {
  if (!Number.isFinite(bytes) || bytes <= 0) { return '0 B'; }
  const units = ['B', 'KB', 'MB', 'GB'];
  const index = Math.min(Math.floor(Math.log(bytes) / Math.log(1024)), units.length - 1);
  return `${(bytes / 1024 ** index).toFixed(index === 0 ? 0 : 1)} ${units[index]}`;
}
