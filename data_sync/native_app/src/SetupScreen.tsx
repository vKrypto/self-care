import React from 'react';
import {Alert, KeyboardAvoidingView, Platform, Pressable, ScrollView, StyleSheet, Text, TextInput, View} from 'react-native';
import type {Permissions, Session, SyncStatus} from './types';

interface Props {
  permissions: Permissions | null; status: SyncStatus | null; session: Session | null;
  permissionErrors: string[];
  busy: boolean; serverUrl: string; email: string; password: string; historyDays: string;
  localConsent: boolean; uploadConsent: boolean; allowLanHttp: boolean;
  onServerUrl: (value: string) => void; onEmail: (value: string) => void;
  onPassword: (value: string) => void; onHistoryDays: (value: string) => void;
  onLocalConsent: () => void; onUploadConsent: () => void;
  onStart: () => void; onSync: () => void; onLogout: () => void; onDashboard: () => void;
  onPermission: (permission: 'usage' | 'runtime' | 'health' | 'healthSettings' | 'background' | 'battery') => void;
}

function Info({title, description, why}: {title: string; description: string; why: string}) {
  return <Pressable accessibilityRole="button" accessibilityLabel={`About ${title}`} hitSlop={4}
    style={styles.info} onPress={() => Alert.alert(title, `${description}\n\nWhy? ${why}`)}>
    <Text style={styles.infoText}>ⓘ</Text>
  </Pressable>;
}

function Action({title, onPress, disabled, secondary = false}: {
  title: string; onPress: () => void; disabled: boolean; secondary?: boolean;
}) {
  return <Pressable accessibilityRole="button" accessibilityState={{disabled}} disabled={disabled} onPress={onPress}
    style={({pressed}) => [styles.button, secondary && styles.secondaryButton, (disabled || pressed) && styles.dim]}>
    <Text style={[styles.buttonText, secondary && styles.secondaryText]}>{title}</Text>
  </Pressable>;
}

function PermissionRow({title, description, why, granted, status, onPress, busy, action = 'Allow'}: {
  title: string; description: string; why: string; granted: boolean; status?: string;
  onPress: () => void; busy: boolean; action?: string;
}) {
  return <View style={styles.permissionRow}>
    <View style={styles.permissionName}><Text style={styles.permissionTitle}>{title}</Text>
      <Text style={[styles.permissionStatus, granted && styles.enabled]}>{status ?? (granted ? 'Allowed' : 'Optional')}</Text></View>
    <Info title={title} description={description} why={why} />
    <Action title={granted ? 'Review' : action} secondary onPress={onPress} disabled={busy} />
  </View>;
}

export default function SetupScreen(props: Props) {
  const {permissions: p, status, session, busy} = props;
  const healthRequested = p?.health.requested_permissions ?? [];
  const healthGranted = p?.health.granted_permissions ?? [];
  const healthAvailable = p?.health.status === 'ok' && healthRequested.length > 0;
  const healthReady = healthAvailable && healthRequested.every(permission => healthGranted.includes(permission));
  const collecting = !!status?.collectionEnabled;
  const localConfigured = !!status?.onboarded;
  const runtime = () => props.onPermission('runtime');
  return <KeyboardAvoidingView style={styles.flex} behavior={Platform.OS === 'ios' ? 'padding' : undefined}>
    <ScrollView contentContainerStyle={styles.content} keyboardShouldPersistTaps="handled">
      <Text style={styles.title}>Set up your data</Text>
      <Text style={styles.subtitle}>Collect on this device. Connect a server when you’re ready.</Text>
      <View style={styles.card}>
        <View style={styles.cardHeader}>
          <Text style={styles.cardTitle}>1. Start collecting data</Text>
          <Action title={busy ? 'Working…' : collecting ? 'Review' : 'Start'} onPress={props.onStart}
            disabled={busy || (!localConfigured && !props.localConsent)} />
        </View>
        <Text style={styles.subtitle}>{collecting ? 'Hourly collection is on.' : 'Start opens the missing Android permission screens.'}</Text>
        {!!props.permissionErrors.length && <Text accessibilityRole="alert" style={styles.httpNote}>{props.permissionErrors.join('\n')}</Text>}
        <PermissionRow title="App & screen usage" granted={!!p?.usageAccess} status={p?.usageAccess ? 'Allowed · required' : 'Required'}
          description="Android Usage Access reads app foreground time, activity transitions, available screen/unlock events, app metadata and per-app network totals. In Android settings, select Forma and turn on Permit usage access. Detailed event history is usually kept for only a few days."
          why="To build your app and screen usage history. Collection needs this access." busy={busy} onPress={() => props.onPermission('usage')} />
        <PermissionRow title="Calendar" granted={!!p?.calendar} description="Read calendar events visible to Android, including event details. No calendar events are changed."
          why="To include your schedule alongside daily activity." busy={busy} onPress={runtime} />
        <PermissionRow title="Location" granted={!!p?.location} description="Read available location snapshots. Approximate location is accepted; precise location is optional. This cannot reconstruct past travel."
          why="To add location context to collection jobs." busy={busy} onPress={runtime} />
        <PermissionRow title="Physical activity" granted={!!p?.activityRecognition} description="Access the step counter sensor where available. A collection takes a short sensor snapshot; this is not a complete historical step log."
          why="To include available movement data." busy={busy} onPress={runtime} />
        <PermissionRow title="Notifications" granted={!!p?.notifications} description="Allow Forma to show collection and sync failure notifications. This does not read notifications from other apps."
          why="To let you know when collection or uploads need attention." busy={busy} onPress={runtime} />
        <PermissionRow title="Health Connect" granted={healthReady}
          status={healthReady ? 'Allowed' : healthAvailable ? (healthGranted.length ? 'Partly allowed' : 'Optional') : 'Unavailable / needs update'}
          action={healthAvailable ? 'Choose' : 'Set up'}
          description={`Read the categories you approve: steps, sleep, workouts, vitals, nutrition, hydration, body measurements and sensitive reproductive health. Supported providers also offer background reads and history older than 30 days. Only records already shared with Health Connect can be collected. Background: ${p?.health.background_granted ? 'allowed' : p?.health.background_supported ? 'not allowed' : 'unsupported'}. Older history: ${p?.health.history_granted ? 'allowed' : p?.health.history_supported ? 'not allowed' : 'unsupported'}. Provider: ${p?.health.status ?? 'checking'}.`}
          why="To include health data you choose to share. You can approve any subset or skip it." busy={busy}
          onPress={() => props.onPermission(healthAvailable ? 'health' : 'healthSettings')} />
        <PermissionRow title="Background location" granted={!!p?.backgroundLocation} description="After granting Location, select Allow all the time in Android app permissions. Android 10 may show a separate prompt."
          why="To include available location snapshots in hourly jobs while the app is closed." busy={busy} onPress={() => props.onPermission('background')} />
        <PermissionRow title="Background & battery" granted={!!p?.batteryUnrestricted} status={p?.batteryUnrestricted ? 'Unrestricted' : 'Restricted'}
          description="In Android’s battery list, choose All apps, select Forma, and allow unrestricted background use or choose Don’t optimize. Labels vary by Android version. Hourly jobs use Android WorkManager and resume after reboot. Android can still delay jobs; force-stop pauses work until you reopen Forma."
          why="To reduce delays in collection and uploads while the app is closed." busy={busy} onPress={() => props.onPermission('battery')} />
        <View style={styles.historyRow}><Text style={styles.permissionTitle}>Initial history</Text>
          <TextInput accessibilityLabel="Initial history days" value={props.historyDays} onChangeText={props.onHistoryDays}
            editable={!busy} style={styles.daysInput} keyboardType="number-pad" maxLength={3} /><Text style={styles.subtitle}>days</Text>
          <Info title="Initial history" description="Choose 1–365 days of available history. Usage events are often retained for only a few days. Health data beyond 30 days needs its separate history permission."
            why="To choose how far back your first collection looks. Available records depend on Android and each source." />
        </View>
        {!localConfigured && <View style={styles.consentRow}>
          <Pressable accessibilityRole="checkbox" accessibilityState={{checked: props.localConsent, disabled: busy}}
            disabled={busy} onPress={props.onLocalConsent} style={styles.consent}>
            <Text style={styles.checkbox}>{props.localConsent ? '☑' : '☐'}</Text>
            <Text style={styles.consentText}>Collect granted data on this device.</Text>
          </Pressable>
          <Info title="Local collection consent" description="I agree to collect the device usage, calendar, location and health data I grant access to, including sensitive health categories, and store it encrypted on this device. I can revoke permissions or pause collection at any time."
            why="To keep a local history. Step 1 does not enable a server connection; existing authorized uploads keep their current setting." />
        </View>}
      </View>
      <View style={styles.card}>
        <View style={styles.cardHeader}><Text style={styles.cardTitle}>2. Sync to server</Text>
          <Action title={busy ? 'Working…' : 'Sync'} onPress={props.onSync} disabled={busy || !localConfigured || !props.uploadConsent} /></View>
        <Text style={styles.subtitle}>{localConfigured ? 'Optional · upload after your approval.' : 'Complete Step 1 first. No server is needed to collect.'}</Text>
        <View style={styles.fieldHeader}><Text style={styles.label}>Server URL</Text>
          <Info title="Server connection" description={`Enter your Forma server address. ${props.allowLanHttp ? 'This LAN preview accepts HTTP on a private IPv4 address; HTTPS also works. HTTP does not encrypt your sign-in details or uploads, so use it only on a trusted LAN for testing.' : 'Use HTTPS to encrypt sign-in details and uploads.'} Your password is used to sign in; a protected session token authenticates future uploads.`}
            why="To send data to your own Forma account and see it on the website." /></View>
        <TextInput accessibilityLabel="Server URL" value={session?.serverUrl ?? props.serverUrl} onChangeText={props.onServerUrl}
          editable={!busy && !session} style={styles.input} autoCapitalize="none" autoCorrect={false} keyboardType="url"
          placeholder={props.allowLanHttp ? 'http://192.168.1.10:8000' : 'https://forma.example.com'} placeholderTextColor="#8b938d" />
        {!!session?.serverUrl.startsWith('http:') || (!session && /^http:/i.test(props.serverUrl.trim())) ?
          <Text style={styles.httpNote}>HTTP · trusted LAN testing only. Details ⓘ above.</Text> : null}
        {session ? <View style={styles.accountRow}><Text style={[styles.subtitle, styles.flex]}>{session.user.email}</Text>
          <Action title="Sign out" secondary onPress={props.onLogout} disabled={busy} /></View> : <>
          <Text style={styles.label}>Email</Text><TextInput accessibilityLabel="Email" value={props.email} onChangeText={props.onEmail}
            editable={!busy} style={styles.input} autoCapitalize="none" autoCorrect={false} keyboardType="email-address" autoComplete="email" placeholder="you@example.com" placeholderTextColor="#8b938d" />
          <Text style={styles.label}>Password</Text><TextInput accessibilityLabel="Password" value={props.password} onChangeText={props.onPassword}
            editable={!busy} style={styles.input} secureTextEntry autoCapitalize="none" autoCorrect={false} autoComplete="current-password" />
        </>}
        {status?.connectionRemoved && <View style={styles.fieldHeader}><Text style={[styles.subtitle, styles.flex]}>Previous connection removed. Sync creates a new one.</Text>
          <Info title="Reconnect device" description="A new connection uploads future collections and unassigned local data after approval. Batches assigned to the removed connection remain on this phone and are not reassigned."
            why="To reconnect without changing the account or connection that owns saved records." /></View>}
        <View style={styles.consentRow}>
          <Pressable accessibilityRole="checkbox" accessibilityState={{checked: props.uploadConsent, disabled: busy || !localConfigured}}
            disabled={busy || !localConfigured} onPress={props.onUploadConsent} style={styles.consent}>
            <Text style={styles.checkbox}>{props.uploadConsent ? '☑' : '☐'}</Text><Text style={styles.consentText}>Upload granted data to this server.</Text>
          </Pressable>
          <Info title="Upload consent" description="I agree to upload the device usage, calendar, location and health data I grant access to, including sensitive health categories, to my configured Forma server. I can revoke permissions or pause uploads at any time. Previously unassigned local records can be included; records owned by another account remain assigned to that account."
            why="To enable periodic uploads. You decide separately from collecting on the device." />
        </View>
      </View>
      {localConfigured && <Action title="Open dashboard" secondary onPress={props.onDashboard} disabled={busy} />}
    </ScrollView>
  </KeyboardAvoidingView>;
}

const styles = StyleSheet.create({
  flex: {flex: 1}, content: {padding: 16, gap: 10, paddingBottom: 28},
  title: {fontSize: 24, fontWeight: '700', color: '#213c2b'}, subtitle: {fontSize: 12, color: '#667360', lineHeight: 17},
  card: {padding: 12, borderRadius: 16, borderWidth: 1, borderColor: '#dfe6d8', backgroundColor: '#fff', gap: 8},
  cardHeader: {flexDirection: 'row', gap: 8, alignItems: 'center', justifyContent: 'space-between'},
  cardTitle: {fontSize: 16, fontWeight: '700', color: '#213c2b', flex: 1},
  permissionRow: {flexDirection: 'row', alignItems: 'center', borderBottomWidth: StyleSheet.hairlineWidth, borderColor: '#e2e7dd', minHeight: 44},
  permissionName: {flex: 1}, permissionTitle: {fontSize: 13, color: '#213c2b', fontWeight: '600'},
  permissionStatus: {fontSize: 10, lineHeight: 14, color: '#768171'}, enabled: {color: '#267957'},
  info: {minWidth: 44, minHeight: 44, justifyContent: 'center', alignItems: 'center'}, infoText: {fontSize: 20, color: '#267957'},
  button: {minHeight: 44, paddingVertical: 10, paddingHorizontal: 13, borderRadius: 9, backgroundColor: '#267957', alignItems: 'center', justifyContent: 'center'},
  secondaryButton: {backgroundColor: '#eaf0e4'}, buttonText: {fontWeight: '700', color: '#fff', fontSize: 12}, secondaryText: {color: '#305a37'}, dim: {opacity: 0.5},
  fieldHeader: {flexDirection: 'row', alignItems: 'center'}, label: {fontSize: 12, fontWeight: '600', color: '#213c2b'},
  input: {borderWidth: 1, borderColor: '#d5decd', borderRadius: 9, padding: 10, fontSize: 14, color: '#213c2b', backgroundColor: '#fafcf8'},
  historyRow: {flexDirection: 'row', alignItems: 'center', gap: 7},
  daysInput: {marginLeft: 'auto', borderWidth: 1, borderColor: '#d5decd', borderRadius: 8, padding: 8, minWidth: 48, color: '#213c2b', fontSize: 13, textAlign: 'center'},
  consentRow: {flexDirection: 'row', alignItems: 'center'}, consent: {flex: 1, minHeight: 44, flexDirection: 'row', gap: 8, alignItems: 'center'},
  checkbox: {fontSize: 23, color: '#267957'}, consentText: {flex: 1, fontSize: 12, color: '#556452', lineHeight: 17},
  accountRow: {flexDirection: 'row', alignItems: 'center', gap: 8}, httpNote: {fontSize: 11, color: '#9c412f', lineHeight: 16},
});
