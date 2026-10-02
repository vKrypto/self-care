import {NativeModules} from 'react-native';
import type {HistoryDetails, HistoryPage, HistoryRecordsPage, Permissions, Session, SyncStatus} from './types';

interface DataSyncModule {
  readonly allowLanHttp: boolean;
  readonly defaultServerUrl: string;
  readonly defaultEmail: string;
  readonly defaultPassword: string;
  readonly appVersion: string;
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
  startCollection(historyDays: number): Promise<SyncStatus>;
  collectNow(): Promise<SyncStatus>;
  pauseCollection(): Promise<SyncStatus>;
  resumeCollection(): Promise<SyncStatus>;
  syncNow(): Promise<SyncStatus>;
  pauseSync(): Promise<SyncStatus>;
  resumeSync(): Promise<SyncStatus>;
  logout(): Promise<void>;
  status(): Promise<SyncStatus>;
  syncHistory(offset: number, limit: number): Promise<HistoryPage>;
  collectionDetails(jobId: string): Promise<HistoryDetails>;
  collectionRecords(jobId: string, source: string, offset: number, limit: number): Promise<HistoryRecordsPage>;
}

export const dataSync = NativeModules.FormaDataSync as DataSyncModule;
