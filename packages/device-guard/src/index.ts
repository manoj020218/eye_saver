import {NativeModules} from 'react-native';

export interface DeviceGuardCapabilities {
  isAdminActive: boolean;
  isDeviceOwner: boolean;
  usageAccessGranted: boolean;
  canSuspendPackages: boolean;
  isLockTaskPermitted: boolean;
  canDrawOverlays: boolean;
  canWriteSystemGrayscale: false;
  tamperGuardEnabled: boolean;
  tamperGuardActive: boolean;
  batteryOptimizationExempt: boolean;
  autoStartSettingsAvailable: boolean;
  autoStartConfirmed: boolean;
  deviceManufacturer: string;
}
export interface BreakScreenOptions { message?: string; triggeredAtMs?: number }
export interface LaunchableApp { packageName: string; label: string }
const Native = NativeModules.EyeSaverDeviceGuard;
const module = () => {
  if (!Native) throw new Error('@eye-saver/device-guard is not linked. Rebuild the Android app.');
  return Native;
};

export const getDeviceGuardCapabilities = (): Promise<DeviceGuardCapabilities> => module().getCapabilities();
export const requestDeviceAdmin = (): Promise<boolean> => module().requestAdmin();
export const requestUsageAccess = (): Promise<boolean> => module().requestUsageAccess();
export const requestOverlayPermission = (): Promise<boolean> => module().requestOverlayPermission();
export const requestBatteryOptimizationExemption = (): Promise<boolean> => module().requestBatteryOptimizationExemption();
export const openAutoStartSettings = (): Promise<boolean> => module().openAutoStartSettings();
export const setAutoStartConfirmed = (confirmed: boolean): Promise<boolean> => module().setAutoStartConfirmed(confirmed);
export const setTamperGuardEnabled = (enabled: boolean): Promise<boolean> => module().setTamperGuardEnabled(enabled);
export const lockDevice = (): Promise<boolean> => module().lockDevice();
export const setPackagesSuspended = (packageNames: string[], suspended: boolean): Promise<string[]> => module().setPackagesSuspended(packageNames, suspended);
export const startLockTask = (): Promise<boolean> => module().startLockTask();
export const stopLockTask = (): Promise<boolean> => module().stopLockTask();
export const showBreakScreen = (options: BreakScreenOptions = {}): Promise<boolean> => module().showBreakScreen(options);
export const dismissBreakScreen = (): Promise<boolean> => module().dismissBreakScreen();
export const getLaunchableApps = (): Promise<LaunchableApp[]> => module().getLaunchableApps();
export const getRestrictedPackages = (): Promise<string[]> => module().getRestrictedPackages();
export const setRestrictedPackages = (packageNames: string[]): Promise<boolean> => module().setRestrictedPackages(packageNames);
export const startSelectiveGuard = (intervalMs = 1500): Promise<boolean> => module().startSelectiveGuard(intervalMs);
export const stopSelectiveGuard = (): Promise<boolean> => module().stopSelectiveGuard();
