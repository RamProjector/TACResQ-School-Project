#!/usr/bin/env python3
"""Static guardrails for encrypted Android session persistence.

These checks complement, but do not replace, Android Keystore instrumentation tests.
"""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
store = (ROOT / "app/src/main/java/com/tacresq/app/data/local/SessionStore.kt").read_text()

checks = {
    "session encryption uses Android Keystore": 'KeyStore.getInstance("AndroidKeyStore")' in store,
    "session encryption uses AES-GCM": '"AES/GCM/NoPadding"' in store and "GCMParameterSpec" in store,
    "encryption generates a fresh IV": "cipher.iv + cipherText" in store and "setRandomizedEncryptionRequired(true)" in store,
    "persisted session is stored as one encrypted value": 'stringPreferencesKey("encrypted_session_v1")' in store and 'prefs[encryptedSessionKey] = encrypted' in store,
    "legacy plaintext session keys are removed on save": 'prefs.remove(stringPreferencesKey("token"))' in store and 'prefs.remove(stringPreferencesKey("user_json"))' in store,
    "decryption failure does not fall back to plaintext": "runCatching { decrypt(encrypted) }.getOrNull()" in store and "prefs[tokenKey]" not in store,
}
failed = [name for name, passed in checks.items() if not passed]
for name, passed in checks.items():
    print(f"{'PASS' if passed else 'FAIL'} {name}")
if failed:
    raise SystemExit(f"{len(failed)} Android session security check(s) failed")
print(f"Android session security source contract OK: {len(checks)} checks.")
