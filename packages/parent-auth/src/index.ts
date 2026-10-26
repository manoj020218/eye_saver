import {NativeModules} from 'react-native';

export interface ParentAuthStatus { configured: boolean; lockedUntilMs: number; maxAttempts: number }
export interface ParentAuthResult { success: boolean; remainingAttempts: number; lockedUntilMs: number; timestampMs: number }
const Native = NativeModules.EyeSaverParentAuth;
const module = () => {
  if (!Native) throw new Error('@eye-saver/parent-auth is not linked. Rebuild the Android app.');
  return Native;
};
export const setParentPin = (pin: string): Promise<boolean> => module().setPin(pin);
export const verifyParentPin = (pin: string): Promise<ParentAuthResult> => module().verifyPin(pin);
export const getParentAuthStatus = (): Promise<ParentAuthStatus> => module().getStatus();
export const clearParentPin = (): Promise<boolean> => module().clear();
