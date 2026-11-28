import test from "node:test";
import assert from "node:assert/strict";
import { webcrypto } from "node:crypto";
import {
  buildDeviceProofMessage,
  createDeviceChallenge,
  decodeBase64Url,
  ecdsaDerToP1363,
  encodeBase64Url,
  verifyP256DeviceSignature,
} from "../supabase/functions/_shared/device_proof_crypto.mjs";

const encoder = new TextEncoder();
const toBase64 = (bytes) => Buffer.from(bytes).toString("base64");

function integerDer(bytes) {
  let first = 0;
  while (first < bytes.length - 1 && bytes[first] === 0) first++;
  let value = bytes.subarray(first);
  if (value[0] & 0x80) value = Buffer.concat([Buffer.from([0]), value]);
  return Buffer.concat([Buffer.from([0x02, value.length]), value]);
}

function rawSignatureToDer(raw) {
  assert.equal(raw.length, 64);
  const r = integerDer(raw.subarray(0, 32));
  const s = integerDer(raw.subarray(32, 64));
  const body = Buffer.concat([r, s]);
  return Buffer.concat([Buffer.from([0x30, body.length]), body]);
}

async function fixture() {
  const pair = await webcrypto.subtle.generateKey(
    { name: "ECDSA", namedCurve: "P-256" },
    true,
    ["sign", "verify"],
  );
  const spki = new Uint8Array(await webcrypto.subtle.exportKey("spki", pair.publicKey));
  return { pair, publicKeySpkiBase64: toBase64(spki) };
}

test("Android DER ECDSA signatures verify after strict P-1363 conversion", async () => {
  const { pair, publicKeySpkiBase64 } = await fixture();
  const message = "TACRESQ-DEVICE-PROOF\nv1\nsynthetic";
  const raw = new Uint8Array(await webcrypto.subtle.sign(
    { name: "ECDSA", hash: "SHA-256" }, pair.privateKey, encoder.encode(message),
  ));
  assert.equal(raw.length, 64);
  const der = rawSignatureToDer(raw);
  assert.deepEqual(ecdsaDerToP1363(der), raw);
  assert.equal(await verifyP256DeviceSignature({
    publicKeySpkiBase64,
    signatureDerBase64Url: encodeBase64Url(der),
    message,
  }), true);
  assert.equal(await verifyP256DeviceSignature({
    publicKeySpkiBase64,
    signatureDerBase64Url: encodeBase64Url(der),
    message: message + "-tampered",
  }), false);
});

test("wrong P-256 key does not verify a valid signature", async () => {
  const first = await fixture();
  const second = await fixture();
  const message = "device-proof-key-binding";
  const raw = new Uint8Array(await webcrypto.subtle.sign(
    { name: "ECDSA", hash: "SHA-256" }, first.pair.privateKey, encoder.encode(message),
  ));
  const der = rawSignatureToDer(raw);
  assert.equal(await verifyP256DeviceSignature({
    publicKeySpkiBase64: second.publicKeySpkiBase64,
    signatureDerBase64Url: encodeBase64Url(der),
    message,
  }), false);
});

test("DER parser rejects malformed, non-minimal, negative, trailing, and out-of-range signatures", () => {
  const invalid = [
    Buffer.from([0x31, 0x00]),
    Buffer.from([0x30, 0x00]),
    Buffer.from([0x30, 0x06, 0x02, 0x01, 0x00, 0x02, 0x01, 0x01]),
    Buffer.from([0x30, 0x06, 0x02, 0x01, 0x80, 0x02, 0x01, 0x01]),
    Buffer.from([0x30, 0x07, 0x02, 0x02, 0x00, 0x01, 0x02, 0x01, 0x01]),
    Buffer.from([0x30, 0x06, 0x02, 0x01, 0x01, 0x02, 0x01, 0x01, 0x00]),
    Buffer.from([0x30, 0x06, 0x02, 0x01, 0x01, 0x02, 0x01, 0x00]),
  ];
  for (const signature of invalid) {
    assert.throws(() => ecdsaDerToP1363(signature));
  }
  assert.throws(() => ecdsaDerToP1363(new Uint8Array(73)));
});

test("proof message has the exact protocol byte layout and rejects mismatched device purpose", async () => {
  const challenge = await createDeviceChallenge();
  const message = buildDeviceProofMessage({
    challengeId: "123e4567-e89b-42d3-a456-426614174000",
    purpose: "registration",
    profileId: "42",
    sessionId: "123e4567-e89b-42d3-a456-426614174001",
    deviceUid: "-",
    challenge: challenge.challenge,
  });
  assert.equal(message, [
    "TACRESQ-DEVICE-PROOF",
    "v1",
    "123e4567-e89b-42d3-a456-426614174000",
    "registration",
    "42",
    "123e4567-e89b-42d3-a456-426614174001",
    "-",
    challenge.challenge,
  ].join("\n"));
  assert.throws(() => buildDeviceProofMessage({
    challengeId: "123e4567-e89b-42d3-a456-426614174000",
    purpose: "registration",
    profileId: "42",
    sessionId: "123e4567-e89b-42d3-a456-426614174001",
    deviceUid: "123e4567-e89b-42d3-a456-426614174002",
    challenge: challenge.challenge,
  }));
  assert.equal(decodeBase64Url(challenge.challenge).length, 32);
});

test("challenge generation returns 32 random bytes and a matching SHA-256 hash", async () => {
  const first = await createDeviceChallenge();
  const second = await createDeviceChallenge();
  assert.equal(decodeBase64Url(first.challenge).length, 32);
  assert.equal(decodeBase64Url(second.challenge).length, 32);
  assert.notEqual(first.challenge, second.challenge);
  const digest = new Uint8Array(await webcrypto.subtle.digest("SHA-256", decodeBase64Url(first.challenge)));
  const expected = Array.from(digest, (byte) => byte.toString(16).padStart(2, "0")).join("");
  assert.equal(first.challengeHash, expected);
  assert.match(first.challengeHash, /^[0-9a-f]{64}$/);
});

test("base64url decoding rejects padding and non-canonical encodings", () => {
  assert.throws(() => decodeBase64Url("AQ=="));
  assert.throws(() => decodeBase64Url("A"));
  assert.equal(encodeBase64Url(new Uint8Array([1, 2, 3])), "AQID");
});
