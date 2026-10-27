import {NativeModules} from 'react-native';

export interface DeviceGuardCapabilities {
  isAdminActive: boolean;
  isDeviceOwner: boolean;
  usageAccessGranted: boolean;
  canSuspendPackages: boolean;
  isLockTaskPermitted: boolean;
  canDrawOverlays: boolean;
  canWriteSystemGrayscale: false;
}
export interface BreakScreenOptions { message?: string; triggeredAtMs?: number }
const Native = NativeModules.EyeSaverDeviceGuard;
const module = () => {
  if (!Native) throw new Error('@eye-saver/device-guard is not linked. Rebuild the Android app.');
  return Native;
};

export const getDeviceGuardCapabilities = (): Promise<DeviceGuardCapabilities> => module().getCapabilities();
export const requestDeviceAdmin = (): Promise<boolean> => module().requestAdmin();
export const requestUsageAccess = (): Promise<boolean> => module().requestUsageAccess();
export const requestOverlayPermission = (): Promise<boolean> => module().requestOverlayPermission();
export const lockDevice = (): Promise<boolean> => module().lockDevice();
export const setPackagesSuspended = (packageNames: string[], suspended: boolean): Promise<string[]> => module().setPackagesSuspended(packageNames, suspended);
export const startLockTask = (): Promise<boolean> => module().startLockTask();
export const stopLockTask = (): Promise<boolean> => module().stopLockTask();
export const showBreakScreen = (options: BreakScreenOptions = {}): Promise<boolean> => module().showBreakScreen(options);
export const dismissBreakScreen = (): Promise<boolean> => module().dismissBreakScreen();
