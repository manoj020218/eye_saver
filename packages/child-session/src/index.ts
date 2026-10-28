import {NativeEventEmitter, NativeModules, PermissionsAndroid, Platform} from 'react-native';

export type ChildSessionState = 'idle' | 'active' | 'paused' | 'expired';
export type ChildSessionEventType = 'state' | 'started' | 'restored' | 'warning' | 'expired' | 'paused' | 'resumed' | 'stopped' | 'screen_on' | 'screen_off' | 'verification_required' | 'verification_passed' | 'verification_failed';

export interface ChildSessionSnapshot {
  eventType: ChildSessionEventType;
  state: ChildSessionState;
  sessionId: string | null;
  durationMs: number;
  usedMs: number;
  remainingMs: number;
  startedAtMs: number;
  timestampMs: number;
  isScreenOn: boolean;
  blockOnExpire: boolean;
  verificationRequired: boolean;
  verificationCheckAtUsedMs: number;
  verificationRequestedAtMs: number;
  verificationCompletedAtMs: number;
}

export interface StartChildSessionOptions {
  durationMs?: number;
  warningBeforeMs?: number;
  blockOnExpire?: boolean;
  /** Earliest active screen-time point at which the one-time random check can occur. */
  verificationMinUsedMs?: number;
  /** Latest active screen-time point at which the one-time random check can occur. */
  verificationMaxUsedMs?: number;
}

const Native = NativeModules.EyeSaverChildSession;
const requireNative = () => {
  if (!Native) throw new Error('@eye-saver/child-session is not linked. Rebuild the Android app.');
  return Native;
};

export async function requestNotificationPermission(): Promise<boolean> {
  if (Platform.OS !== 'android' || Number(Platform.Version) < 33) return true;
  return (await PermissionsAndroid.request(PermissionsAndroid.PERMISSIONS.POST_NOTIFICATIONS)) === PermissionsAndroid.RESULTS.GRANTED;
}

export const startChildSession = (options: StartChildSessionOptions = {}): Promise<boolean> =>
  requireNative().start({
    durationMs: options.durationMs ?? 20 * 60_000,
    warningBeforeMs: options.warningBeforeMs ?? 2 * 60_000,
    blockOnExpire: options.blockOnExpire ?? false,
    verificationMinUsedMs: options.verificationMinUsedMs ?? 0,
    verificationMaxUsedMs: options.verificationMaxUsedMs ?? 0,
  });
export const pauseChildSession = (): Promise<boolean> => requireNative().pause();
export const resumeChildSession = (): Promise<boolean> => requireNative().resume();
export const stopChildSession = (): Promise<boolean> => requireNative().stop();
export const completeChildVerification = (success: boolean): Promise<boolean> => requireNative().completeVerification(success);
export const getChildSessionState = (): Promise<ChildSessionSnapshot> => requireNative().getState();
export const getChildSessionCapabilities = (): Promise<{foregroundServiceSupported: boolean; notificationsGranted: boolean; timingModel: string}> => requireNative().getCapabilities();

export function onChildSessionEvent(listener: (event: ChildSessionSnapshot) => void): () => void {
  const subscription = new NativeEventEmitter(requireNative()).addListener('childSessionEvent', listener);
  return () => subscription.remove();
}
