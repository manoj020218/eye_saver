import {NativeEventEmitter, NativeModules, PermissionsAndroid, Platform} from 'react-native';

export type FaceDistanceMode = 'idle' | 'calibrating' | 'monitoring';
export type FaceDistanceEventType =
  | 'state'
  | 'calibration_progress'
  | 'calibration_complete'
  | 'distance'
  | 'too_close'
  | 'cleared'
  | 'no_face'
  | 'camera_error'
  | 'stopped';

export interface FaceDistanceCapabilities {
  androidSupported: boolean;
  frontCameraAvailable: boolean;
  cameraPermissionGranted: boolean;
  overlayPermissionGranted: boolean;
  minimumSdk: number;
  estimationModel: 'calibrated-inter-eye-ratio';
}

export interface FaceDistanceCalibration {
  calibrated: boolean;
  profileId: string;
  knownDistanceCm: number;
  referenceEyeDistancePx: number;
  calibratedAtMs: number;
}

export interface FaceDistanceSnapshot {
  eventType: FaceDistanceEventType;
  mode: FaceDistanceMode;
  active: boolean;
  profileId: string | null;
  distanceCm: number | null;
  thresholdCm: number;
  clearThresholdCm: number;
  tooClose: boolean;
  samplesCollected: number;
  samplesRequired: number;
  lastAlertAtMs: number;
  timestampMs: number;
  message?: string;
}

export interface CalibrationOptions {
  profileId: string;
  knownDistanceCm?: number;
  sampleCount?: number;
}

export interface FaceDistanceMonitorOptions {
  profileId: string;
  thresholdCm?: number;
  clearThresholdCm?: number;
  intervalMs?: number;
  consecutiveNearSamples?: number;
  smoothingWindow?: number;
  showOverlay?: boolean;
}

const Native = NativeModules.EyeSaverFaceDistance;
const module = () => {
  if (!Native) throw new Error('@eye-saver/face-distance-monitor is not linked. Rebuild the Android app.');
  return Native;
};

export async function requestFaceDistanceCameraPermission(): Promise<boolean> {
  if (Platform.OS !== 'android') return false;
  return (await PermissionsAndroid.request(
    PermissionsAndroid.PERMISSIONS.CAMERA,
    {
      title: 'Eye Saver camera access',
      message: 'Eye Saver uses the front camera during parent-started child mode to estimate viewing distance. Frames stay on this device and are not saved.',
      buttonPositive: 'Allow',
      buttonNegative: 'Cancel',
    },
  )) === PermissionsAndroid.RESULTS.GRANTED;
}

export const getFaceDistanceCapabilities = (): Promise<FaceDistanceCapabilities> => module().getCapabilities();
export const getFaceDistanceState = (): Promise<FaceDistanceSnapshot> => module().getState();
export const getFaceDistanceCalibration = (profileId: string): Promise<FaceDistanceCalibration> => module().getCalibration(profileId);
export const startFaceDistanceCalibration = (options: CalibrationOptions): Promise<boolean> => module().startCalibration({
  profileId: options.profileId,
  knownDistanceCm: options.knownDistanceCm ?? 100,
  sampleCount: options.sampleCount ?? 4,
});
export const clearFaceDistanceCalibration = (profileId: string): Promise<boolean> => module().clearCalibration(profileId);
export const startFaceDistanceMonitor = (options: FaceDistanceMonitorOptions): Promise<boolean> => module().startMonitoring({
  profileId: options.profileId,
  thresholdCm: options.thresholdCm ?? 25,
  clearThresholdCm: options.clearThresholdCm ?? (options.thresholdCm ?? 25) + 5,
  intervalMs: options.intervalMs ?? 1500,
  consecutiveNearSamples: options.consecutiveNearSamples ?? 3,
  smoothingWindow: options.smoothingWindow ?? 5,
  showOverlay: options.showOverlay ?? true,
});
export const stopFaceDistanceMonitor = (): Promise<boolean> => module().stop();

export function onFaceDistanceEvent(listener: (event: FaceDistanceSnapshot) => void): () => void {
  const subscription = new NativeEventEmitter(module()).addListener('faceDistanceEvent', listener);
  return () => subscription.remove();
}
