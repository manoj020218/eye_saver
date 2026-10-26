# @eye-saver/face-verification-core

Stores normalized face embeddings encrypted with an Android Keystore AES-GCM key and performs cosine-similarity checks entirely on the device. Subject IDs are hashed and raw camera photos are never accepted or stored.

This is deliberately model-independent. A CameraX/TFLite adapter in the final app must detect, align and convert a visible one-time face frame into an embedding. Enrollment and verification must use the exact same model and embedding dimension. ML Kit face detection by itself does not identify a person.

```ts
await enrollFaceTemplate('child-1', enrollmentEmbedding);
const result = await verifyFaceTemplate('child-1', currentEmbedding, 0.78);
```

Tune the threshold against the selected model and real validation data. Do not treat face matching as the only parent-authentication factor; use a parent PIN or device credential for overrides.
