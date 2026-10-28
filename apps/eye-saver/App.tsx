import React, {useCallback, useEffect, useState} from 'react';
import {
  ActivityIndicator,
  AppState,
  BackHandler,
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
  getLaunchableApps,
  getRestrictedPackages,
  openAutoStartSettings,
  requestBatteryOptimizationExemption,
  requestDeviceAdmin,
  requestOverlayPermission,
  requestUsageAccess,
  setRestrictedPackages,
  setTamperGuardEnabled,
  showBreakScreen,
  startSelectiveGuard,
  stopSelectiveGuard,
  type DeviceGuardCapabilities,
  type LaunchableApp,
} from '@eye-saver/device-guard';
import {isFaceEnrolled} from '@eye-saver/face-verification-core';
import {
  getFaceDistanceCalibration,
  getFaceDistanceCapabilities,
  getFaceDistanceState,
  onFaceDistanceEvent,
  requestFaceDistanceCameraPermission,
  startFaceDistanceCalibration,
  startFaceDistanceMonitor,
  stopFaceDistanceMonitor,
  type FaceDistanceCalibration,
  type FaceDistanceCapabilities,
  type FaceDistanceSnapshot,
} from '@eye-saver/face-distance-monitor';
import {getParentAuthStatus, setParentPin, verifyParentPin} from '@eye-saver/parent-auth';
import {FaceProximity, useProximityAlert, type ProximityCapabilities} from '../../src';

type Screen = 'loading' | 'setup' | 'login' | 'dashboard' | 'child';
const DISTANCE_PROFILE = 'child-1';

const EMPTY_SESSION: ChildSessionSnapshot = {
  eventType: 'state', state: 'idle', sessionId: null, durationMs: 0,
  usedMs: 0, remainingMs: 0, startedAtMs: 0, timestampMs: 0, isScreenOn: true,
  blockOnExpire: false,
  verificationRequired: false, verificationCheckAtUsedMs: 0,
  verificationRequestedAtMs: 0, verificationCompletedAtMs: 0,
};
const EMPTY_GUARD: DeviceGuardCapabilities = {
  isAdminActive: false, isDeviceOwner: false, usageAccessGranted: false,
  canSuspendPackages: false, isLockTaskPermitted: false, canDrawOverlays: false,
  canWriteSystemGrayscale: false,
  tamperGuardEnabled: true, tamperGuardActive: false,
  batteryOptimizationExempt: false, autoStartSettingsAvailable: false, deviceManufacturer: 'Android',
};
const EMPTY_DISTANCE_CAPABILITIES: FaceDistanceCapabilities = {
  androidSupported: true, frontCameraAvailable: false, cameraPermissionGranted: false,
  overlayPermissionGranted: false, minimumSdk: 24, estimationModel: 'calibrated-inter-eye-ratio',
};
const EMPTY_DISTANCE_CALIBRATION: FaceDistanceCalibration = {
  calibrated: false, profileId: DISTANCE_PROFILE, knownDistanceCm: 0,
  referenceEyeDistancePx: 0, calibratedAtMs: 0,
};
const EMPTY_DISTANCE_STATE: FaceDistanceSnapshot = {
  eventType: 'state', mode: 'idle', active: false, profileId: null, distanceCm: null,
  thresholdCm: 25, clearThresholdCm: 30, tooClose: false, samplesCollected: 0,
  samplesRequired: 0, lastAlertAtMs: 0, timestampMs: 0,
};
const errorText = (error: unknown) => error instanceof Error ? error.message : String(error);
const clock = (timestamp: number) => timestamp > 0 ? new Date(timestamp).toLocaleTimeString([], {hour: '2-digit', minute: '2-digit'}) : '—';

async function enforceExpiredBreak(timestampMs: number) {
  const selectedPackages = await getRestrictedPackages();
  if (selectedPackages.length > 0) {
    await dismissBreakScreen();
    await startSelectiveGuard(1500);
  } else {
    await stopSelectiveGuard();
    await showBreakScreen({message: 'Time for an eye and screen break', triggeredAtMs: timestampMs});
  }
}

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
  const [launchableApps, setLaunchableApps] = useState<LaunchableApp[]>([]);
  const [restrictedPackages, setRestrictedPackageState] = useState<string[]>([]);
  const [distanceCapabilities, setDistanceCapabilities] = useState<FaceDistanceCapabilities>(EMPTY_DISTANCE_CAPABILITIES);
  const [distanceCalibration, setDistanceCalibration] = useState<FaceDistanceCalibration>(EMPTY_DISTANCE_CALIBRATION);
  const [distanceState, setDistanceState] = useState<FaceDistanceSnapshot>(EMPTY_DISTANCE_STATE);
  const [distanceEnabled, setDistanceEnabled] = useState(false);
  const [distanceThresholdCm, setDistanceThresholdCm] = useState(25);
  const [distanceIntervalMs, setDistanceIntervalMs] = useState(1500);
  const [, redrawClock] = useState(0);

  useProximityAlert({
    enabled: proximityEnabled,
    thresholdCm: proximity.maxRangeCm ?? 5,
    intervalMs: 1000,
    pauseInBackground: true,
    cooldownMs: 10_000,
  });

  const refreshCapabilities = useCallback(async () => {
    const [nextGuard, enrolled, proximityInfo, apps, selectedPackages, nextDistanceCapabilities, calibration, nextDistanceState] = await Promise.all([
      getDeviceGuardCapabilities(), isFaceEnrolled('child-1'), FaceProximity.getCapabilities(),
      getLaunchableApps(), getRestrictedPackages(), getFaceDistanceCapabilities(),
      getFaceDistanceCalibration(DISTANCE_PROFILE), getFaceDistanceState(),
    ]);
    setGuard(nextGuard);
    setFaceEnrolled(enrolled);
    setProximity(proximityInfo);
    setLaunchableApps(apps);
    setRestrictedPackageState(selectedPackages);
    setDistanceCapabilities(nextDistanceCapabilities);
    setDistanceCalibration(calibration);
    setDistanceState(nextDistanceState);
  }, []);

  const bootstrap = useCallback(async () => {
    try {
      const [auth, activeSession] = await Promise.all([getParentAuthStatus(), getChildSessionState()]);
      setSession(activeSession);
      await refreshCapabilities();
      if (activeSession.state === 'active' || activeSession.state === 'paused' || activeSession.state === 'expired') {
        setScreen('child');
        if (activeSession.state === 'expired') {
          await stopFaceDistanceMonitor();
          await enforceExpiredBreak(activeSession.timestampMs);
        } else {
          await stopSelectiveGuard();
          await dismissBreakScreen();
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
          stopFaceDistanceMonitor().catch(() => undefined);
          enforceExpiredBreak(event.timestampMs)
            .catch(error => setMessage(errorText(error)));
        }
        if (event.eventType === 'verification_required') {
          setMessage(`Random face check requested at ${clock(event.verificationRequestedAtMs)}.`);
        }
        if (event.eventType === 'stopped') {
          stopFaceDistanceMonitor().catch(() => undefined);
          setSession(EMPTY_SESSION);
          setPin('');
          setScreen('login');
        }
      });
    } catch (error) { setMessage(errorText(error)); }
    return unsubscribe;
  }, []);

  useEffect(() => {
    let unsubscribe: () => void = () => {};
    try {
      unsubscribe = onFaceDistanceEvent(event => {
        setDistanceState(event);
        if (event.eventType === 'calibration_progress') {
          setMessage(`Keep one face straight at 35 cm: ${event.samplesCollected}/${event.samplesRequired}.`);
        }
        if (event.eventType === 'calibration_complete') {
          setDistanceEnabled(true);
          setMessage('Face-distance calibration complete. Camera distance warning is ready.');
          refreshCapabilities().catch(() => undefined);
        }
        if (event.eventType === 'too_close') {
          setMessage(`Too close at ${clock(event.timestampMs)}: approximately ${Math.round(event.distanceCm ?? 0)} cm.`);
        }
        if (event.eventType === 'camera_error') setMessage(event.message ?? 'Front camera monitoring stopped.');
      });
    } catch (error) { setMessage(errorText(error)); }
    return unsubscribe;
  }, [refreshCapabilities]);

  useEffect(() => {
    const subscription = AppState.addEventListener('change', state => {
      if (state !== 'active' || screen === 'loading') return;
      Promise.all([refreshCapabilities(), getChildSessionState()])
        .then(([, restoredSession]) => {
          setSession(restoredSession);
          if (restoredSession.state === 'active' || restoredSession.state === 'paused' || restoredSession.state === 'expired') {
            setScreen('child');
          }
        })
        .catch(() => undefined);
    });
    return () => subscription.remove();
  }, [refreshCapabilities, screen]);

  useEffect(() => {
    if (screen !== 'child') return;
    const subscription = BackHandler.addEventListener('hardwareBackPress', () => {
      setMessage('Child mode continues in the background. Enter the parent PIN to end it.');
      return true;
    });
    return () => subscription.remove();
  }, [screen]);

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
  const setupReadyCount = [guard.canDrawOverlays, guard.usageAccessGranted, guard.isAdminActive, guard.batteryOptimizationExempt].filter(Boolean).length;

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
        await stopFaceDistanceMonitor();
        await stopSelectiveGuard();
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
      if (!guard.canDrawOverlays) {
        await requestOverlayPermission();
        setMessage('Enable "Display over other apps", return to Eye Saver, then press Start again. On Xiaomi, first tap EyeSaverApp in the list, then enable the switch.');
        return;
      }
      if ((restrictedPackages.length > 0 || guard.tamperGuardEnabled) && !guard.usageAccessGranted) {
        await requestUsageAccess();
        setMessage('Allow Usage access, return to Eye Saver, then press Start again. It lets Eye Saver protect Settings and detect parent-selected apps.');
        return;
      }
      if (distanceEnabled && !distanceCapabilities.cameraPermissionGranted) {
        const granted = await requestFaceDistanceCameraPermission();
        await refreshCapabilities();
        if (!granted) {
          setMessage('Camera permission is required for face-distance warnings.');
          return;
        }
      }
      if (distanceEnabled && !distanceCalibration.calibrated) {
        setMessage('Calibrate face distance at 35 cm before enabling camera distance warnings.');
        return;
      }
      await requestNotificationPermission();
      await stopFaceDistanceMonitor();
      await stopSelectiveGuard();
      await dismissBreakScreen();
      await startChildSession({
        durationMs: durationMinutes * 60_000,
        warningBeforeMs: Math.min(2 * 60_000, durationMinutes * 30_000),
        blockOnExpire: true,
      });
      if (distanceEnabled) {
        try {
          await startFaceDistanceMonitor({
            profileId: DISTANCE_PROFILE,
            thresholdCm: distanceThresholdCm,
            clearThresholdCm: distanceThresholdCm + 5,
            intervalMs: distanceIntervalMs,
            consecutiveNearSamples: 3,
            smoothingWindow: 5,
            showOverlay: true,
          });
        } catch (error) {
          await stopChildSession();
          throw error;
        }
      }
      const now = Date.now();
      setSession({...EMPTY_SESSION, state: 'active', durationMs: durationMinutes * 60_000, remainingMs: durationMinutes * 60_000, timestampMs: now, startedAtMs: now});
      setScreen('child');
    } catch (error) { setMessage(errorText(error)); }
    finally { setBusy(false); }
  }

  async function calibrateFaceDistance() {
    setBusy(true); setMessage('');
    try {
      let granted = distanceCapabilities.cameraPermissionGranted;
      if (!granted) granted = await requestFaceDistanceCameraPermission();
      await refreshCapabilities();
      if (!granted) {
        setMessage('Camera permission was not granted. Calibration cannot start.');
        return;
      }
      await startFaceDistanceCalibration({profileId: DISTANCE_PROFILE, knownDistanceCm: 35, sampleCount: 15});
      setMessage('Hold the phone 35 cm from one face, look straight at it, and keep still for a few seconds.');
    } catch (error) { setMessage(errorText(error)); }
    finally { setBusy(false); }
  }

  async function toggleRestrictedPackage(packageName: string) {
    const next = restrictedPackages.includes(packageName)
      ? restrictedPackages.filter(value => value !== packageName)
      : [...restrictedPackages, packageName];
    setRestrictedPackageState(next);
    try { await setRestrictedPackages(next); }
    catch (error) {
      setRestrictedPackageState(restrictedPackages);
      setMessage(errorText(error));
    }
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
        {distanceEnabled && distanceState.distanceCm != null && <Text style={styles.distanceReading}>Face distance: ~{Math.round(distanceState.distanceCm)} cm</Text>}
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

      <Card title={`Guided phone setup · ${setupReadyCount}/4 ready`}>
        <Text style={styles.setupIntro}>Tap each amber button. Eye Saver opens the exact Android page; enable the shown switch, then press Back. Its status turns green automatically.</Text>
        <StatusRow label="1. Display over other apps" ready={guard.canDrawOverlays} action="Open & allow" note="Turn on Allow display over other apps for Eye Saver" onPress={async () => { setMessage('Turn on the Eye Saver switch, then press Back.'); await requestOverlayPermission(); }} />
        <StatusRow label="2. Usage access" ready={guard.usageAccessGranted} action="Open & allow" note="Select Eye Saver and turn on Permit usage access" onPress={async () => { setMessage('Turn on Permit usage access for Eye Saver, then press Back.'); await requestUsageAccess(); }} />
        <StatusRow label="3. Device Admin" ready={guard.isAdminActive} action="Open & activate" note="Tap Activate this device admin on the next screen" onPress={async () => { setMessage('Tap Activate this device admin, then return to Eye Saver.'); await requestDeviceAdmin(); }} />
        <StatusRow label="4. Battery: No restrictions" ready={guard.batteryOptimizationExempt} action="Open & allow" note="Allow Eye Saver to keep child protection running in background" onPress={async () => { setMessage('Choose Allow/No restrictions for Eye Saver, then return.'); await requestBatteryOptimizationExemption(); }} />
        <ActionRow label={`5. ${guard.deviceManufacturer} AutoStart`} action="Open setting" note={guard.autoStartSettingsAvailable ? 'Turn on the Eye Saver switch, then press Back. Android does not expose this switch status to apps.' : 'Manufacturer page unavailable; Eye Saver app details will open instead.'} onPress={async () => { setMessage('Turn on AutoStart for Eye Saver, then press Back.'); await openAutoStartSettings(); }} />
        <Text style={styles.setupDone}>{setupReadyCount === 4 ? 'Required phone setup is complete.' : `${4 - setupReadyCount} required step${4 - setupReadyCount === 1 ? '' : 's'} remaining.`}</Text>
      </Card>

      <Card title="Protection status">
        <StatusRow label="Swipe-close recovery" ready note="Foreground child mode continues if Eye Saver is removed from Recents" />
        <StatusRow label="Reboot recovery receiver" ready note="Restores persisted child mode after a normal reboot; Xiaomi AutoStart/battery rules can delay it" />
        <StatusRow label="Dedicated Device Owner" ready={guard.isDeviceOwner} note="Required for strong package suspension on a dedicated child phone" />
        <StatusRow label="Child face enrolled" ready={faceEnrolled} note="Camera/model adapter is the next milestone" />
        <StatusRow label="Random mid-session check" ready={false} note={faceEnrolled ? 'Enrollment exists; camera checkpoint adapter is still required' : 'Activates after secure child-face enrollment'} />
      </Card>

      <Card title="Close and tamper protection">
        <View style={styles.switchRow}><View style={styles.flex}><Text style={styles.rowTitle}>Protect Settings and app installs</Text><Text style={styles.rowNote}>Blocks Settings, App info/Force Stop, package installers and app stores during child mode. Parent PIN ends the session.</Text></View><Switch value={guard.tamperGuardEnabled} onValueChange={async value => { try { await setTamperGuardEnabled(value); await refreshCapabilities(); } catch (error) { setMessage(errorText(error)); } }} trackColor={{true: '#2f8068'}} thumbColor="#d8fff2" /></View>
        <Text style={styles.rowNote}>Personal-phone protection is best effort. Android Force Stop cannot be disabled by a normal app; Device Owner mode is required for tamper-proof control.</Text>
      </Card>

      <Card title="Session length">
        <View style={styles.chips}>{[1, 20, 30, 45].map(value => <Pressable key={value} onPress={() => setDurationMinutes(value)} style={[styles.chip, durationMinutes === value && styles.chipActive]}><Text style={[styles.chipText, durationMinutes === value && styles.chipTextActive]}>{value === 1 ? '1 min test' : `${value} min`}</Text></Pressable>)}</View>
      </Card>

      <Card title="Apps blocked after screen time ends">
        <Text style={styles.rowNote}>These apps remain usable while the timer is running and are blocked only after it reaches zero. Leave Phone unselected so calls remain available. Tamper-proof Play Store blocking requires Device Owner.</Text>
        {launchableApps.map(app => <View key={app.packageName} style={styles.switchRow}>
          <View style={styles.flex}><Text style={styles.rowTitle}>{app.label}</Text><Text style={styles.rowNote}>{app.packageName}</Text></View>
          <Switch
            value={restrictedPackages.includes(app.packageName)}
            onValueChange={() => toggleRestrictedPackage(app.packageName)}
            trackColor={{true: '#2f8068'}}
            thumbColor="#d8fff2"
          />
        </View>)}
      </Card>

      <Card title="Eye-distance warning">
        <View style={styles.switchRow}><View style={styles.flex}><Text style={styles.rowTitle}>{proximity.available ? proximity.name ?? 'Proximity sensor' : 'Sensor unavailable'}</Text><Text style={styles.rowNote}>{proximity.isLikelyBinary ? 'Near/far warning; exact centimetres are unavailable' : 'Distance threshold warning while this app is visible'}</Text></View><Switch value={proximityEnabled} onValueChange={setProximityEnabled} disabled={!proximity.available} trackColor={{true: '#2f8068'}} thumbColor="#d8fff2" /></View>
      </Card>

      <Card title="Camera face-distance warning">
        <StatusRow
          label="Front camera permission"
          ready={distanceCapabilities.cameraPermissionGranted}
          action="Allow"
          note="Frames are processed on-device and are not saved"
          onPress={async () => { await requestFaceDistanceCameraPermission(); await refreshCapabilities(); }}
        />
        <StatusRow
          label="35 cm calibration"
          ready={distanceCalibration.calibrated}
          action="Required"
          note={distanceCalibration.calibrated ? `Calibrated at ${clock(distanceCalibration.calibratedAtMs)}` : 'Place one forward-facing face 35 cm from the phone'}
        />
        <PrimaryButton label={distanceState.mode === 'calibrating' ? `Calibrating ${distanceState.samplesCollected}/${distanceState.samplesRequired}` : 'Calibrate at 35 cm'} onPress={calibrateFaceDistance} disabled={busy || distanceState.mode === 'calibrating'} />
        <View style={styles.switchRow}><View style={styles.flex}><Text style={styles.rowTitle}>Enable during child mode</Text><Text style={styles.rowNote}>Android shows its camera privacy indicator and an ongoing notification</Text></View><Switch value={distanceEnabled} onValueChange={setDistanceEnabled} disabled={!distanceCalibration.calibrated} trackColor={{true: '#2f8068'}} thumbColor="#d8fff2" /></View>
        <Text style={styles.optionLabel}>Too-close threshold</Text>
        <View style={styles.chips}>{[20, 25, 30].map(value => <Pressable key={value} onPress={() => setDistanceThresholdCm(value)} style={[styles.chip, distanceThresholdCm === value && styles.chipActive]}><Text style={[styles.chipText, distanceThresholdCm === value && styles.chipTextActive]}>{value} cm</Text></Pressable>)}</View>
        <Text style={styles.optionLabel}>Analysis interval</Text>
        <View style={styles.chips}>{[1000, 1500, 3000].map(value => <Pressable key={value} onPress={() => setDistanceIntervalMs(value)} style={[styles.chip, distanceIntervalMs === value && styles.chipActive]}><Text style={[styles.chipText, distanceIntervalMs === value && styles.chipTextActive]}>{value / 1000}s</Text></Pressable>)}</View>
        <Text style={styles.rowNote}>Approximate only: head angle, camera crop and different faces affect the estimate. The warning requires three consecutive near readings and clears 5 cm beyond the selected threshold.</Text>
      </Card>

      <PrimaryButton label={`Start ${durationMinutes}-minute child session`} onPress={startSession} disabled={busy} />
      <Text style={styles.disclosure}>Child mode uses visible Android notifications, Usage access and explicitly granted overlays. Swipe-closing Eye Saver does not end its foreground protection. Camera distance monitoring is parent-started, shows Android's privacy indicator, and processes frames locally without saving them. Force Stop remains an Android system limitation unless this app is provisioned as Device Owner.</Text>
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
function ActionRow({label, action, note, onPress}: {label: string; action: string; note?: string; onPress: () => void | Promise<unknown>}) { return <View style={styles.statusRow}><View style={styles.flex}><Text style={styles.rowTitle}>{label}</Text>{note && <Text style={styles.rowNote}>{note}</Text>}</View><Pressable onPress={() => {Promise.resolve(onPress()).catch(error => console.warn(error));}} style={[styles.badge, styles.pending]}><Text style={styles.badgeText}>{action}</Text></Pressable></View>; }

const styles = StyleSheet.create({
  page: {flex: 1, backgroundColor: '#07120f'}, childPage: {flex: 1, backgroundColor: '#07120f'}, center: {flex: 1, backgroundColor: '#07120f', alignItems: 'center', justifyContent: 'center', gap: 16}, scroll: {padding: 22, paddingBottom: 48}, authWrap: {flex: 1, justifyContent: 'center', padding: 28}, childContent: {flex: 1, justifyContent: 'center', alignItems: 'stretch', padding: 28}, headerRow: {flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between'}, heading: {color: '#effff9', fontSize: 38, fontWeight: '800', letterSpacing: -1}, eyebrow: {color: '#5fe0b7', fontSize: 12, fontWeight: '800', letterSpacing: 2, marginBottom: 8}, subtitle: {color: '#9bb0a9', fontSize: 16, lineHeight: 23, marginTop: 8, marginBottom: 24}, muted: {color: '#9bb0a9', fontSize: 15, lineHeight: 22, textAlign: 'center'}, distanceReading: {color: '#5fe0b7', fontSize: 16, fontWeight: '700', textAlign: 'center', marginTop: 12}, lockLink: {color: '#5fe0b7', fontWeight: '700', padding: 12}, card: {backgroundColor: '#10211b', borderColor: '#1c392f', borderWidth: 1, borderRadius: 20, padding: 18, marginBottom: 16}, cardTitle: {color: '#effff9', fontSize: 18, fontWeight: '700', marginBottom: 12}, setupIntro: {color: '#b9cec7', fontSize: 13, lineHeight: 19, marginBottom: 6}, setupDone: {color: '#5fe0b7', fontSize: 13, fontWeight: '700', marginTop: 12}, statusRow: {flexDirection: 'row', alignItems: 'center', gap: 12, paddingVertical: 10, borderBottomColor: '#1b342c', borderBottomWidth: StyleSheet.hairlineWidth}, switchRow: {flexDirection: 'row', alignItems: 'center', gap: 12}, flex: {flex: 1}, rowTitle: {color: '#e2f5ee', fontSize: 15, fontWeight: '600'}, rowNote: {color: '#849a92', fontSize: 12, lineHeight: 17, marginTop: 3}, badge: {paddingHorizontal: 11, paddingVertical: 8, borderRadius: 999, maxWidth: 110}, ready: {backgroundColor: '#205c49'}, pending: {backgroundColor: '#725b19'}, badgeText: {color: '#eafff7', fontSize: 12, fontWeight: '700', textAlign: 'center'}, chips: {flexDirection: 'row', flexWrap: 'wrap', gap: 9}, chip: {borderColor: '#2a493e', borderWidth: 1, borderRadius: 12, paddingHorizontal: 14, paddingVertical: 10}, chipActive: {backgroundColor: '#5fe0b7', borderColor: '#5fe0b7'}, chipText: {color: '#b4c8c1', fontWeight: '700'}, chipTextActive: {color: '#07120f'}, optionLabel: {color: '#b9cec7', fontSize: 13, fontWeight: '700', marginTop: 18, marginBottom: 9}, primary: {backgroundColor: '#5fe0b7', minHeight: 54, borderRadius: 16, alignItems: 'center', justifyContent: 'center', paddingHorizontal: 18, marginTop: 12}, primaryDim: {opacity: 0.45}, primaryText: {color: '#07120f', fontSize: 16, fontWeight: '800'}, disclosure: {color: '#70867e', fontSize: 12, lineHeight: 18, textAlign: 'center', marginTop: 14}, pinInput: {backgroundColor: '#10211b', borderColor: '#2a493e', borderWidth: 1, borderRadius: 14, color: '#effff9', fontSize: 22, letterSpacing: 5, paddingHorizontal: 18, marginTop: 12, minHeight: 56}, error: {color: '#ff9b91', fontSize: 14, lineHeight: 20, marginTop: 14, textAlign: 'center'}, timer: {color: '#effff9', fontSize: 72, fontWeight: '200', textAlign: 'center', letterSpacing: -3, marginVertical: 18}, childTitle: {color: '#dff8ef', fontSize: 23, fontWeight: '700', textAlign: 'center', marginBottom: 8}, rule: {height: 1, backgroundColor: '#1c392f', marginVertical: 30}, label: {color: '#9bb0a9', fontSize: 13, fontWeight: '700'},
});
