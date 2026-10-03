import React, {useCallback, useEffect, useRef, useState} from 'react';
import {
  ActivityIndicator, AppState, Linking, Pressable, ScrollView, StyleSheet, Text, View,
} from 'react-native';
import {SafeAreaProvider, SafeAreaView} from 'react-native-safe-area-context';
import {dataSync} from './native';
import {collectionScreen, readableBytes} from './collection';
import SyncHistoryScreen from './SyncHistoryScreen';
import SetupScreen from './SetupScreen';
import {startLocalSetup, syncServerSetup} from './setup';
import type {Permissions, Session, SyncStatus} from './types';
import {normalizeServerUrl} from './validation';

const ALLOW_LAN_HTTP = dataSync.allowLanHttp === true;
const BUILD_LABEL = `${ALLOW_LAN_HTTP ? 'LAN preview' : __DEV__ ? 'Development' : 'HTTPS preview'} · ${dataSync.appVersion}`;

function Button({title, onPress, disabled = false, secondary = false}: {
  title: string; onPress: () => void; disabled?: boolean; secondary?: boolean;
}) {
  return <Pressable accessibilityRole="button" accessibilityState={{disabled}} disabled={disabled} onPress={onPress}
    style={({pressed}) => [styles.button, secondary && styles.secondaryButton, (disabled || pressed) && styles.dim]}>
    <Text style={[styles.buttonText, secondary && styles.secondaryText]}>{title}</Text>
  </Pressable>;
}

function Content() {
  const [session, setSession] = useState<Session | null>(null);
  const [starting, setStarting] = useState(true);
  const [busy, setBusy] = useState(false);
  const [manualOperation, setManualOperation] = useState<'collect' | 'sync' | null>(null);
  const [error, setError] = useState('');
  const [serverUrl, setServerUrl] = useState(ALLOW_LAN_HTTP ? dataSync.defaultServerUrl : __DEV__ ? 'http://10.0.2.2:8000' : '');
  const [email, setEmail] = useState(ALLOW_LAN_HTTP ? dataSync.defaultEmail : '');
  const [password, setPassword] = useState(ALLOW_LAN_HTTP ? dataSync.defaultPassword : '');
  const [permissions, setPermissions] = useState<Permissions | null>(null);
  const [permissionErrors, setPermissionErrors] = useState<string[]>([]);
  const [status, setStatus] = useState<SyncStatus | null>(null);
  const [settings, setSettings] = useState(false);
  const [showHistory, setShowHistory] = useState(false);
  const [showSignIn, setShowSignIn] = useState(false);
  const [consent, setConsent] = useState(false);
  const [localConsent, setLocalConsent] = useState(false);
  const [historyDays, setHistoryDays] = useState('30');
  const sessionRef = useRef<Session | null>(null);
  const actionBusy = useRef(false);
  const manualSyncBusy = useRef(false);
  const refreshRevision = useRef(0);
  const authRevision = useRef(0);

  const rememberSession = useCallback((saved: Session | null) => {
    sessionRef.current = saved;
    setSession(saved);
  }, []);

  const clearSessionView = useCallback(() => {
    authRevision.current++;
    rememberSession(null); setPassword(''); setSettings(false); setConsent(false); setLocalConsent(false);
    setShowSignIn(false);
  }, [rememberSession]);

  const refresh = useCallback(async () => {
    const expectedToken = sessionRef.current?.token;
    const revision = ++refreshRevision.current;
    const [permissionResult, statusResult] = await Promise.allSettled([dataSync.permissionStatus(), dataSync.status()]);
    if (revision !== refreshRevision.current || sessionRef.current?.token !== expectedToken) { return; }
    if (permissionResult.status === 'fulfilled') { setPermissions(permissionResult.value); }
    if (statusResult.status === 'fulfilled') {
      setStatus(statusResult.value);
      if (statusResult.value.authRequired && sessionRef.current) {
        clearSessionView(); setError('Your session expired. Sign in again to resume uploads.');
        return;
      }
    }
    if (statusResult.status === 'rejected') { throw statusResult.reason; }
    if (permissionResult.status === 'rejected') { throw permissionResult.reason; }
  }, [clearSessionView]);

  useEffect(() => {
    let active = true;
    const revision = authRevision.current;
    // A local dashboard must not wait for an offline server's session check.
    dataSync.status().then(current => {
      if (active) { setStatus(current); setHistoryDays(String(current.historyDays)); }
    }).catch(e => active && setError(e.message || 'Unable to read local collection status.'))
      .finally(() => active && setStarting(false));
    dataSync.permissionStatus().then(current => active && setPermissions(current)).catch(() => {});
    dataSync.restoreSession().then(saved => {
      if (!active || revision !== authRevision.current) { return; }
      rememberSession(saved);
      if (saved) { setServerUrl(saved.serverUrl); setEmail(saved.user.email); setPassword(''); }
      return refresh();
    }).catch(() => {
      if (active && revision === authRevision.current) { setError('Unable to restore your server session. Local collection remains available.'); }
    });
    return () => { active = false; };
  }, [refresh, rememberSession]);

  useEffect(() => {
    const subscription = AppState.addEventListener('change', state => {
      if (state === 'active') { refresh().catch(() => {}); }
    });
    const interval = setInterval(() => { if (AppState.currentState === 'active') { refresh().catch(() => {}); } }, 10000);
    return () => { subscription.remove(); clearInterval(interval); };
  }, [refresh]);

  const perform = async (action: () => Promise<unknown>) => {
    if (actionBusy.current) { return; }
    actionBusy.current = true;
    setBusy(true); setError(''); setPermissionErrors([]);
    try { await action(); await refresh(); }
    catch (e) {
      setError(e instanceof Error ? e.message : 'Please retry.');
      // Failed authenticated requests can invalidate the native token. Refresh
      // immediately so the user can sign in again without waiting for polling.
      await refresh().catch(() => {});
    }
    finally { actionBusy.current = false; setBusy(false); }
  };

  const rememberPermissions = (current: Permissions) => {
    setPermissions(current); setPermissionErrors(current.requestErrors ?? []);
  };

  const startLocally = () => perform(async () => {
    const current = await startLocalSetup(dataSync, {historyDays, consent: localConsent,
      alreadyCollecting: !!status?.onboarded, onPermissions: rememberPermissions});
    setStatus(current); setSettings(true); setShowSignIn(false); setConsent(false); setLocalConsent(false);
  });

  const connectServer = () => perform(async () => {
    const origin = normalizeServerUrl(sessionRef.current?.serverUrl ?? serverUrl, __DEV__, ALLOW_LAN_HTTP);
    const revision = ++authRevision.current;
    await syncServerSetup(dataSync, {
      historyDays, localConfigured: !!status?.onboarded, uploadConsent: consent,
      session: sessionRef.current, serverUrl: origin, email, password, onPermissions: rememberPermissions,
      onSession: saved => {
        if (revision !== authRevision.current) { throw new Error('Your account changed. Review sync consent again.'); }
        rememberSession(saved); setServerUrl(saved.serverUrl); setEmail(saved.user.email); setPassword('');
      },
    });
    setStatus(await dataSync.status()); setSettings(false); setShowSignIn(false); setConsent(false); setLocalConsent(false);
  });

  const logout = () => perform(async () => {
    authRevision.current++;
    await dataSync.logout(); clearSessionView();
  });

  const collectOrSync = async (upload: boolean) => {
    if (manualSyncBusy.current || actionBusy.current) { return; }
    if (upload && (!sessionRef.current || !status?.connected || !status.enabled)) { return; }
    const expectedToken = sessionRef.current?.token;
    manualSyncBusy.current = true; setManualOperation(upload ? 'sync' : 'collect'); setError('');
    try {
      await (upload ? dataSync.syncNow() : dataSync.collectNow());
    } catch (e) {
      const cancelled = e instanceof Error && 'code' in e && e.code === 'E_CANCELLED';
      if (!cancelled && sessionRef.current?.token === expectedToken) {
        setError(e instanceof Error ? e.message : 'Collection could not finish. Please retry.');
      }
    } finally {
      await refresh().catch(() => {});
      manualSyncBusy.current = false; setManualOperation(null);
    }
  };

  const openWebsite = () => perform(async () => {
    const origin = normalizeServerUrl(sessionRef.current?.serverUrl ?? serverUrl, __DEV__, ALLOW_LAN_HTTP);
    try { await Linking.openURL(`${origin}/`); }
    catch { throw new Error('Cannot open the website. Check that a browser is installed and try again.'); }
  });

  if (starting) {
    return <SafeAreaView style={styles.center}><ActivityIndicator color="#267957" /><Text style={styles.body}>Opening Forma…</Text></SafeAreaView>;
  }

  const screen = collectionScreen(session, status, settings, showSignIn);
  const onboarding = screen === 'onboarding';
  const connectionRemoved = !!status?.connectionRemoved;
  const openSettings = () => { setHistoryDays(String(status?.historyDays ?? 30)); setConsent(false); setLocalConsent(false); setSettings(true); setShowSignIn(false); };
  const openSignIn = () => { openSettings(); setError(''); };
  const openPermission = (permission: 'usage' | 'runtime' | 'health' | 'healthSettings' | 'background' | 'battery') => {
    const actions = {
      usage: () => dataSync.openUsageSettings(), runtime: () => dataSync.requestRuntimePermissions(),
      health: () => dataSync.requestHealthPermissions(), healthSettings: () => dataSync.openHealthSettings(),
      background: () => dataSync.openBackgroundLocationSettings(), battery: () => dataSync.openBatterySettings(),
    };
    void perform(async () => rememberPermissions(await actions[permission]()));
  };
  const collectionPanel = <View style={styles.syncBar}>
    <View style={styles.row}><Text style={styles.cardTitle}>Collection & storage</Text><Button title="Sync history" secondary onPress={() => setShowHistory(true)} /></View>
    <Text style={styles.cardTitle}>{status?.collectionEnabled ? 'Hourly collection enabled' : 'Local collection paused'}</Text>
    <Text style={styles.hint}>{status?.lastCollectedAt ? `Last collection: ${new Date(status.lastCollectedAt).toLocaleString()}` : 'Waiting for the first collection'}</Text>
    <Text style={styles.body}>{status?.queuedBatches ?? 0} batches saved on this device · {readableBytes(status?.queuedBytes ?? 0)} / {readableBytes(status?.storageLimitBytes ?? 0)}</Text>
    {!!status?.waitingAccountBatches && <Text style={styles.hint}>{status.waitingAccountBatches} batches are assigned to {session ? 'another account' : 'an account'}. Reconnect the original account to upload them.</Text>}
    {!!status?.waitingConnectionBatches && <Text style={styles.hint}>{status.waitingConnectionBatches} batches belong to a previous connection. Their records remain in Sync history and are not uploaded by this connection.</Text>}
    {!!status?.collectionError && <Text accessibilityRole="alert" style={styles.errorText}>{status.collectionError}</Text>}
    <View style={styles.row}>
      <Button title={manualOperation === 'collect' ? 'Collecting…' : 'Collect now'} secondary
        disabled={busy || manualOperation !== null || !status?.collectionEnabled} onPress={() => { void collectOrSync(false); }} />
      <Button title={status?.collectionEnabled ? 'Pause collection' : 'Resume collection'} secondary disabled={busy}
        onPress={() => perform(() => status?.collectionEnabled ? dataSync.pauseCollection() : dataSync.resumeCollection())} />
    </View>
  </View>;

  return <SafeAreaView style={styles.screen}>
    <View style={styles.header}>
      <View style={styles.brandBlock}><Text style={styles.brand}>forma<Text style={styles.brandDot}>.</Text></Text><Text style={styles.eyebrow}>ANDROID DATA CONNECT</Text>
        <Text style={styles.buildLabel}>{BUILD_LABEL}</Text></View>
      {(status?.onboarded || session) && <Button
        title={onboarding ? (status?.onboarded ? 'Dashboard' : session ? 'Sign out' : 'Set up') : 'Data settings'}
        secondary disabled={busy} onPress={() => {
          if (onboarding && status?.onboarded) { setSettings(false); setShowSignIn(false); }
          else if (onboarding && session) { void logout(); }
          else if (onboarding) { setSettings(false); }
          else { openSettings(); }
        }} />}
    </View>
    {!!error && <View accessibilityRole="alert" style={styles.error}><Text style={styles.errorText}>{error}</Text></View>}
    {busy && <ActivityIndicator color="#267957" style={styles.spinner} />}

    {onboarding ? <SetupScreen permissions={permissions} permissionErrors={permissionErrors} status={status} session={session} busy={busy}
      serverUrl={serverUrl} email={email} password={password} historyDays={historyDays}
      localConsent={localConsent} uploadConsent={consent} allowLanHttp={ALLOW_LAN_HTTP}
      onServerUrl={setServerUrl} onEmail={setEmail} onPassword={setPassword} onHistoryDays={setHistoryDays}
      onLocalConsent={() => setLocalConsent(!localConsent)} onUploadConsent={() => setConsent(!consent)}
      onStart={startLocally} onSync={connectServer} onLogout={logout} onPermission={openPermission}
      onDashboard={() => { setSettings(false); setShowSignIn(false); }} /> : session && status?.connected ? <ScrollView contentContainerStyle={styles.content}>
      {collectionPanel}
      <View style={styles.syncBar}>
        <Text style={styles.cardTitle}>{status.enabled ? 'Hourly uploads enabled' : 'Uploads paused'}</Text>
        <Text style={styles.hint}>{status.lastSyncAt ? `Last upload: ${new Date(status.lastSyncAt).toLocaleString()}` : 'Waiting for the first upload'}</Text>
        {!!status.lastError && <Text accessibilityRole="alert" style={styles.errorText}>{status.lastError}</Text>}
        <View style={styles.row}>
          <Button title={manualOperation === 'sync' ? 'Syncing…' : 'Sync now'} secondary disabled={busy || manualOperation !== null || !status.enabled} onPress={() => { void collectOrSync(true); }} />
          <Button title={status.enabled ? 'Pause uploads' : 'Resume uploads'} secondary disabled={busy} onPress={() => perform(() => status.enabled ? dataSync.pauseSync() : dataSync.resumeSync())} />
        </View>
      </View>
      <View style={styles.card}>
        <Text style={styles.cardTitle}>Forma website</Text>
        <Text selectable style={styles.body}>{session.serverUrl}</Text>
        <Text style={styles.hint}>Opens in your browser. Sign in there if needed.</Text>
        <Button title="Open website" disabled={busy} onPress={openWebsite} />
      </View>
    </ScrollView> : <ScrollView contentContainerStyle={styles.content}>
      <Text style={styles.kicker}>LOCAL DASHBOARD</Text><Text style={styles.title}>Your data stays with you.</Text>
      <Text style={styles.body}>Granted sources are collected into an encrypted queue on this device. Collection continues without a server connection; available history is retried after interruptions.</Text>
      <View style={styles.card}>{collectionPanel}</View>
      <View style={styles.card}>
        <Text style={styles.cardTitle}>{connectionRemoved ? 'Previous connection removed' : 'Connect when you are ready'}</Text>
        <Text style={styles.body}>{connectionRemoved ? 'This device was removed from your server account. Local collection and history remain available. Review upload consent to create a new connection; previously assigned batches remain on this phone.' : session ? `Signed in as ${session.user.email}. Choose to connect this device before local data uploads to ${session.serverUrl}.` : 'Sign in to your Forma server and review upload consent to send saved data to it.'}</Text>
        {status?.authRequired && <Text style={styles.errorText}>Your server session expired. Sign in again to resume uploads.</Text>}
        {!!status?.lastError && <Text style={styles.hint}>{status.lastError}</Text>}
        <Button title={session ? connectionRemoved ? 'Reconnect & review upload consent' : 'Connect account & review upload consent' : 'Sign in to sync'} disabled={busy}
          onPress={session ? openSettings : openSignIn} />
        {session && <Button title="Sign out · keep collecting locally" secondary disabled={busy} onPress={logout} />}
      </View>
    </ScrollView>}
    {showHistory && <SyncHistoryScreen onClose={() => setShowHistory(false)} />}
  </SafeAreaView>;
}

export default function App() {
  return <SafeAreaProvider><Content /></SafeAreaProvider>;
}

const styles = StyleSheet.create({
  screen: {flex: 1, backgroundColor: '#f5f7f2'}, flex: {flex: 1},
  center: {flex: 1, alignItems: 'center', justifyContent: 'center', padding: 24, gap: 16},
  header: {paddingHorizontal: 22, paddingVertical: 14, borderBottomWidth: 1, borderColor: '#e0e6da', flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center'},
  brandBlock: {flexShrink: 1, paddingRight: 10}, buildLabel: {fontSize: 10, lineHeight: 15, color: '#556452', marginTop: 4},
  brand: {fontSize: 32, fontWeight: '800', color: '#213c2b', letterSpacing: -1.5}, brandDot: {color: '#83b448'},
  eyebrow: {fontSize: 9, fontWeight: '700', letterSpacing: 2, color: '#768171'},
  content: {padding: 22, gap: 16, paddingBottom: 40}, kicker: {fontSize: 11, fontWeight: '700', letterSpacing: 2, color: '#267957'},
  title: {fontSize: 34, fontWeight: '700', color: '#213c2b', letterSpacing: -1},
  body: {fontSize: 14, color: '#556452', lineHeight: 22}, hint: {fontSize: 12, color: '#768171', lineHeight: 18},
  card: {padding: 18, borderRadius: 18, borderWidth: 1, borderColor: '#dfe6d8', backgroundColor: '#fff', gap: 12},
  cardTitle: {fontSize: 15, color: '#213c2b', fontWeight: '700', flexShrink: 1},
  row: {flexDirection: 'row', gap: 12, flexWrap: 'wrap', alignItems: 'center', justifyContent: 'space-between'},
  label: {fontSize: 13, color: '#213c2b', fontWeight: '600'},
  input: {borderWidth: 1, borderColor: '#d5decd', borderRadius: 10, padding: 13, fontSize: 15, color: '#213c2b', backgroundColor: '#fafcf8'},
  button: {paddingVertical: 12, paddingHorizontal: 16, borderRadius: 10, backgroundColor: '#267957', alignItems: 'center'},
  secondaryButton: {backgroundColor: '#eaf0e4'}, buttonText: {fontWeight: '700', color: '#fff', fontSize: 13}, secondaryText: {color: '#305a37'},
  dim: {opacity: 0.5}, granted: {fontSize: 11, color: '#267957', fontWeight: '700'}, muted: {fontSize: 11, color: '#768171'},
  error: {margin: 12, padding: 14, borderRadius: 10, backgroundColor: '#fce8df'}, errorText: {color: '#9c412f', fontSize: 13, lineHeight: 19},
  spinner: {padding: 12}, consent: {flexDirection: 'row', alignItems: 'flex-start', gap: 12}, checkbox: {fontSize: 24, color: '#267957'},
  syncBar: {padding: 16, gap: 8, borderBottomWidth: 1, borderColor: '#dfe6d8'},
});
