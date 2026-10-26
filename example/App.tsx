import React from 'react';
import {SafeAreaView, Text} from 'react-native';
// In a consuming app, import this from 'react-native-face-proximity'.
import {useProximityAlert} from '../src';

export default function App() {
  useProximityAlert({
    thresholdCm: 5,
    intervalMs: 1_000,
    pauseInBackground: true,
    onTooClose: event => {
      // Navigate to your consent-based camera screen here if desired.
      console.log('Near-face trigger', event.timestampMs);
    },
  });

  return <SafeAreaView><Text>Move the phone near your face to test.</Text></SafeAreaView>;
}
