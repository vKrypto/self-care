import React, {useCallback, useEffect, useRef, useState} from 'react';
import {
  ActivityIndicator, AppState, KeyboardAvoidingView, Platform, Pressable,
  ScrollView, StyleSheet, Text, TextInput, View,
} from 'react-native';
import {SafeAreaProvider, SafeAreaView} from 'react-native-safe-area-context';
import {WebView} from 'react-native-webview';
import {dataSync} from './native';
import {DASHBOARD_AUTH_BRIDGE, dashboardAuthEvent} from './dashboard';
import type {Permissions, Session, SyncStatus} from './types';
import {isDashboardNavigationAllowed, normalizeServerUrl} from './validation';

const CONSENT = 'I agree to upload the device usage, calendar, location and health data I grant access to, including sensitive health categories, to my configured Forma server. I can revoke permissions or pause uploads at any time.';

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
  const [syncing, setSyncing] = useState(false);
  const [error, setError] = useState('');
  const [serverUrl, setServerUrl] = useState(__DEV__ ? 'http://10.0.2.2:8000' : '');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [permissions, setPermissions] = useState<Permissions | null>(null);
  const [status, setStatus] = useState<SyncStatus | null>(null);
  const [settings, setSettings] = useState(false);
  const [consent, setConsent] = useState(false);
  const [historyDays, setHistoryDays] = useState('30');
  const [webError, setWebError] = useState('');
  const [webKey, setWebKey] = useState(0);
  const sessionRef = useRef<Session | null>(null);
  const actionBusy = useRef(false);
  const manualSyncBusy = useRef(false);
  const checkingDashboardAuth = useRef(false);
  const refreshRevision = useRef(0);

  const rememberSession = useCallback((saved: Session | null) => {
    sessionRef.current = saved;
    setSession(saved);
  }, []);

  const clearSessionView = useCallback(() => {
    rememberSession(null); setPassword(''); setSettings(false); setConsent(false);
    setStatus(null); setWebError(''); setWebKey(k => k + 1);
  }, [rememberSession]);

  const refresh = useCallback(async () => {
    const expectedToken = sessionRef.current?.token;
    const revision = ++refreshRevision.current;
    const [permissionResult, statusResult] = await Promise.allSettled([dataSync.permissionStatus(), dataSync.status()]);
    if (revision !== refreshRevision.current || sessionRef.current?.token !== expectedToken) { return; }
    if (permissionResult.status === 'fulfilled') { setPermissions(permissionResult.value); }
    if (statusResult.status === 'fulfilled') {
      setStatus(statusResult.value);
      if (statusResult.value.authRequired) {
        clearSessionView(); setError('Your session expired. Sign in again to resume uploads.');
        return;
      }
    }
    if (statusResult.status === 'rejected') { throw statusResult.reason; }
    if (permissionResult.status === 'rejected') { throw permissionResult.reason; }
  }, [clearSessionView]);

  useEffect(() => {
    let active = true;
    dataSync.restoreSession().then(saved => {
      if (!active) { return; }
      rememberSession(saved);
      if (saved) { setServerUrl(saved.serverUrl); setEmail(saved.user.email); }
      return refresh();
    }).catch(e => active && setError(e.message || 'Unable to restore your session.'))
      .finally(() => active && setStarting(false));
    return () => { active = false; };
  }, [refresh, rememberSession]);

  useEffect(() => {
    const subscription = AppState.addEventListener('change', state => {
      if (state === 'active') { refresh().catch(() => {}); }
    });
    const interval = setInterval(() => { if (session) { refresh().catch(() => {}); } }, 10000);
    return () => { subscription.remove(); clearInterval(interval); };
  }, [session, refresh]);

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
    const origin = normalizeServerUrl(serverUrl, __DEV__);
    if (!email.trim() || !password) { throw new Error('Enter your email and password.'); }
    const saved = await dataSync.login(origin, email.trim().toLowerCase(), password);
    setPassword(''); rememberSession(saved); setServerUrl(saved.serverUrl); setSettings(false); setConsent(false); setWebError('');
    setHistoryDays(String((await dataSync.status()).historyDays));
  });

  const submitOnboarding = () => perform(async () => {
    const origin = normalizeServerUrl(serverUrl, __DEV__);
    if (origin !== session?.serverUrl) { throw new Error('Sign out and sign in to this server before uploading data to it.'); }
    if (!permissions?.usageAccess) { throw new Error('Enable Usage Access to connect app and screen history.'); }
    if (!consent) { throw new Error('Review and accept the data upload consent.'); }
    const days = Number(historyDays);
    if (!Number.isInteger(days) || days < 1 || days > 365) { throw new Error('Choose 1–365 days of available history.'); }
    const saved = await dataSync.completeOnboarding(days);
    rememberSession(saved); setSettings(false);
  });

  const logout = () => perform(async () => {
    await dataSync.logout(); clearSessionView();
  });

  const syncNow = async () => {
    if (manualSyncBusy.current || actionBusy.current || !sessionRef.current) { return; }
    const expectedToken = sessionRef.current.token;
    manualSyncBusy.current = true; setSyncing(true); setError('');
    try {
      await dataSync.syncNow();
      if (sessionRef.current?.token === expectedToken) { await refresh(); }
    } catch (e) {
      const cancelled = e instanceof Error && 'code' in e && e.code === 'E_CANCELLED';
      if (!cancelled && sessionRef.current?.token === expectedToken) {
        setError(e instanceof Error ? e.message : 'Sync could not finish. Please retry.');
      }
    } finally { manualSyncBusy.current = false; setSyncing(false); }
  };

  const handleDashboardAuth = async (event: 'auth-required' | 'signed-out', expectedToken: string) => {
    if (checkingDashboardAuth.current || sessionRef.current?.token !== expectedToken) { return; }
    checkingDashboardAuth.current = true;
    try {
      if (event === 'signed-out') {
        await dataSync.logout();
        if (sessionRef.current?.token === expectedToken) { clearSessionView(); }
      } else {
        // An AJAX 401 can mean either the native token expired or the WebView
        // cookie failed. Verify the native session before asking for sign-in.
        const saved = await dataSync.restoreSession();
        if (sessionRef.current?.token !== expectedToken) { return; }
        if (!saved) {
          clearSessionView(); setError('Your session expired. Sign in again to resume uploads.');
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

  const onboarding = session && (!session.onboarded || settings);
  const healthGranted = (permissions?.health.granted_permissions ?? []).length > 0;
  const healthAvailability = permissions?.health.availability ?? permissions?.health.status ?? 'Checking availability';

  return <SafeAreaView style={styles.screen}>
    <View style={styles.header}>
      <View><Text style={styles.brand}>forma<Text style={styles.brandDot}>.</Text></Text><Text style={styles.eyebrow}>ANDROID DATA CONNECT</Text></View>
      {session && <Button title={onboarding ? (session.onboarded ? 'Dashboard' : 'Sign out') : 'Data settings'} secondary disabled={busy}
        onPress={() => onboarding ? (session.onboarded ? setSettings(false) : logout()) : (() => { setHistoryDays(String(status?.historyDays ?? 30)); setConsent(false); setSettings(true); })()} />}
    </View>
    {!!error && <View accessibilityRole="alert" style={styles.error}><Text style={styles.errorText}>{error}</Text></View>}
    {busy && <ActivityIndicator color="#267957" style={styles.spinner} />}

    {!session ? <KeyboardAvoidingView style={styles.flex} behavior={Platform.OS === 'ios' ? 'padding' : undefined}>
      <ScrollView contentContainerStyle={styles.content} keyboardShouldPersistTaps="handled">
        <Text style={styles.kicker}>YOUR ROUTINE, CONNECTED</Text>
        <Text style={styles.title}>Welcome back.</Text>
        <Text style={styles.body}>Sign in with your existing Forma account to connect this device and open your dashboard.</Text>
        <View style={styles.card}>
          <Text style={styles.label}>Server URL</Text>
          <TextInput accessibilityLabel="Server URL" value={serverUrl} onChangeText={setServerUrl} editable={!busy} style={styles.input} autoCapitalize="none" autoCorrect={false} keyboardType="url" placeholder="https://forma.example.com" placeholderTextColor="#8b938d" />
          <Text style={styles.hint}>Enter the HTTPS address of your Forma server.{__DEV__ ? ' For an emulator development build, use http://10.0.2.2:8000.' : ''}</Text>
          <Text style={styles.label}>Email</Text>
          <TextInput accessibilityLabel="Email" value={email} onChangeText={setEmail} editable={!busy} style={styles.input} autoCapitalize="none" autoCorrect={false} keyboardType="email-address" autoComplete="email" placeholder="you@example.com" placeholderTextColor="#8b938d" />
          <Text style={styles.label}>Password</Text>
          <TextInput accessibilityLabel="Password" value={password} onChangeText={setPassword} editable={!busy} style={styles.input} secureTextEntry autoCapitalize="none" autoCorrect={false} autoComplete="current-password" onSubmitEditing={login} />
          <Button title="Sign in" onPress={login} disabled={busy} />
        </View>
        <Text style={styles.hint}>Your password is used once to sign in. A protected session token authenticates future uploads.</Text>
      </ScrollView>
    </KeyboardAvoidingView> : onboarding ? <ScrollView contentContainerStyle={styles.content} keyboardShouldPersistTaps="handled">
      <Text style={styles.kicker}>DATA CONNECTION</Text><Text style={styles.title}>Connect your day.</Text>
      <Text style={styles.body}>Android asks you to approve each source. Only granted sources are uploaded. You can return here any time to review permissions.</Text>
      <View style={styles.card}>
        <Text style={styles.label}>Upload server</Text>
        <TextInput accessibilityLabel="Upload server" value={serverUrl} onChangeText={setServerUrl} style={styles.input} keyboardType="url" autoCapitalize="none" autoCorrect={false} />
        <Text style={styles.hint}>Signed in as {session.user.email}. Changing servers requires signing in to that server.</Text>
        <Text style={styles.label}>Initial history (days)</Text>
        <TextInput accessibilityLabel="Initial history days" value={historyDays} onChangeText={setHistoryDays} style={styles.input} keyboardType="number-pad" maxLength={3} />
        <Text style={styles.hint}>1–365 days where available. Android keeps detailed usage events for only a few days. Health history beyond 30 days needs its separate permission.</Text>
      </View>
      <PermissionCard title="App & screen usage" description="App foreground time, activity events, screen and unlock events, available app metadata, and per-app network totals. Usage Access is required for this connection." granted={!!permissions?.usageAccess} action="Open Usage Access" disabled={busy} onPress={() => perform(() => dataSync.openUsageSettings())} />
      <PermissionCard title="Health Connect" description={`Read available steps, sleep, workouts, vitals, nutrition, hydration, body measurements and reproductive health. Android lets you choose each category, background reads and older history. Provider: ${healthAvailability}.`} granted={healthGranted} action="Choose health permissions" disabled={busy} onPress={() => perform(() => dataSync.requestHealthPermissions())} />
      <Text style={styles.hint}>Health background reads: {permissions?.health.background_granted ? 'enabled' : permissions?.health.background_supported ? 'permission needed' : 'unavailable on this provider; use Sync now'} · Older health history: {permissions?.health.history_granted ? 'enabled' : 'limited by Android'}</Text>
      <Button title="Health Connect settings / install" secondary disabled={busy} onPress={() => perform(() => dataSync.openHealthSettings())} />
      <PermissionCard title="Calendar, location & activity" description="Read calendar events, available location snapshots, and a short step counter sensor snapshot. Also requests notifications for sync failures. Android may grant any subset." granted={!!permissions?.calendar && !!permissions?.location && !!permissions?.activityRecognition && !!permissions?.notifications} action="Request device permissions" disabled={busy} onPress={() => perform(() => dataSync.requestRuntimePermissions())} />
      <Text style={styles.hint}>Calendar {permissions?.calendar ? 'enabled' : 'off'} · Location {permissions?.location ? 'enabled' : 'off'} · Activity {permissions?.activityRecognition ? 'enabled' : 'off'} · Notifications {permissions?.notifications ? 'enabled' : 'off'}</Text>
      <PermissionCard title="Location in the background" description="After granting location above, choose “Allow all the time” in Android app permissions to include available location snapshots during background sync. This does not reconstruct past travel." granted={!!permissions?.backgroundLocation} action="Review background location" disabled={busy} onPress={() => perform(() => dataSync.openBackgroundLocationSettings())} />
      <PermissionCard title="Background & battery" description="An hourly WorkManager job uploads even after the app closes and across reboots. Select unrestricted battery use for fewer delays. Device power management may still delay uploads; force-stop pauses work until you reopen the app." granted={!!permissions?.batteryUnrestricted} action="Review battery restrictions" disabled={busy} onPress={() => perform(() => dataSync.openBatterySettings())} />
      <View style={styles.card}>
        <Text style={styles.cardTitle}>Know what is available</Text>
        <Text style={styles.body}>This connection cannot read private messages, passwords, other apps’ private files or complete lifetime history. Health Connect only contains records shared by participating apps. Missing permissions and unavailable sources are recorded with each upload.</Text>
        <Pressable accessibilityRole="checkbox" accessibilityState={{checked: consent, disabled: busy}} disabled={busy} onPress={() => setConsent(!consent)} style={styles.consent}>
          <Text style={styles.checkbox}>{consent ? '☑' : '☐'}</Text><Text style={[styles.body, styles.flex]}>{CONSENT}</Text>
        </Pressable>
        <Button title={session.onboarded ? 'Save connection & sync' : 'Connect & open dashboard'} disabled={busy || !consent} onPress={submitOnboarding} />
      </View>
      {session.onboarded && <Button title="Sign out & stop this connection" secondary disabled={busy} onPress={logout} />}
    </ScrollView> : <View style={styles.flex}>
      <View style={styles.syncBar}>
        <Text style={styles.cardTitle}>{status?.enabled ? 'Hourly sync enabled' : 'Sync paused'}</Text>
        <Text style={styles.hint}>{status?.lastSyncAt ? `Last upload: ${new Date(status.lastSyncAt).toLocaleString()}` : 'Waiting for the first upload'}{status?.pending ? ' · Upload queued' : ''}</Text>
        {!!status?.lastError && <Text accessibilityRole="alert" style={styles.errorText}>{status.lastError}</Text>}
        <View style={styles.row}>
          <Button title={syncing ? 'Syncing…' : 'Sync now'} secondary disabled={busy || syncing || !status?.enabled} onPress={() => { void syncNow(); }} />
          <Button title={status?.enabled ? 'Pause uploads' : 'Resume uploads'} secondary disabled={busy} onPress={() => perform(() => status?.enabled ? dataSync.pauseSync() : dataSync.resumeSync())} />
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
    </View>}
  </SafeAreaView>;
}

export default function App() {
  return <SafeAreaProvider><Content /></SafeAreaProvider>;
}

const styles = StyleSheet.create({
  screen: {flex: 1, backgroundColor: '#f5f7f2'}, flex: {flex: 1},
  center: {flex: 1, alignItems: 'center', justifyContent: 'center', padding: 24, gap: 16},
  header: {paddingHorizontal: 22, paddingVertical: 14, borderBottomWidth: 1, borderColor: '#e0e6da', flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center'},
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
