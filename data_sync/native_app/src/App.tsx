import React, {useCallback, useEffect, useRef, useState} from 'react';
import {
  ActivityIndicator, AppState, KeyboardAvoidingView, Platform, Pressable,
  ScrollView, StyleSheet, Text, TextInput, View,
} from 'react-native';
import {SafeAreaProvider, SafeAreaView} from 'react-native-safe-area-context';
import {WebView} from 'react-native-webview';
import {dataSync} from './native';
import {DASHBOARD_AUTH_BRIDGE, dashboardAuthEvent} from './dashboard';
import {collectionScreen, readableBytes} from './collection';
import SyncHistoryScreen from './SyncHistoryScreen';
import type {Permissions, Session, SyncStatus} from './types';
import {isDashboardNavigationAllowed, normalizeServerUrl} from './validation';

const CONSENT = 'I agree to upload the device usage, calendar, location and health data I grant access to, including sensitive health categories, to my configured Forma server. I can revoke permissions or pause uploads at any time.';
const LOCAL_CONSENT = 'I agree to collect the device usage, calendar, location and health data I grant access to, including sensitive health categories, and store it encrypted on this device. I can revoke permissions or pause collection at any time.';
const ALLOW_LAN_HTTP = dataSync.allowLanHttp === true;
const BUILD_LABEL = `${ALLOW_LAN_HTTP ? 'LAN preview' : __DEV__ ? 'Development' : 'HTTPS preview'} · ${dataSync.appVersion}`;
const HTTP_NOTICE = 'HTTP has no in-transit encryption: your sign-in details and uploaded data can be read on the network. Use it only on a trusted LAN for testing, or use HTTPS.';

function Button({title, onPress, disabled = false, secondary = false}: {
  title: string; onPress: () => void; disabled?: boolean; secondary?: boolean;
}) {
  return <Pressable accessibilityRole="button" accessibilityState={{disabled}} disabled={disabled} onPress={onPress}
    style={({pressed}) => [styles.button, secondary && styles.secondaryButton, (disabled || pressed) && styles.dim]}>
    <Text style={[styles.buttonText, secondary && styles.secondaryText]}>{title}</Text>
  </Pressable>;
}

function PermissionCard({title, description, granted, action, onPress, disabled}: {
  title: string; description: string; granted: boolean; action: string;
  onPress: () => void; disabled: boolean;
}) {
  return <View style={styles.card}>
    <View style={styles.row}><Text style={styles.cardTitle}>{title}</Text>
      <Text style={granted ? styles.granted : styles.muted}>{granted ? 'Enabled' : 'Optional / pending'}</Text></View>
    <Text style={styles.body}>{description}</Text>
    <Button title={action} onPress={onPress} secondary disabled={disabled} />
  </View>;
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
  const [status, setStatus] = useState<SyncStatus | null>(null);
  const [settings, setSettings] = useState(false);
  const [showHistory, setShowHistory] = useState(false);
  const [showSignIn, setShowSignIn] = useState(false);
  const [consent, setConsent] = useState(false);
  const [localConsent, setLocalConsent] = useState(false);
  const [historyDays, setHistoryDays] = useState('30');
  const [webError, setWebError] = useState('');
  const [webKey, setWebKey] = useState(0);
  const sessionRef = useRef<Session | null>(null);
  const actionBusy = useRef(false);
  const manualSyncBusy = useRef(false);
  const checkingDashboardAuth = useRef(false);
  const refreshRevision = useRef(0);
  const authRevision = useRef(0);

  const rememberSession = useCallback((saved: Session | null) => {
    sessionRef.current = saved;
    setSession(saved);
  }, []);

  const clearSessionView = useCallback(() => {
    authRevision.current++;
    rememberSession(null); setPassword(''); setSettings(false); setConsent(false); setLocalConsent(false);
    setShowSignIn(false); setWebError(''); setWebKey(k => k + 1);
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
    setBusy(true); setError('');
    try { await action(); await refresh(); }
    catch (e) {
      setError(e instanceof Error ? e.message : 'Please retry.');
      // Failed authenticated requests can invalidate the native token. Refresh
      // immediately so the user can sign in again without waiting for polling.
      await refresh().catch(() => {});
    }
    finally { actionBusy.current = false; setBusy(false); }
  };

  const login = () => perform(async () => {
    const origin = normalizeServerUrl(serverUrl, __DEV__, ALLOW_LAN_HTTP);
    if (!email.trim() || !password) { throw new Error('Enter your email and password.'); }
    authRevision.current++;
    const saved = await dataSync.login(origin, email.trim().toLowerCase(), password);
    const current = await dataSync.status();
    setPassword(''); rememberSession(saved); setServerUrl(saved.serverUrl); setShowSignIn(false);
    setStatus(current); setSettings(!current.connected); setConsent(false); setLocalConsent(false); setWebError('');
    setHistoryDays(String(current.historyDays));
  });

  const submitOnboarding = () => perform(async () => {
    if (!permissions?.usageAccess) { throw new Error('Enable Usage Access to collect app and screen history.'); }
    if (session && !consent) { throw new Error('Review and accept the data upload consent.'); }
    if ((!session || !status?.onboarded) && !localConsent) { throw new Error('Review and accept the local collection consent.'); }
    const days = Number(historyDays);
    if (!Number.isInteger(days) || days < 1 || days > 365) { throw new Error('Choose 1–365 days of available history.'); }
    if (session) {
      const saved = await dataSync.completeOnboarding(days);
      rememberSession(saved);
    } else { setStatus(await dataSync.startCollection(days)); }
    setSettings(false); setConsent(false); setLocalConsent(false);
  });

  const startLocally = () => perform(async () => {
    if (!permissions?.usageAccess) { throw new Error('Enable Usage Access to collect app and screen history.'); }
    if (!localConsent) { throw new Error('Review and accept the local collection consent.'); }
    const days = Number(historyDays);
    if (!Number.isInteger(days) || days < 1 || days > 365) { throw new Error('Choose 1–365 days of available history.'); }
    setStatus(await dataSync.startCollection(days)); setSettings(false); setConsent(false); setLocalConsent(false);
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

  const handleDashboardAuth = async (event: 'auth-required' | 'signed-out', expectedToken: string) => {
    if (checkingDashboardAuth.current || sessionRef.current?.token !== expectedToken) { return; }
    checkingDashboardAuth.current = true;
    try {
      if (event === 'signed-out') {
        await dataSync.logout();
        if (sessionRef.current?.token === expectedToken) { clearSessionView(); await refresh(); }
      } else {
        // An AJAX 401 can mean either the native token expired or the WebView
        // cookie failed. Verify the native session before asking for sign-in.
        const saved = await dataSync.restoreSession();
        if (sessionRef.current?.token !== expectedToken) { return; }
        if (!saved) {
          clearSessionView(); setError('Your session expired. Sign in again to resume uploads.');
          await refresh();
        } else {
          setWebError('Dashboard sign-in failed. Check the server connection and reload the dashboard.');
        }
      }
    } catch {
      if (sessionRef.current?.token === expectedToken) {
        setWebError('Cannot verify your dashboard session. Check the server connection and reload.');
      }
    } finally { checkingDashboardAuth.current = false; }
  };

  if (starting) {
    return <SafeAreaView style={styles.center}><ActivityIndicator color="#267957" /><Text style={styles.body}>Opening Forma…</Text></SafeAreaView>;
  }

  const screen = collectionScreen(session, status, settings, showSignIn);
  const onboarding = screen === 'onboarding';
  const openSettings = () => { setHistoryDays(String(status?.historyDays ?? 30)); setConsent(false); setLocalConsent(false); setSettings(true); setShowSignIn(false); };
  const openSignIn = () => { setSettings(false); setConsent(false); setLocalConsent(false); setShowSignIn(true); setError(''); };
  const healthGranted = (permissions?.health.granted_permissions ?? []).length > 0;
  const healthAvailability = permissions?.health.availability ?? permissions?.health.status ?? 'Checking availability';
  const collectionPanel = <View style={styles.syncBar}>
    <View style={styles.row}><Text style={styles.cardTitle}>Collection & storage</Text><Button title="Sync history" secondary onPress={() => setShowHistory(true)} /></View>
    <Text style={styles.cardTitle}>{status?.collectionEnabled ? 'Hourly collection enabled' : 'Local collection paused'}</Text>
    <Text style={styles.hint}>{status?.lastCollectedAt ? `Last collection: ${new Date(status.lastCollectedAt).toLocaleString()}` : 'Waiting for the first collection'}</Text>
    <Text style={styles.body}>{status?.queuedBatches ?? 0} batches saved on this device · {readableBytes(status?.queuedBytes ?? 0)} / {readableBytes(status?.storageLimitBytes ?? 0)}</Text>
    {!!status?.waitingAccountBatches && <Text style={styles.hint}>{status.waitingAccountBatches} batches are assigned to {session ? 'another account' : 'an account'}. Reconnect the original account to upload them.</Text>}
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
      {(status?.onboarded || session || onboarding) && <Button
        title={screen === 'login' ? 'Local dashboard' : onboarding ? (status?.onboarded ? 'Dashboard' : session ? 'Sign out' : 'Back') : 'Data settings'}
        secondary disabled={busy} onPress={() => {
          if (screen === 'login') { setShowSignIn(false); }
          else if (onboarding && status?.onboarded) { setSettings(false); }
          else if (onboarding && session) { void logout(); }
          else if (onboarding) { setSettings(false); }
          else { openSettings(); }
        }} />}
    </View>
    {!!error && <View accessibilityRole="alert" style={styles.error}><Text style={styles.errorText}>{error}</Text></View>}
    {busy && <ActivityIndicator color="#267957" style={styles.spinner} />}

    {screen === 'login' ? <KeyboardAvoidingView style={styles.flex} behavior={Platform.OS === 'ios' ? 'padding' : undefined}>
      <ScrollView contentContainerStyle={styles.content} keyboardShouldPersistTaps="handled">
        <Text style={styles.kicker}>YOUR ROUTINE, CONNECTED</Text>
        <Text style={styles.title}>Your day, on your device.</Text>
        <Text style={styles.body}>Collect and keep your available Android data locally. Sign in when you want to connect a Forma server and upload it.</Text>
        <Button title={status?.onboarded ? 'Continue locally' : 'Skip login · collect locally'} secondary disabled={busy}
          onPress={() => { setShowSignIn(false); setConsent(false); setLocalConsent(false); setSettings(!status?.onboarded); setError(''); }} />
        <View style={styles.card}>
          <Text style={styles.label}>Server URL</Text>
          <TextInput accessibilityLabel="Server URL" value={serverUrl} onChangeText={setServerUrl} editable={!busy} style={styles.input} autoCapitalize="none" autoCorrect={false} keyboardType="url" placeholder={ALLOW_LAN_HTTP ? 'http://192.168.1.10:8000' : 'https://forma.example.com'} placeholderTextColor="#8b938d" />
          <Text style={styles.hint}>{ALLOW_LAN_HTTP ? 'LAN test build: use http://YOUR-COMPUTER-LAN-IP:8000 with a private IPv4 address. HTTPS also works.' : `Enter the HTTPS address of your Forma server.${__DEV__ ? ' For an emulator development build, use http://10.0.2.2:8000.' : ''}`}</Text>
          {(ALLOW_LAN_HTTP || __DEV__) && /^http:/i.test(serverUrl.trim()) && <Text style={styles.errorText}>{HTTP_NOTICE}</Text>}
          <Text style={styles.label}>Email</Text>
          <TextInput accessibilityLabel="Email" value={email} onChangeText={setEmail} editable={!busy} style={styles.input} autoCapitalize="none" autoCorrect={false} keyboardType="email-address" autoComplete="email" placeholder="you@example.com" placeholderTextColor="#8b938d" />
          <Text style={styles.label}>Password</Text>
          <TextInput accessibilityLabel="Password" value={password} onChangeText={setPassword} editable={!busy} style={styles.input} secureTextEntry autoCapitalize="none" autoCorrect={false} autoComplete="current-password" onSubmitEditing={login} />
          <Button title="Sign in" onPress={login} disabled={busy} />
        </View>
        <Text style={styles.hint}>Your password is used once to sign in. A protected session token authenticates future uploads. Signing in asks for your consent before connecting local data.</Text>
      </ScrollView>
    </KeyboardAvoidingView> : onboarding ? <ScrollView contentContainerStyle={styles.content} keyboardShouldPersistTaps="handled">
      <Text style={styles.kicker}>{session ? 'SERVER CONNECTION' : 'LOCAL COLLECTION'}</Text><Text style={styles.title}>{session ? 'Connect your data.' : 'Keep your day locally.'}</Text>
      <Text style={styles.body}>{session ? 'Connect this device and its unassigned local data to your signed-in Forma account. Android asks you to approve each source; only granted sources are collected and uploaded.' : 'Android asks you to approve each source. Granted data is stored encrypted on this device. You can sign in and choose to upload it later.'}</Text>
      <View style={styles.card}>
        {session && <>
          <Text style={styles.label}>Upload server</Text><Text style={styles.body}>{session.serverUrl}</Text>
          {session.serverUrl.startsWith('http:') && <Text style={styles.errorText}>{HTTP_NOTICE}</Text>}
          <Text style={styles.hint}>Signed in as {session.user.email}. Data assigned to another account stays on this device and will only upload when that account reconnects.</Text>
        </>}
        <Text style={styles.label}>Initial history (days)</Text>
        <TextInput accessibilityLabel="Initial history days" value={historyDays} onChangeText={setHistoryDays} style={styles.input} keyboardType="number-pad" maxLength={3} />
        <Text style={styles.hint}>1–365 days where available. Android keeps detailed usage events for only a few days. Health history beyond 30 days needs its separate permission.</Text>
      </View>
      <PermissionCard title="App & screen usage" description="App foreground time, activity events, screen and unlock events, available app metadata, and per-app network totals. Usage Access is required to start collection." granted={!!permissions?.usageAccess} action="Open Usage Access" disabled={busy} onPress={() => perform(() => dataSync.openUsageSettings())} />
      <PermissionCard title="Health Connect" description={`Read available steps, sleep, workouts, vitals, nutrition, hydration, body measurements and reproductive health. Android lets you choose each category, background reads and older history. Provider: ${healthAvailability}.`} granted={healthGranted} action="Choose health permissions" disabled={busy} onPress={() => perform(() => dataSync.requestHealthPermissions())} />
      <Text style={styles.hint}>Health background reads: {permissions?.health.background_granted ? 'enabled' : permissions?.health.background_supported ? 'permission needed' : 'unavailable on this provider; use Collect now'} · Older health history: {permissions?.health.history_granted ? 'enabled' : 'limited by Android'}</Text>
      <Button title="Health Connect settings / install" secondary disabled={busy} onPress={() => perform(() => dataSync.openHealthSettings())} />
      <PermissionCard title="Calendar, location & activity" description="Read calendar events, available location snapshots, and a short step counter sensor snapshot. Also requests notifications for sync failures. Android may grant any subset." granted={!!permissions?.calendar && !!permissions?.location && !!permissions?.activityRecognition && !!permissions?.notifications} action="Request device permissions" disabled={busy} onPress={() => perform(() => dataSync.requestRuntimePermissions())} />
      <Text style={styles.hint}>Calendar {permissions?.calendar ? 'enabled' : 'off'} · Location {permissions?.location ? 'enabled' : 'off'} · Activity {permissions?.activityRecognition ? 'enabled' : 'off'} · Notifications {permissions?.notifications ? 'enabled' : 'off'}</Text>
      <PermissionCard title="Location in the background" description="After granting location above, choose “Allow all the time” in Android app permissions to include available location snapshots during background collection. This does not reconstruct past travel." granted={!!permissions?.backgroundLocation} action="Review background location" disabled={busy} onPress={() => perform(() => dataSync.openBackgroundLocationSettings())} />
      <PermissionCard title="Background & battery" description="Hourly collection continues after the app closes and across reboots. A connected account can also upload in the background. Select unrestricted battery use for fewer delays. Android may delay jobs; force-stop pauses work until you reopen the app." granted={!!permissions?.batteryUnrestricted} action="Review battery restrictions" disabled={busy} onPress={() => perform(() => dataSync.openBatterySettings())} />
      <View style={styles.card}>
        <Text style={styles.cardTitle}>Know what is available</Text>
        <Text style={styles.body}>Available history depends on Android and the apps sharing it with Health Connect. Collection includes each source’s permission and availability status. The encrypted queue has a storage limit; collection pauses adding data when full, and its cursor stays in place so data can be retried.</Text>
        {(!session || !status?.onboarded) && <Pressable accessibilityRole="checkbox" accessibilityState={{checked: localConsent, disabled: busy}} disabled={busy} onPress={() => setLocalConsent(!localConsent)} style={styles.consent}>
          <Text style={styles.checkbox}>{localConsent ? '☑' : '☐'}</Text><Text style={[styles.body, styles.flex]}>{LOCAL_CONSENT}</Text>
        </Pressable>}
        {session && <Pressable accessibilityRole="checkbox" accessibilityState={{checked: consent, disabled: busy}} disabled={busy} onPress={() => setConsent(!consent)} style={styles.consent}>
          <Text style={styles.checkbox}>{consent ? '☑' : '☐'}</Text><Text style={[styles.body, styles.flex]}>{CONSENT}</Text>
        </Pressable>}
        <Button title={session ? 'Connect account & enable uploads' : status?.onboarded ? 'Save local collection settings' : 'Start local collection'}
          disabled={busy || (session ? !consent || (!status?.onboarded && !localConsent) : !localConsent)} onPress={submitOnboarding} />
        {session && !status?.onboarded && <Button title="Collect locally without connecting" secondary disabled={busy || !localConsent} onPress={startLocally} />}
      </View>
      {session && <Button title="Sign out · keep collecting locally" secondary disabled={busy} onPress={logout} />}
    </ScrollView> : session && status?.connected ? <View style={styles.flex}>
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
      {webError ? <View style={styles.center}><Text style={styles.errorText}>{webError}</Text><Button title="Reload dashboard" onPress={() => { setWebError(''); setWebKey(k => k + 1); }} /></View> :
        <WebView key={`${session.user.id}-${webKey}`} style={styles.flex}
          source={{uri: `${session.serverUrl}/api/native/dashboard`, headers: {Authorization: `Bearer ${session.token}`}}}
          originWhitelist={[session.serverUrl]} javaScriptEnabled domStorageEnabled
          sharedCookiesEnabled thirdPartyCookiesEnabled={false} mixedContentMode="never"
          injectedJavaScriptBeforeContentLoaded={DASHBOARD_AUTH_BRIDGE} injectedJavaScript={DASHBOARD_AUTH_BRIDGE}
          onMessage={event => {
            const authEvent = dashboardAuthEvent(event.nativeEvent.data, event.nativeEvent.url, session.serverUrl);
            if (authEvent) { void handleDashboardAuth(authEvent, session.token); }
          }}
          allowFileAccess={false} allowFileAccessFromFileURLs={false} allowUniversalAccessFromFileURLs={false}
          setSupportMultipleWindows={false} startInLoadingState
          onShouldStartLoadWithRequest={request => isDashboardNavigationAllowed(request.url, session.serverUrl)}
          renderLoading={() => <ActivityIndicator color="#267957" style={styles.spinner} />}
          onError={() => setWebError('Cannot open your dashboard. Check your server connection and reload.')}
          onHttpError={event => {
            if (event.nativeEvent.statusCode === 401) { void handleDashboardAuth('auth-required', session.token); }
            else if (event.nativeEvent.statusCode >= 400) { setWebError(`Dashboard returned ${event.nativeEvent.statusCode}. Check that the web app has been built on the server.`); }
          }} />}
    </View> : <ScrollView contentContainerStyle={styles.content}>
      <Text style={styles.kicker}>LOCAL DASHBOARD</Text><Text style={styles.title}>Your data stays with you.</Text>
      <Text style={styles.body}>Granted sources are collected into an encrypted queue on this device. Collection continues without a server connection; available history is retried after interruptions.</Text>
      <View style={styles.card}>{collectionPanel}</View>
      <View style={styles.card}>
        <Text style={styles.cardTitle}>Connect when you are ready</Text>
        <Text style={styles.body}>{session ? `Signed in as ${session.user.email}. Choose to connect this device before local data uploads to ${session.serverUrl}.` : 'Sign in to your Forma server and review upload consent to send saved data and open your web dashboard.'}</Text>
        {status?.authRequired && <Text style={styles.errorText}>Your server session expired. Sign in again to resume uploads.</Text>}
        {!!status?.lastError && <Text style={styles.hint}>{status.lastError}</Text>}
        <Button title={session ? 'Connect account & review upload consent' : 'Sign in to sync'} disabled={busy}
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
