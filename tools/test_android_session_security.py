#!/usr/bin/env python3
"""Static guardrails for encrypted Android session persistence.

These checks complement, but do not replace, Android Keystore instrumentation tests.
"""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
store = (ROOT / "app/src/main/java/com/tacresq/app/data/local/SessionStore.kt").read_text()
repository = (ROOT / "app/src/main/java/com/tacresq/app/data/repo/TacRepository.kt").read_text()
start_session = repository.split("private suspend fun startSession(response: AuthResponse)", 1)[1].split("private suspend fun endSession()", 1)[0]
legacy_cleanup = store.split(".onEach { prefs ->", 1)[1].split(".map { prefs ->", 1)[0]
retry_policy_test = (ROOT / "app/src/test/java/com/tacresq/app/data/local/SessionStoreRetryPolicyTest.kt").read_text()

checks = {
    "session encryption uses Android Keystore": 'const val ANDROID_KEYSTORE = "AndroidKeyStore"' in store and 'KeyStore.getInstance(ANDROID_KEYSTORE)' in store,
    "session encryption uses AES-GCM": '"AES/GCM/NoPadding"' in store and "GCMParameterSpec" in store,
    "encryption generates a fresh IV": "cipher.iv + cipherText" in store and "setRandomizedEncryptionRequired(true)" in store,
    "persisted session is stored as one encrypted value": 'stringPreferencesKey("encrypted_session_v1")' in store and 'prefs[encryptedSessionKey] = encrypted' in store,
    "legacy plaintext session keys are removed on save": 'prefs.remove(stringPreferencesKey("token"))' in store and 'prefs.remove(stringPreferencesKey("user_json"))' in store,
    "legacy plaintext session keys are removed on first session-store read": 'context.sessionDataStore.edit { current ->' in legacy_cleanup and 'current.remove(stringPreferencesKey("token"))' in legacy_cleanup and 'current.remove(stringPreferencesKey("user_json"))' in legacy_cleanup,
    "decryption failure does not fall back to plaintext": "runCatching { decrypt(encrypted) }.getOrNull()" in store and "prefs[tokenKey]" not in store,
    "bearer token is not retained before encrypted persistence succeeds": start_session.index("store.save(Session(response.token, response.user))") < start_session.index("tokenHolder.value = response.token"),
    "transient DataStore read failures retry through the explicit policy helper": ".retryWhen { cause, attempt ->" in store and "shouldRetrySessionStoreRead(cause)" in store and "internal fun shouldRetrySessionStoreRead(cause: Throwable)" in store and "emit(emptyPreferences())" not in store,
    "DataStore retries only recoverable IO failures": "cause is IOException && cause !is kotlinx.coroutines.CancellationException" in store and "onlyRecoverableIoFailuresAreRetried" in retry_policy_test and "IOException(" in retry_policy_test and "IllegalStateException(" in retry_policy_test and "SecurityException(" in retry_policy_test and "CancellationException(" in retry_policy_test,
    "DataStore read retries use bounded exponential backoff": "sessionStoreReadRetryDelayMillis(attempt)" in store and "(1_000L shl exponent).coerceAtMost(30_000L)" in store and "retryDelayUsesCappedExponentialBackoff" in retry_policy_test,
    "logout clears local session before attempting remote revocation": repository.index("val capturedToken = tokenHolder.value") < repository.index("endSession()") < repository.index("api.logout(capturedToken)"),
    "remote logout uses the captured token after token holder is cleared": "suspend fun logout(token: String?)" in (ROOT / "app/src/main/java/com/tacresq/app/data/remote/TacApi.kt").read_text() and 'auth.logout("Bearer $token")' in (ROOT / "app/src/main/java/com/tacresq/app/data/remote/SupabaseTacApi.kt").read_text(),
    "Supabase logout explicitly revokes only the current device session": '@Query("scope") scope: String = "local"' in (ROOT / "app/src/main/java/com/tacresq/app/data/remote/SupabaseTacApi.kt").read_text(),
    "logout schedules remote revocation in application scope after local cleanup": "appScope.launch(Dispatchers.IO)" in repository and repository.index("endSession()") < repository.index("appScope.launch(Dispatchers.IO)") < repository.index("api.logout(capturedToken)"),
}
failed = [name for name, passed in checks.items() if not passed]
for name, passed in checks.items():
    print(f"{'PASS' if passed else 'FAIL'} {name}")
if failed:
    raise SystemExit(f"{len(failed)} Android session security check(s) failed")
print(f"Android session security source contract OK: {len(checks)} checks.")
