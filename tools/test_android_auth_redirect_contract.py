from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
config = (ROOT / "app/src/main/java/com/tacresq/app/AppConfig.kt").read_text()
auth_api = (ROOT / "app/src/main/java/com/tacresq/app/data/remote/SupabaseTacApi.kt").read_text()
manifest = (ROOT / "app/src/main/AndroidManifest.xml").read_text()
gradle = (ROOT / "app/build.gradle.kts").read_text()
callback = (ROOT / "app/src/main/java/com/tacresq/app/AuthCallbackMessage.kt").read_text()
unit_test = (ROOT / "app/src/test/java/com/tacresq/app/AuthCallbackMessageTest.kt").read_text()

checks = {
    "canonical Android auth callback is configured": 'const val AUTH_REDIRECT_URL = "tacresq://auth/callback"' in config,
    "signup passes an explicit redirect_to query parameter": '@Query("redirect_to") redirectTo: String = AppConfig.AUTH_REDIRECT_URL' in auth_api,
    "Android callback intent filter matches the configured URI": 'android:scheme="${authRedirectScheme}" android:host="auth" android:path="/callback"' in manifest,
    "Supabase debug build uses the production callback scheme": 'if (useSupabaseInDebug) "tacresq" else "tacresq-demo"' in gradle,
    "release build uses the production callback scheme": 'manifestPlaceholders["authRedirectScheme"] = "tacresq"' in gradle,
    "demo and real-backend callback schemes stay isolated": 'manifestPlaceholders["authRedirectScheme"] =' in gradle and '"tacresq-demo"' in gradle,
    "callback parser explicitly rejects token-based session creation": "never reads access tokens from query parameters or fragments" in callback,
    "callback requires exact scheme, host, path, and no user info": 'uri.scheme.equals("tacresq", ignoreCase = true)' in callback and 'uri.host.equals("auth", ignoreCase = true)' in callback and 'uri.rawPath == "/callback"' in callback and "uri.rawUserInfo == null" in callback,
    "JVM tests cover hostile and malformed callbacks": "rejectsOtherSchemesHostsAndPaths" in unit_test and "rejectsMissingAndMalformedUris" in unit_test,
}
failed = [name for name, ok in checks.items() if not ok]
for name, ok in checks.items():
    print(f'{"PASS" if ok else "FAIL"}: {name}')
if failed:
    raise SystemExit(f"{len(failed)} auth redirect contract check(s) failed.")
print(f"Passed all {len(checks)} Android auth redirect contract checks.")
