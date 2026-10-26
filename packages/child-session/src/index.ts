import {NativeEventEmitter, NativeModules, PermissionsAndroid, Platform} from 'react-native';

export type ChildSessionState = 'idle' | 'active' | 'paused' | 'expired';
export type ChildSessionEventType = 'state' | 'started' | 'warning' | 'expired' | 'paused' | 'resumed' | 'stopped' | 'screen_on' | 'screen_off';

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
}

export interface StartChildSessionOptions { durationMs?: number; warningBeforeMs?: number }

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
  requireNative().start({durationMs: options.durationMs ?? 20 * 60_000, warningBeforeMs: options.warningBeforeMs ?? 2 * 60_000});
export const pauseChildSession = (): Promise<boolean> => requireNative().pause();
export const resumeChildSession = (): Promise<boolean> => requireNative().resume();
export const stopChildSession = (): Promise<boolean> => requireNative().stop();
export const getChildSessionState = (): Promise<ChildSessionSnapshot> => requireNative().getState();
export const getChildSessionCapabilities = (): Promise<{foregroundServiceSupported: boolean; notificationsGranted: boolean; timingModel: string}> => requireNative().getCapabilities();

export function onChildSessionEvent(listener: (event: ChildSessionSnapshot) => void): () => void {
  const subscription = new NativeEventEmitter(requireNative()).addListener('childSessionEvent', listener);
  return () => subscription.remove();
}
