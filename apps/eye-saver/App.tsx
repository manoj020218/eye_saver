import React, {useCallback, useEffect, useState} from 'react';
import {
  ActivityIndicator,
  AppState,
  Pressable,
  ScrollView,
  StatusBar,
  StyleSheet,
  Switch,
  Text,
  TextInput,
  View,
} from 'react-native';
import {SafeAreaProvider, SafeAreaView} from 'react-native-safe-area-context';
import {
  getChildSessionState,
  onChildSessionEvent,
  requestNotificationPermission,
  startChildSession,
  stopChildSession,
  type ChildSessionSnapshot,
} from '@eye-saver/child-session';
import {
  dismissBreakScreen,
  getDeviceGuardCapabilities,
  requestDeviceAdmin,
  requestUsageAccess,
  showBreakScreen,
  type DeviceGuardCapabilities,
} from '@eye-saver/device-guard';
import {isFaceEnrolled} from '@eye-saver/face-verification-core';
import {getParentAuthStatus, setParentPin, verifyParentPin} from '@eye-saver/parent-auth';
import {FaceProximity, useProximityAlert, type ProximityCapabilities} from 'react-native-face-proximity';

type Screen = 'loading' | 'setup' | 'login' | 'dashboard' | 'child';

const EMPTY_SESSION: ChildSessionSnapshot = {
  eventType: 'state', state: 'idle', sessionId: null, durationMs: 0,
  usedMs: 0, remainingMs: 0, startedAtMs: 0, timestampMs: 0, isScreenOn: true,
};
const EMPTY_GUARD: DeviceGuardCapabilities = {
  isAdminActive: false, isDeviceOwner: false, usageAccessGranted: false,
  canSuspendPackages: false, isLockTaskPermitted: false, canWriteSystemGrayscale: false,
};
const errorText = (error: unknown) => error instanceof Error ? error.message : String(error);
const clock = (timestamp: number) => timestamp > 0 ? new Date(timestamp).toLocaleTimeString([], {hour: '2-digit', minute: '2-digit'}) : '—';

function AppContent() {
  const [screen, setScreen] = useState<Screen>('loading');
  const [pin, setPin] = useState('');
  const [confirmPin, setConfirmPin] = useState('');
  const [message, setMessage] = useState('');
  const [busy, setBusy] = useState(false);
  const [durationMinutes, setDurationMinutes] = useState(20);
  const [session, setSession] = useState<ChildSessionSnapshot>(EMPTY_SESSION);
  const [guard, setGuard] = useState<DeviceGuardCapabilities>(EMPTY_GUARD);
  const [faceEnrolled, setFaceEnrolled] = useState(false);
  const [proximity, setProximity] = useState<ProximityCapabilities>({available: false});
  const [proximityEnabled, setProximityEnabled] = useState(false);
  const [, redrawClock] = useState(0);

  useProximityAlert({
    enabled: proximityEnabled,
    thresholdCm: proximity.maxRangeCm ?? 5,
    intervalMs: 1000,
    pauseInBackground: true,
    cooldownMs: 10_000,
  });

  const refreshCapabilities = useCallback(async () => {
    const [nextGuard, enrolled, proximityInfo] = await Promise.all([
      getDeviceGuardCapabilities(), isFaceEnrolled('child-1'), FaceProximity.getCapabilities(),
    ]);
    setGuard(nextGuard);
    setFaceEnrolled(enrolled);
    setProximity(proximityInfo);
  }, []);

  const bootstrap = useCallback(async () => {
    try {
      const [auth, activeSession] = await Promise.all([getParentAuthStatus(), getChildSessionState()]);
      setSession(activeSession);
      await refreshCapabilities();
      if (activeSession.state === 'active' || activeSession.state === 'paused' || activeSession.state === 'expired') {
        setScreen('child');
        if (activeSession.state === 'expired') {
          await showBreakScreen({message: 'Time for an eye and screen break', triggeredAtMs: activeSession.timestampMs});
        }
      } else setScreen(auth.configured ? 'login' : 'setup');
    } catch (error) {
      setMessage(errorText(error));
      setScreen('setup');
    }
  }, [refreshCapabilities]);

  useEffect(() => { bootstrap().catch(error => setMessage(errorText(error))); }, [bootstrap]);

  useEffect(() => {
    let unsubscribe: () => void = () => {};
    try {
      unsubscribe = onChildSessionEvent(event => {
        setSession(event);
        if (event.eventType === 'warning') setMessage(`Break starts soon. Warning at ${clock(event.timestampMs)}.`);
        if (event.eventType === 'expired') {
          setScreen('child');
          showBreakScreen({message: 'Time for an eye and screen break', triggeredAtMs: event.timestampMs})
            .catch(error => setMessage(errorText(error)));
        }
      });
    } catch (error) { setMessage(errorText(error)); }
    return unsubscribe;
  }, []);

  useEffect(() => {
    const subscription = AppState.addEventListener('change', state => {
      if (state === 'active' && screen !== 'loading') refreshCapabilities().catch(() => undefined);
    });
    return () => subscription.remove();
  }, [refreshCapabilities, screen]);

  useEffect(() => {
    if (screen !== 'child' || session.state !== 'active') return;
    const timer = setInterval(() => redrawClock(value => value + 1), 1000);
    return () => clearInterval(timer);
  }, [screen, session.state]);

  const remainingMs = (() => {
    if (session.state !== 'active' || !session.isScreenOn) return session.remainingMs;
    return Math.max(0, session.remainingMs - (Date.now() - session.timestampMs));
  })();
  const remainingLabel = `${Math.floor(remainingMs / 60_000).toString().padStart(2, '0')}:${Math.floor((remainingMs % 60_000) / 1000).toString().padStart(2, '0')}`;

  async function createPin() {
    if (pin !== confirmPin) { setMessage('PIN confirmation does not match.'); return; }
    setBusy(true); setMessage('');
    try { await setParentPin(pin); setPin(''); setConfirmPin(''); setScreen('dashboard'); }
    catch (error) { setMessage(errorText(error)); }
    finally { setBusy(false); }
  }

  async function unlockParent(target: 'dashboard' | 'stop') {
    setBusy(true); setMessage('');
    try {
      const result = await verifyParentPin(pin);
      if (!result.success) {
        setMessage(result.lockedUntilMs > Date.now()
          ? `Too many attempts. Try after ${clock(result.lockedUntilMs)}.`
          : `Incorrect PIN. ${result.remainingAttempts} attempts remaining.`);
        return;
      }
      setPin('');
      if (target === 'stop') {
        await stopChildSession();
        await dismissBreakScreen();
        setSession(EMPTY_SESSION);
      }
      setScreen('dashboard');
    } catch (error) { setMessage(errorText(error)); }
    finally { setBusy(false); }
  }

  async function startSession() {
    setBusy(true); setMessage('');
    try {
      await requestNotificationPermission();
      await startChildSession({
        durationMs: durationMinutes * 60_000,
        warningBeforeMs: Math.min(2 * 60_000, durationMinutes * 30_000),
      });
      const now = Date.now();
      setSession({...EMPTY_SESSION, state: 'active', durationMs: durationMinutes * 60_000, remainingMs: durationMinutes * 60_000, timestampMs: now, startedAtMs: now});
      setScreen('child');
    } catch (error) { setMessage(errorText(error)); }
    finally { setBusy(false); }
  }

  if (screen === 'loading') return <SafeAreaView style={styles.center}><ActivityIndicator size="large" color="#5fe0b7" /><Text style={styles.muted}>Preparing secure child mode…</Text></SafeAreaView>;

  if (screen === 'setup' || screen === 'login') {
    return <PinScreen
      setup={screen === 'setup'} pin={pin} confirmPin={confirmPin} busy={busy} message={message}
      onPin={setPin} onConfirm={setConfirmPin}
      onSubmit={screen === 'setup' ? createPin : () => unlockParent('dashboard')}
    />;
  }

  if (screen === 'child') {
    return <SafeAreaView style={styles.childPage}>
      <StatusBar barStyle="light-content" backgroundColor="#07120f" />
      <View style={styles.childContent}>
        <Text style={styles.eyebrow}>CHILD MODE</Text>
        <Text style={styles.timer}>{session.state === 'expired' ? 'BREAK' : remainingLabel}</Text>
        <Text style={styles.childTitle}>{session.state === 'expired' ? 'Time to rest your eyes' : 'Screen time remaining'}</Text>
        <Text style={styles.muted}>{session.isScreenOn ? 'Active screen time is being counted' : 'Screen is off — timer paused automatically'}</Text>
        <View style={styles.rule} />
        <Text style={styles.label}>Parent PIN to end child mode</Text>
        <TextInput value={pin} onChangeText={setPin} style={styles.pinInput} secureTextEntry keyboardType="number-pad" maxLength={12} placeholder="••••" placeholderTextColor="#53635d" />
        <PrimaryButton label="Unlock parent controls" onPress={() => unlockParent('stop')} disabled={busy || pin.length < 4} />
        {!!message && <Text style={styles.error}>{message}</Text>}
      </View>
    </SafeAreaView>;
  }

  return <SafeAreaView style={styles.page}>
    <StatusBar barStyle="light-content" backgroundColor="#07120f" />
    <ScrollView contentContainerStyle={styles.scroll}>
      <View style={styles.headerRow}><View><Text style={styles.eyebrow}>PARENT DASHBOARD</Text><Text style={styles.heading}>Eye Saver</Text></View><Pressable onPress={() => {setPin(''); setMessage(''); setScreen('login');}}><Text style={styles.lockLink}>Lock</Text></Pressable></View>
      <Text style={styles.subtitle}>Configure a safe session, then hand over the phone.</Text>

      <Card title="Protection readiness">
        <StatusRow label="Device Admin lock" ready={guard.isAdminActive} action="Enable" onPress={requestDeviceAdmin} />
        <StatusRow label="Usage access" ready={guard.usageAccessGranted} action="Allow" onPress={requestUsageAccess} />
        <StatusRow label="Dedicated Device Owner" ready={guard.isDeviceOwner} />
        <StatusRow label="Child face enrolled" ready={faceEnrolled} note="Camera/model adapter is the next milestone" />
      </Card>

      <Card title="Session length">
        <View style={styles.chips}>{[1, 20, 30, 45].map(value => <Pressable key={value} onPress={() => setDurationMinutes(value)} style={[styles.chip, durationMinutes === value && styles.chipActive]}><Text style={[styles.chipText, durationMinutes === value && styles.chipTextActive]}>{value === 1 ? '1 min test' : `${value} min`}</Text></Pressable>)}</View>
      </Card>

      <Card title="Eye-distance warning">
        <View style={styles.switchRow}><View style={styles.flex}><Text style={styles.rowTitle}>{proximity.available ? proximity.name ?? 'Proximity sensor' : 'Sensor unavailable'}</Text><Text style={styles.rowNote}>{proximity.isLikelyBinary ? 'Near/far warning; exact centimetres are unavailable' : 'Distance threshold warning while this app is visible'}</Text></View><Switch value={proximityEnabled} onValueChange={setProximityEnabled} disabled={!proximity.available} trackColor={{true: '#2f8068'}} thumbColor="#d8fff2" /></View>
      </Card>

      <PrimaryButton label={`Start ${durationMinutes}-minute child session`} onPress={startSession} disabled={busy} />
      <Text style={styles.disclosure}>Child mode uses a visible Android notification. Camera verification will never run secretly in the background.</Text>
      {!!message && <Text style={styles.error}>{message}</Text>}
    </ScrollView>
  </SafeAreaView>;
}

export default function App() {
  return <SafeAreaProvider><AppContent /></SafeAreaProvider>;
}

function PinScreen(props: {setup: boolean; pin: string; confirmPin: string; busy: boolean; message: string; onPin: (value: string) => void; onConfirm: (value: string) => void; onSubmit: () => void}) {
  return <SafeAreaView style={styles.page}><StatusBar barStyle="light-content" backgroundColor="#07120f" /><View style={styles.authWrap}>
    <Text style={styles.eyebrow}>{props.setup ? 'FIRST-TIME SETUP' : 'PARENT ACCESS'}</Text>
    <Text style={styles.heading}>{props.setup ? 'Create parent PIN' : 'Welcome back'}</Text>
    <Text style={styles.subtitle}>{props.setup ? 'Use 4–12 digits. Five failed attempts trigger a native lockout.' : 'Enter the PIN to change child-mode settings.'}</Text>
    <TextInput value={props.pin} onChangeText={props.onPin} style={styles.pinInput} secureTextEntry keyboardType="number-pad" maxLength={12} placeholder="Parent PIN" placeholderTextColor="#53635d" />
    {props.setup && <TextInput value={props.confirmPin} onChangeText={props.onConfirm} style={styles.pinInput} secureTextEntry keyboardType="number-pad" maxLength={12} placeholder="Confirm PIN" placeholderTextColor="#53635d" />}
    <PrimaryButton label={props.setup ? 'Secure parent controls' : 'Unlock dashboard'} onPress={props.onSubmit} disabled={props.busy || props.pin.length < 4 || (props.setup && props.confirmPin.length < 4)} />
    {!!props.message && <Text style={styles.error}>{props.message}</Text>}
  </View></SafeAreaView>;
}

function Card({title, children}: React.PropsWithChildren<{title: string}>) { return <View style={styles.card}><Text style={styles.cardTitle}>{title}</Text>{children}</View>; }
function PrimaryButton({label, onPress, disabled}: {label: string; onPress: () => void; disabled?: boolean}) { return <Pressable accessibilityRole="button" onPress={onPress} disabled={disabled} style={({pressed}) => [styles.primary, (pressed || disabled) && styles.primaryDim]}><Text style={styles.primaryText}>{label}</Text></Pressable>; }
function StatusRow({label, ready, action, note, onPress}: {label: string; ready: boolean; action?: string; note?: string; onPress?: () => void | Promise<unknown>}) { return <View style={styles.statusRow}><View style={styles.flex}><Text style={styles.rowTitle}>{label}</Text>{note && <Text style={styles.rowNote}>{note}</Text>}</View><Pressable disabled={!onPress || ready} onPress={() => {Promise.resolve(onPress?.()).catch(error => console.warn(error));}} style={[styles.badge, ready ? styles.ready : styles.pending]}><Text style={styles.badgeText}>{ready ? 'Ready' : action ?? 'Optional'}</Text></Pressable></View>; }

const styles = StyleSheet.create({
  page: {flex: 1, backgroundColor: '#07120f'}, childPage: {flex: 1, backgroundColor: '#07120f'}, center: {flex: 1, backgroundColor: '#07120f', alignItems: 'center', justifyContent: 'center', gap: 16}, scroll: {padding: 22, paddingBottom: 48}, authWrap: {flex: 1, justifyContent: 'center', padding: 28}, childContent: {flex: 1, justifyContent: 'center', alignItems: 'stretch', padding: 28}, headerRow: {flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between'}, heading: {color: '#effff9', fontSize: 38, fontWeight: '800', letterSpacing: -1}, eyebrow: {color: '#5fe0b7', fontSize: 12, fontWeight: '800', letterSpacing: 2, marginBottom: 8}, subtitle: {color: '#9bb0a9', fontSize: 16, lineHeight: 23, marginTop: 8, marginBottom: 24}, muted: {color: '#9bb0a9', fontSize: 15, lineHeight: 22, textAlign: 'center'}, lockLink: {color: '#5fe0b7', fontWeight: '700', padding: 12}, card: {backgroundColor: '#10211b', borderColor: '#1c392f', borderWidth: 1, borderRadius: 20, padding: 18, marginBottom: 16}, cardTitle: {color: '#effff9', fontSize: 18, fontWeight: '700', marginBottom: 12}, statusRow: {flexDirection: 'row', alignItems: 'center', gap: 12, paddingVertical: 10, borderBottomColor: '#1b342c', borderBottomWidth: StyleSheet.hairlineWidth}, switchRow: {flexDirection: 'row', alignItems: 'center', gap: 12}, flex: {flex: 1}, rowTitle: {color: '#e2f5ee', fontSize: 15, fontWeight: '600'}, rowNote: {color: '#849a92', fontSize: 12, lineHeight: 17, marginTop: 3}, badge: {paddingHorizontal: 11, paddingVertical: 6, borderRadius: 999}, ready: {backgroundColor: '#205c49'}, pending: {backgroundColor: '#3b3420'}, badgeText: {color: '#eafff7', fontSize: 12, fontWeight: '700'}, chips: {flexDirection: 'row', flexWrap: 'wrap', gap: 9}, chip: {borderColor: '#2a493e', borderWidth: 1, borderRadius: 12, paddingHorizontal: 14, paddingVertical: 10}, chipActive: {backgroundColor: '#5fe0b7', borderColor: '#5fe0b7'}, chipText: {color: '#b4c8c1', fontWeight: '700'}, chipTextActive: {color: '#07120f'}, primary: {backgroundColor: '#5fe0b7', minHeight: 54, borderRadius: 16, alignItems: 'center', justifyContent: 'center', paddingHorizontal: 18, marginTop: 12}, primaryDim: {opacity: 0.45}, primaryText: {color: '#07120f', fontSize: 16, fontWeight: '800'}, disclosure: {color: '#70867e', fontSize: 12, lineHeight: 18, textAlign: 'center', marginTop: 14}, pinInput: {backgroundColor: '#10211b', borderColor: '#2a493e', borderWidth: 1, borderRadius: 14, color: '#effff9', fontSize: 22, letterSpacing: 5, paddingHorizontal: 18, marginTop: 12, minHeight: 56}, error: {color: '#ff9b91', fontSize: 14, lineHeight: 20, marginTop: 14, textAlign: 'center'}, timer: {color: '#effff9', fontSize: 72, fontWeight: '200', textAlign: 'center', letterSpacing: -3, marginVertical: 18}, childTitle: {color: '#dff8ef', fontSize: 23, fontWeight: '700', textAlign: 'center', marginBottom: 8}, rule: {height: 1, backgroundColor: '#1c392f', marginVertical: 30}, label: {color: '#9bb0a9', fontSize: 13, fontWeight: '700'},
});
