import {NativeModules} from 'react-native';
import type {Permissions, Session, SyncStatus} from './types';

interface DataSyncModule {
  restoreSession(): Promise<Session | null>;
  login(serverUrl: string, email: string, password: string): Promise<Session>;
  permissionStatus(): Promise<Permissions>;
  openUsageSettings(): Promise<void>;
  requestRuntimePermissions(): Promise<void>;
  requestHealthPermissions(): Promise<void>;
  openBatterySettings(): Promise<void>;
  openHealthSettings(): Promise<void>;
  openBackgroundLocationSettings(): Promise<void>;
  completeOnboarding(historyDays: number): Promise<Session>;
  syncNow(): Promise<void>;
  pauseSync(): Promise<void>;
  resumeSync(): Promise<void>;
  logout(): Promise<void>;
  status(): Promise<SyncStatus>;
}

export const dataSync = NativeModules.FormaDataSync as DataSyncModule;
