import {NativeModules} from 'react-native';

export interface EnrollmentResult { dimension: number; enrolledAtMs: number }
export interface VerificationResult { match: boolean; similarity: number; threshold: number; dimension: number; timestampMs: number }
const Native = NativeModules.EyeSaverFaceVerification;
const module = () => {
  if (!Native) throw new Error('@eye-saver/face-verification-core is not linked. Rebuild the Android app.');
  return Native;
};

export const enrollFaceTemplate = (subjectId: string, embedding: number[]): Promise<EnrollmentResult> => module().enroll(subjectId, embedding);
export const verifyFaceTemplate = (subjectId: string, embedding: number[], threshold = 0.78): Promise<VerificationResult> => module().verify(subjectId, embedding, threshold);
export const isFaceEnrolled = (subjectId: string): Promise<boolean> => module().isEnrolled(subjectId);
export const removeFaceTemplate = (subjectId: string): Promise<boolean> => module().remove(subjectId);
export const clearFaceTemplates = (): Promise<boolean> => module().clearAll();
