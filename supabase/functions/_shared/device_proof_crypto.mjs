const P256_ORDER = BigInt("0xffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632551");
const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const BASE64URL_PATTERN = /^[A-Za-z0-9_-]+$/;
const BASE64_PATTERN = /^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$/;

function bytesToBase64(bytes) {
  let binary = "";
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary);
}

function decodeBase64(value, pattern, urlSafe) {
  if (typeof value !== "string" || value.length === 0 || !pattern.test(value)) {
    throw new TypeError("Invalid base64 encoding");
  }
  const normalized = urlSafe
    ? value.replaceAll("-", "+").replaceAll("_", "/") + "=".repeat((4 - value.length % 4) % 4)
    : value;
  let binary;
  try {
    binary = atob(normalized);
  } catch {
    throw new TypeError("Invalid base64 encoding");
  }
  const bytes = Uint8Array.from(binary, (character) => character.charCodeAt(0));
  const canonical = urlSafe
    ? bytesToBase64(bytes).replaceAll("+", "-").replaceAll("/", "_").replaceAll("=", "")
    : bytesToBase64(bytes);
  if (canonical !== value) throw new TypeError("Non-canonical base64 encoding");
  return bytes;
}

export function encodeBase64Url(bytes) {
  if (!(bytes instanceof Uint8Array)) throw new TypeError("Expected bytes");
  return bytesToBase64(bytes).replaceAll("+", "-").replaceAll("/", "_").replaceAll("=", "");
}

export function decodeBase64Url(value) {
  return decodeBase64(value, BASE64URL_PATTERN, true);
}

function readDerLength(bytes, offset) {
  if (offset >= bytes.length) throw new TypeError("Truncated DER length");
  const first = bytes[offset];
  if (first < 0x80) return { length: first, next: offset + 1 };
  const count = first & 0x7f;
  if (count === 0 || count > 2 || offset + 1 + count > bytes.length) {
    throw new TypeError("Unsupported DER length");
  }
  if (bytes[offset + 1] === 0) throw new TypeError("Non-minimal DER length");
  let length = 0;
  for (let index = 0; index < count; index++) {
    length = (length << 8) | bytes[offset + 1 + index];
  }
  if (length < 0x80 || (count === 2 && length <= 0xff)) {
    throw new TypeError("Non-minimal DER length");
  }
  return { length, next: offset + 1 + count };
}

function readDerInteger(bytes, offset) {
  if (bytes[offset] !== 0x02) throw new TypeError("Expected DER INTEGER");
  const { length, next } = readDerLength(bytes, offset + 1);
  const end = next + length;
  if (length < 1 || length > 33 || end > bytes.length) {
    throw new TypeError("Invalid DER INTEGER length");
  }
  const encoded = bytes.subarray(next, end);
  if ((encoded[0] & 0x80) !== 0) throw new TypeError("Negative ECDSA scalar");
  if (encoded.length > 1 && encoded[0] === 0 && (encoded[1] & 0x80) === 0) {
    throw new TypeError("Non-minimal DER INTEGER");
  }
  const magnitude = encoded[0] === 0 ? encoded.subarray(1) : encoded;
  if (magnitude.length === 0 || magnitude.length > 32) {
    throw new TypeError("ECDSA scalar is outside P-256 width");
  }
  let scalar = 0n;
  for (const byte of magnitude) scalar = (scalar << 8n) | BigInt(byte);
  if (scalar <= 0n || scalar >= P256_ORDER) {
    throw new TypeError("ECDSA scalar is outside the P-256 group order");
  }
  const padded = new Uint8Array(32);
  padded.set(magnitude, 32 - magnitude.length);
  return { bytes: padded, next: end };
}

/**
 * Convert Android SHA256withECDSA ASN.1 DER signature bytes into the fixed
 * 64-byte IEEE P1363 representation required by WebCrypto ECDSA verification.
 * Strict DER parsing rejects non-canonical encodings and out-of-range scalars.
 */
export function ecdsaDerToP1363(der) {
  if (!(der instanceof Uint8Array) || der.length < 8 || der.length > 72) {
    throw new TypeError("Invalid P-256 DER signature size");
  }
  if (der[0] !== 0x30) throw new TypeError("Expected DER SEQUENCE");
  const sequence = readDerLength(der, 1);
  if (sequence.next + sequence.length !== der.length) {
    throw new TypeError("DER SEQUENCE length mismatch");
  }
  const r = readDerInteger(der, sequence.next);
  const s = readDerInteger(der, r.next);
  if (s.next !== der.length) throw new TypeError("Unexpected trailing DER data");
  const raw = new Uint8Array(64);
  raw.set(r.bytes, 0);
  raw.set(s.bytes, 32);
  return raw;
}

export function buildDeviceProofMessage({
  challengeId,
  purpose,
  profileId,
  sessionId,
  deviceUid,
  challenge,
}) {
  if (typeof challengeId !== "string" || !UUID_PATTERN.test(challengeId)) {
    throw new TypeError("Invalid challenge identifier");
  }
  if (!["registration", "session_proof", "step_up"].includes(purpose)) {
    throw new TypeError("Unsupported device proof purpose");
  }
  if (typeof profileId !== "string" || !/^[1-9][0-9]{0,18}$/.test(profileId)) {
    throw new TypeError("Invalid profile identifier");
  }
  if (typeof sessionId !== "string" || !UUID_PATTERN.test(sessionId)) {
    throw new TypeError("Invalid Auth session identifier");
  }
  if (purpose === "registration" ? deviceUid !== "-" : (
    typeof deviceUid !== "string" || !UUID_PATTERN.test(deviceUid)
  )) {
    throw new TypeError("Device identifier does not match proof purpose");
  }
  if (decodeBase64Url(challenge).length !== 32) {
    throw new TypeError("Device challenge must contain exactly 32 random bytes");
  }
  return [
    "TACRESQ-DEVICE-PROOF",
    "v1",
    challengeId,
    purpose,
    profileId,
    sessionId,
    deviceUid,
    challenge,
  ].join("\n");
}

export async function createDeviceChallenge() {
  const raw = new Uint8Array(32);
  crypto.getRandomValues(raw);
  const digest = new Uint8Array(await crypto.subtle.digest("SHA-256", raw));
  return {
    challenge: encodeBase64Url(raw),
    challengeHash: Array.from(digest, (byte) => byte.toString(16).padStart(2, "0")).join(""),
  };
}

export async function verifyP256DeviceSignature({
  publicKeySpkiBase64,
  signatureDerBase64Url,
  message,
}) {
  if (typeof publicKeySpkiBase64 !== "string" || publicKeySpkiBase64.length > 4096) {
    throw new TypeError("Invalid P-256 public key");
  }
  if (typeof message !== "string" || message.length < 1 || message.length > 2048) {
    throw new TypeError("Invalid device proof message");
  }
  const spki = decodeBase64(publicKeySpkiBase64, BASE64_PATTERN, false);
  const der = decodeBase64Url(signatureDerBase64Url);
  const signature = ecdsaDerToP1363(der);
  let key;
  try {
    key = await crypto.subtle.importKey(
      "spki",
      spki,
      { name: "ECDSA", namedCurve: "P-256" },
      false,
      ["verify"],
    );
  } catch {
    throw new TypeError("Unsupported or malformed P-256 public key");
  }
  return crypto.subtle.verify(
    { name: "ECDSA", hash: "SHA-256" },
    key,
    signature,
    new TextEncoder().encode(message),
  );
}
