import {useEffect, useRef} from 'react';
import {Alert} from 'react-native';
import {
  FaceProximity,
  formatProximityTime,
  type ProximityOptions,
  type ProximityThresholdEvent,
} from './index';

export type ProximityAlertOptions = ProximityOptions & {
  enabled?: boolean;
  title?: string;
  cooldownMs?: number;
  onTooClose?: (event: ProximityThresholdEvent) => void;
};

/** Mount once near the app root so the warning can appear over the current view. */
export function useProximityAlert(options: ProximityAlertOptions = {}): void {
  const callbackRef = useRef(options.onTooClose);
  callbackRef.current = options.onTooClose;

  const {
    enabled = true,
    title = 'Screen is too close to your face',
    cooldownMs = 5_000,
    thresholdCm,
    intervalMs,
    hysteresisCm,
    pauseInBackground,
  } = options;

  useEffect(() => {
    if (!enabled) return;
    let active = true;
    let lastAlertAt = 0;

    const subscription = FaceProximity.onThresholdChange(event => {
      if (!active || event.transition !== 'entered') return;
      if (event.timestampMs - lastAlertAt < cooldownMs) return;
      lastAlertAt = event.timestampMs;
      callbackRef.current?.(event);
      Alert.alert(
        title,
        `Too close at ${formatProximityTime(event.timestampMs)} (${event.distanceCm} cm)`,
      );
    });

    FaceProximity.start({thresholdCm, intervalMs, hysteresisCm, pauseInBackground})
      .catch(error => {
        if (active) console.warn('[FaceProximity] Could not start', error);
      });

    return () => {
      active = false;
      subscription.remove();
      FaceProximity.stop().catch(() => undefined);
    };
  }, [enabled, title, cooldownMs, thresholdCm, intervalMs, hysteresisCm, pauseInBackground]);
}
