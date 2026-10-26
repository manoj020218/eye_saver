import {NativeEventEmitter, NativeModules, Platform} from 'react-native';

const LINKING_ERROR = `The package 'react-native-face-proximity' is not linked. ${
  Platform.OS === 'android'
    ? 'Rebuild the Android app after installing the package.'
    : 'This package currently supports Android only.'
}`;

type NativeFaceProximity = {
  getCapabilities(): Promise<ProximityCapabilities>;
  start(options: Required<ProximityOptions>): Promise<MonitoringState>;
  stop(): Promise<void>;
  getLatestMeasurement(): Promise<ProximityMeasurement | null>;
  addListener(eventName: string): void;
  removeListeners(count: number): void;
};

const NativeModule = NativeModules.FaceProximity as NativeFaceProximity | undefined;

function requireNativeModule(): NativeFaceProximity {
  if (Platform.OS !== 'android' || !NativeModule) throw new Error(LINKING_ERROR);
  return NativeModule;
}

let emitter: NativeEventEmitter | undefined;
function eventEmitter(): NativeEventEmitter {
  emitter ??= new NativeEventEmitter(requireNativeModule() as never);
  return emitter;
}

export type ProximityOptions = {
  /** Near threshold in centimetres. Binary sensors effectively support near/far only. */
  thresholdCm?: number;
  /** Minimum JS measurement-event interval. Range: 100-60000 ms. */
  intervalMs?: number;
  /** Extra exit distance that prevents state chatter near the threshold. */
  hysteresisCm?: number;
  /** Unregister when the React activity is backgrounded. Recommended for battery life. */
  pauseInBackground?: boolean;
};

export type MonitoringState = Required<Pick<ProximityOptions,
  'thresholdCm' | 'intervalMs' | 'pauseInBackground'>> & {
  monitoring: boolean;
  isLikelyBinary: boolean;
};

export type ProximityCapabilities = {
  available: boolean;
  maxRangeCm?: number;
  resolutionCm?: number;
  powerMilliAmp?: number;
  name?: string;
  vendor?: string;
  version?: number;
  isWakeUpSensor?: boolean;
  reportingMode?: 'continuous' | 'on-change' | 'one-shot' | 'special-trigger' | 'unknown';
  isLikelyBinary?: boolean;
};

export type ProximityMeasurement = {
  distanceCm: number;
  isTooClose: boolean;
  thresholdCm: number;
  /** Unix wall-clock time in milliseconds. */
  timestampMs: number;
  accuracy: number;
};

export type ProximityThresholdEvent = ProximityMeasurement & {
  transition: 'entered' | 'exited';
};

const DEFAULTS: Required<ProximityOptions> = {
  thresholdCm: 5,
  intervalMs: 500,
  hysteresisCm: 0.5,
  pauseInBackground: true,
};

export const FaceProximity = {
  getCapabilities: (): Promise<ProximityCapabilities> => requireNativeModule().getCapabilities(),
  start(options: ProximityOptions = {}): Promise<MonitoringState> {
    const normalized: Required<ProximityOptions> = {
      thresholdCm: options.thresholdCm ?? DEFAULTS.thresholdCm,
      intervalMs: options.intervalMs ?? DEFAULTS.intervalMs,
      hysteresisCm: options.hysteresisCm ?? DEFAULTS.hysteresisCm,
      pauseInBackground: options.pauseInBackground ?? DEFAULTS.pauseInBackground,
    };
    return requireNativeModule().start(normalized);
  },
  stop: (): Promise<void> => requireNativeModule().stop(),
  getLatestMeasurement: (): Promise<ProximityMeasurement | null> =>
    requireNativeModule().getLatestMeasurement(),
  onMeasurement(listener: (event: ProximityMeasurement) => void) {
    return eventEmitter().addListener('faceProximityMeasurement', listener);
  },
  onThresholdChange(listener: (event: ProximityThresholdEvent) => void) {
    return eventEmitter().addListener('faceProximityThreshold', listener);
  },
};

export function formatProximityTime(timestampMs: number, locales?: string | string[]): string {
  return new Date(timestampMs).toLocaleTimeString(locales, {
    hour: '2-digit', minute: '2-digit', second: '2-digit',
  });
}

export {useProximityAlert} from './useProximityAlert';
export type {ProximityAlertOptions} from './useProximityAlert';
