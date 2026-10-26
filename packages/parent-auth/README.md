# @eye-saver/parent-auth

Native parent-PIN storage and verification for React Native Android. PINs are validated as 4–12 digits, derived with PBKDF2 and a random salt, then encrypted with an Android Keystore AES-GCM key. Five failed attempts trigger a 30-second native lockout.

This protects normal in-app parent actions. On an unmanaged shared phone, clearing app data also clears enrollment; strong tamper resistance requires Device Owner provisioning.
