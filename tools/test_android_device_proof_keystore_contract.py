#!/usr/bin/env python3
"""Contract checks for the isolated Android Keystore device-proof primitive."""
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "app/src/main/java/com/tacresq/app/data/local/DeviceProofKeyStore.kt"
TEST = ROOT / "app/src/androidTest/java/com/tacresq/app/data/local/DeviceProofKeyStoreTest.kt"
SESSION_STORE = ROOT / "app/src/main/java/com/tacresq/app/data/local/SessionStore.kt"


def require(condition: bool, message: str) -> None:
    if not condition:
        raise SystemExit(f"FAIL: {message}")


def main() -> None:
    source = SOURCE.read_text(encoding="utf-8")
    test = TEST.read_text(encoding="utf-8")
    session_store = SESSION_STORE.read_text(encoding="utf-8")

    for marker, description in [
        ('"tacresq_device_proof_p256_v1"', "versioned device-proof alias"),
        ('"AndroidKeyStore"', "Android Keystore provider"),
        ('ECGenParameterSpec(CURVE_NAME)', "explicit EC curve"),
        ('"secp256r1"', "P-256 curve"),
        ('"SHA256withECDSA"', "Android DER ECDSA signature algorithm"),
        ('KeyProperties.DIGEST_SHA256', "SHA-256 digest restriction"),
        ('KeyPermanentlyInvalidatedException', "invalidated-key handling"),
        ('DeviceProofKeySecurityLevel', "actual key security-level reporting"),
        ('KeyProperties.SECURITY_LEVEL_UNKNOWN_SECURE', "unknown-secure key level reporting"),
        ('Base64.encodeToString(pair.public.encoded, Base64.NO_WRAP)', "SPKI public-key export"),
    ]:
        require(marker in source, f"missing {description}")

    require("tacresq_session_aes_gcm_v1" in session_store, "existing AES-GCM session alias must remain present")
    device_alias = re.search(r'KEY_ALIAS\s*=\s*"([^"]+)"', source)
    session_alias = re.search(r'KEY_ALIAS\s*=\s*"([^"]+)"', session_store)
    require(device_alias is not None and session_alias is not None, "both Android Keystore aliases must be explicit")
    require(device_alias.group(1) != session_alias.group(1), "device signing key must not reuse the AES session key alias")

    require("check(store.containsAlias(KEY_ALIAS))" in source, "signing must not silently create/replace a missing key")
    require("generator.generateKeyPair()" in source, "explicit key creation path must exist")
    require("internal fun deleteForRecovery()" in source, "recovery deletion must remain an internal explicit method")
    require("private.encoded" not in source and "privateKey.encoded" not in source, "private key material must never be exported")
    require("message.size <= MAX_MESSAGE_BYTES" in source, "signed message must be bounded")
    require("signature.size in MIN_DER_SIGNATURE_BYTES..MAX_DER_SIGNATURE_BYTES" in source, "DER signature size must be bounded")

    for marker in [
        "publicKeyIsStableAndUsesP256Spki",
        "sha256WithEcdsaSignatureVerifiesAndUsesDerSequence",
        "alteredMessageDoesNotVerify",
        "explicitRecoveryDeletesOnlyTheVersionedProofAliasAndCreatesANewKeyOnRequest",
        "signingRequiresAnExistingKeyAndBoundsTheMessage",
    ]:
        require(marker in test, f"missing Android instrumentation test: {marker}")

    # Keep this key manager dormant until the server-side deployment and
    # device-level test gates are approved. A future integration must update
    # this contract rather than silently wiring proof into emergency flows.
    app_sources = list((ROOT / "app/src/main").rglob("*.kt"))
    references = [
        path.relative_to(ROOT).as_posix()
        for path in app_sources
        if path != SOURCE and "DeviceProofKeyStore" in path.read_text(encoding="utf-8")
    ]
    require(not references, f"device proof must remain isolated from app flows for now: {references}")

    print("PASS: Android Keystore P-256 key manager remains isolated, non-exportable, and separately tested")


if __name__ == "__main__":
    main()
