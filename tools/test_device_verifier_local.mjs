import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { readFile } from "node:fs/promises";
import { webcrypto } from "node:crypto";
import { buildDeviceProofMessage, encodeBase64Url } from "../supabase/functions/_shared/device_proof_crypto.mjs";

const encoder = new TextEncoder();
const envPath = process.argv[2];
const edgeEnvPath = process.argv[3];
if (!envPath || !edgeEnvPath) throw new Error("Usage: node tools/test_device_verifier_local.mjs <supabase-status-env-file> <edge-env-file>");
const env = {};
for (const line of (await readFile(envPath, "utf8")).split(/\r?\n/)) {
  const match = /^([A-Z0-9_]+)=(.*)$/.exec(line.trim());
  if (!match) continue;
  const raw = match[2];
  env[match[1]] = raw.startsWith("'") && raw.endsWith("'")
    ? raw.slice(1, -1).replaceAll("'\\''", "'")
    : raw.startsWith('"') && raw.endsWith('"') ? raw.slice(1, -1) : raw;
}
const api = env.API_URL?.replace(/\/$/, "");
const anon = env.ANON_KEY;
const service = env.SERVICE_ROLE_KEY;
const dbUrl = env.DB_URL;
if (!api?.startsWith("http://127.0.0.1:") || !anon || !service || !dbUrl?.includes("127.0.0.1")) {
  throw new Error("Refusing device verifier integration test outside the disposable loopback Supabase stack");
}
const endpoint = `${api}/functions/v1/tacresq-device-verifier`;
const edgeEnv = {};
for (const line of (await readFile(edgeEnvPath, "utf8")).split(/\r?\n/)) {
  const match = /^([A-Z0-9_]+)=(.*)$/.exec(line.trim());
  if (match) edgeEnv[match[1]] = match[2];
}
const verifierDbUrl = edgeEnv.TACRESQ_DEVICE_VERIFIER_DATABASE_URL;
if (!verifierDbUrl?.includes("tacresq_device_verifier@host.docker.internal")) {
  throw new Error("Refusing to test a verifier database connection outside the dedicated local login");
}
const authHeaders = { apikey: anon, "Content-Type": "application/json" };
const adminHeaders = { apikey: service, Authorization: `Bearer ${service}`, "Content-Type": "application/json" };
const createdUsers = [];

async function request(url, { method = "GET", headers = {}, body } = {}) {
  const response = await fetch(url, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const text = await response.text();
  let data = null;
  try { data = text ? JSON.parse(text) : null; } catch { /* Keep malformed response as null. */ }
  return { status: response.status, data };
}
async function createUser(label) {
  const email = `tacresq-device-${label}-${crypto.randomUUID()}@example.invalid`;
  const password = `${crypto.randomUUID()}Aa1!`;
  const created = await request(`${api}/auth/v1/admin/users`, {
    method: "POST", headers: adminHeaders,
    body: { email, password, email_confirm: true, user_metadata: { name: `Device Proof ${label}`, phone: "+63917000000" } },
  });
  assert.ok([200, 201].includes(created.status) && created.data?.id, `synthetic Auth user creation failed: HTTP ${created.status}`);
  const signedIn = await request(`${api}/auth/v1/token?grant_type=password`, {
    method: "POST", headers: authHeaders, body: { email, password },
  });
  assert.equal(signedIn.status, 200, "synthetic Auth sign-in failed");
  const token = signedIn.data.access_token;
  const claims = JSON.parse(Buffer.from(token.split(".")[1], "base64url").toString("utf8"));
  assert.equal(claims.sub, created.data.id);
  assert.ok(claims.session_id);
  const user = { id: created.data.id, email, password, token, sessionId: claims.session_id, refreshToken: signedIn.data.refresh_token };
  createdUsers.push(user);
  return user;
}
async function createAdditionalSession(user) {
  const signedIn = await request(`${api}/auth/v1/token?grant_type=password`, {
    method: "POST", headers: authHeaders, body: { email: user.email, password: user.password },
  });
  assert.equal(signedIn.status, 200, "additional synthetic Auth sign-in failed");
  const claims = JSON.parse(Buffer.from(signedIn.data.access_token.split(".")[1], "base64url").toString("utf8"));
  assert.equal(claims.sub, user.id);
  assert.notEqual(claims.session_id, user.sessionId);
  return { ...user, token: signedIn.data.access_token, sessionId: claims.session_id, refreshToken: signedIn.data.refresh_token };
}
async function invoke(user, body) {
  return request(endpoint, {
    method: "POST",
    headers: { ...authHeaders, Authorization: `Bearer ${user.token}` },
    body,
  });
}
function psql(sql, url = dbUrl) {
  return execFileSync("psql", [url, "-X", "-v", "ON_ERROR_STOP=1", "-q", "-t", "-A", "-c", sql], {
    encoding: "utf8",
    env: { ...process.env, PGOPTIONS: "-c client_min_messages=warning" },
  }).trim();
}
function derInteger(input) {
  let first = 0;
  while (first < input.length - 1 && input[first] === 0) first++;
  let value = Buffer.from(input.subarray(first));
  if (value[0] & 0x80) value = Buffer.concat([Buffer.from([0]), value]);
  return Buffer.concat([Buffer.from([0x02, value.length]), value]);
}
function rawToDer(raw) {
  assert.equal(raw.length, 64);
  const r = derInteger(raw.subarray(0, 32));
  const s = derInteger(raw.subarray(32));
  const body = Buffer.concat([r, s]);
  return Buffer.concat([Buffer.from([0x30, body.length]), body]);
}
async function makeKeyPair() {
  return webcrypto.subtle.generateKey({ name: "ECDSA", namedCurve: "P-256" }, true, ["sign", "verify"]);
}
async function signedRequest(user, challenge, pair, publicKeySpkiBase64, overrides = {}) {
  const message = buildDeviceProofMessage({
    challengeId: challenge.challenge_id,
    purpose: challenge.purpose,
    profileId: String(challenge.profile_id),
    sessionId: user.sessionId,
    deviceUid: challenge.device_uid,
    challenge: challenge.challenge,
  });
  const signature = new Uint8Array(await webcrypto.subtle.sign(
    { name: "ECDSA", hash: "SHA-256" }, pair.privateKey, encoder.encode(message),
  ));
  const payload = {
    action: "verify",
    challengeId: challenge.challenge_id,
    challenge: challenge.challenge,
    signatureDerBase64Url: encodeBase64Url(rawToDer(signature)),
    ...(challenge.purpose === "registration" ? { publicKeySpkiBase64 } : {}),
    deviceName: "CI test device",
    deviceModel: "Synthetic Android",
    appVersion: "0.0-test",
    ...overrides,
  };
  return { payload, message };
}
async function issue(user, purpose, deviceId = undefined) {
  const result = await invoke(user, { action: "issue", purpose, ...(deviceId ? { deviceId } : {}) });
  assert.equal(result.status, 200, `challenge issuance failed: HTTP ${result.status}`);
  assert.ok(result.data?.challenge_id && result.data?.challenge);
  assert.equal(Buffer.from(result.data.challenge, "base64url").length, 32);
  return result.data;
}
async function cleanup() {
  try {
    psql(`
      DROP TRIGGER IF EXISTS __device_verifier_fail_event ON public.device_security_events;
      DROP FUNCTION IF EXISTS public.__device_verifier_fail_event();
    `);
  } catch { /* The disposable database may already be stopping. */ }
  for (const user of createdUsers) {
    try {
      await request(`${api}/auth/v1/admin/users/${user.id}`, {
        method: "DELETE", headers: adminHeaders,
      });
    } catch { /* Continue cleanup for remaining synthetic users. */ }
  }
}

try {
  const roleChecks = psql(`
    SELECT
      has_function_privilege('tacresq_device_verifier', 'private.device_verifier_issue_challenge(uuid,uuid,text,uuid)', 'EXECUTE')
      AND has_function_privilege('tacresq_device_verifier', 'private.device_verifier_get_challenge(uuid,uuid,uuid)', 'EXECUTE')
      AND has_function_privilege('tacresq_device_verifier', 'private.device_verifier_complete_challenge(uuid,uuid,uuid,text,text,text,text,text)', 'EXECUTE')
      AND NOT has_function_privilege('authenticated', 'private.device_verifier_issue_challenge(uuid,uuid,text,uuid)', 'EXECUTE')
      AND NOT has_function_privilege('service_role', 'private.device_verifier_issue_challenge(uuid,uuid,text,uuid)', 'EXECUTE')
      AND NOT has_table_privilege('tacresq_device_verifier', 'public.user_devices', 'SELECT')
      AND NOT has_table_privilege('tacresq_device_verifier', 'public.device_challenges', 'UPDATE')
  `);
  assert.equal(roleChecks, "t", "dedicated verifier role must have only private function execution");
  const verifierUrl = new URL(verifierDbUrl);
  const directHost = new URL(dbUrl).hostname;
  const directRead = await import("node:child_process").then(({ spawnSync }) =>
    spawnSync("psql", [
      "-h", directHost,
      "-p", verifierUrl.port || "5432",
      "-U", decodeURIComponent(verifierUrl.username),
      "-d", verifierUrl.pathname.slice(1),
      "-X", "-v", "ON_ERROR_STOP=1", "-q",
      "-c", "SELECT * FROM public.user_devices LIMIT 1",
    ], {
      encoding: "utf8",
      env: { ...process.env, PGPASSWORD: decodeURIComponent(verifierUrl.password) },
    })
  );
  assert.equal(directRead.error, undefined, "psql must run for the direct-table denial check");
  assert.notEqual(directRead.status, 0, "dedicated verifier login must not read registry tables directly");
  console.log("PASS: dedicated verifier login has narrow function execution and no direct registry table privileges");

  const alpha = await createUser("alpha");
  const bravo = await createUser("bravo");
  const alphaPair = await makeKeyPair();
  const alphaPublicKey = Buffer.from(await webcrypto.subtle.exportKey("spki", alphaPair.publicKey)).toString("base64");

  const alphaRegistration = await issue(alpha, "registration");
  const crossUser = await invoke(bravo, {
    action: "verify",
    challengeId: alphaRegistration.challenge_id,
    challenge: alphaRegistration.challenge,
    signatureDerBase64Url: "AA",
    publicKeySpkiBase64: alphaPublicKey,
  });
  assert.equal(crossUser.status, 400, "another Auth user must not retrieve or consume this challenge");
  const alphaSecondSession = await createAdditionalSession(alpha);
  assert.equal((await invoke(alphaSecondSession, {
    action: "verify",
    challengeId: alphaRegistration.challenge_id,
    challenge: alphaRegistration.challenge,
    signatureDerBase64Url: "AA",
    publicKeySpkiBase64: alphaPublicKey,
  })).status, 400, "another session belonging to the same Auth user must not retrieve this challenge");
  console.log("PASS: cross-user and cross-session challenge lookups are rejected");

  const alphaSigned = await signedRequest(alpha, alphaRegistration, alphaPair, alphaPublicKey);
  const badSignature = { ...alphaSigned.payload, signatureDerBase64Url: encodeBase64Url(new Uint8Array([1, 2, 3])) };
  assert.equal((await invoke(alpha, badSignature)).status, 400, "malformed signature must be rejected");
  const wrongPair = await makeKeyPair();
  const wrongKeyProof = await signedRequest(alpha, alphaRegistration, wrongPair, alphaPublicKey);
  assert.equal((await invoke(alpha, wrongKeyProof.payload)).status, 400, "signature from a different private key must be rejected");
  const alteredChallenge = {
    ...alphaRegistration,
    challenge: encodeBase64Url(webcrypto.getRandomValues(new Uint8Array(32))),
  };
  const alteredChallengeProof = await signedRequest(alpha, alteredChallenge, alphaPair, alphaPublicKey);
  assert.equal((await invoke(alpha, alteredChallengeProof.payload)).status, 400, "a valid signature over a challenge not matching the stored hash must be rejected");
  const registered = await invoke(alpha, alphaSigned.payload);
  assert.equal(registered.status, 200, `valid registration proof failed: HTTP ${registered.status}`);
  assert.equal(registered.data.trust_status, "active");
  const alphaDeviceId = registered.data.device_id;
  const alphaDeviceUid = registered.data.device_uid;
  assert.equal((await invoke(bravo, {
    action: "issue", purpose: "session_proof", deviceId: alphaDeviceId,
  })).status, 400, "a different profile must not issue a challenge for this device");
  assert.equal((await invoke(alphaSecondSession, {
    action: "issue", purpose: "session_proof", deviceId: alphaDeviceId,
  })).status, 200, "a new session for the same profile may request a proof for its registered device");
  console.log("PASS: P-256 registration proof created one active device; foreign profiles cannot claim it");

  const replay = await invoke(alpha, alphaSigned.payload);
  assert.equal(replay.status, 400, "a consumed registration challenge must not be replayable");
  console.log("PASS: successful challenge cannot be replayed");

  const alphaSessionChallenge = await issue(alpha, "session_proof", alphaDeviceId);
  const alphaSessionProof = await signedRequest(alpha, alphaSessionChallenge, alphaPair, alphaPublicKey);
  const concurrent = await Promise.all([
    invoke(alpha, alphaSessionProof.payload),
    invoke(alpha, alphaSessionProof.payload),
  ]);
  assert.equal(concurrent.filter((item) => item.status === 200).length, 1, "exactly one concurrent proof must consume the challenge");
  assert.equal(concurrent.filter((item) => item.status === 400).length, 1, "the losing concurrent proof must be rejected");
  console.log("PASS: concurrent duplicate proofs have exactly one winner");

  const bravoPair = await makeKeyPair();
  const bravoPublicKey = Buffer.from(await webcrypto.subtle.exportKey("spki", bravoPair.publicKey)).toString("base64");
  const bravoRegistration = await issue(bravo, "registration");
  const bravoSigned = await signedRequest(bravo, bravoRegistration, bravoPair, bravoPublicKey);

  // Force the append-only event insert to fail. The challenge consumption,
  // device insert, and session binding must all roll back with it.
  psql(`
    CREATE OR REPLACE FUNCTION public.__device_verifier_fail_event()
    RETURNS trigger LANGUAGE plpgsql AS $body$
    BEGIN
      RAISE EXCEPTION 'synthetic event insert failure';
    END;
    $body$;
    CREATE TRIGGER __device_verifier_fail_event
      BEFORE INSERT ON public.device_security_events
      FOR EACH ROW EXECUTE FUNCTION public.__device_verifier_fail_event();
  `);
  const rollbackResponse = await invoke(bravo, bravoSigned.payload);
  assert.equal(rollbackResponse.status, 503, "synthetic event failure should not be reported as success");
  psql(`
    DROP TRIGGER __device_verifier_fail_event ON public.device_security_events;
    DROP FUNCTION public.__device_verifier_fail_event();
  `);
  const bravoProfile = psql(`SELECT id FROM public.profiles WHERE auth_user_id = '${bravo.id}'::uuid`);
  const rollbackState = psql(`
    SELECT
      (SELECT consumed_at IS NULL FROM public.device_challenges WHERE id = '${bravoRegistration.challenge_id}'::uuid)
      AND NOT EXISTS (SELECT 1 FROM public.user_devices WHERE profile_id = ${bravoProfile})
      AND NOT EXISTS (SELECT 1 FROM public.device_sessions WHERE auth_session_id = '${bravo.sessionId}'::uuid)
  `);
  assert.equal(rollbackState, "t", "event failure must roll back challenge, device, and session writes");
  assert.equal((await invoke(bravo, bravoSigned.payload)).status, 200, "unconsumed challenge should succeed after rollback condition is removed");
  console.log("PASS: failed event insert rolls back challenge consumption, device creation, and session binding");

  const bravoDevice = psql(`SELECT id FROM public.user_devices WHERE profile_id = ${bravoProfile} AND trust_status = 'active'`);
  const expired = await issue(bravo, "session_proof", bravoDevice);
  psql(`UPDATE public.device_challenges SET expires_at = clock_timestamp() - interval '1 second' WHERE id = '${expired.challenge_id}'::uuid`);
  const expiredAttempt = await invoke(bravo, {
    action: "verify",
    challengeId: expired.challenge_id,
    challenge: expired.challenge,
    signatureDerBase64Url: "AA",
  });
  assert.equal(expiredAttempt.status, 400, "expired challenge must be rejected");
  console.log("PASS: expired challenge is rejected");

  // Four more challenges bring this session to five within 15 minutes.
  for (let i = 0; i < 3; i++) await issue(bravo, "session_proof", bravoDevice);
  const limited = await invoke(bravo, { action: "issue", purpose: "session_proof", deviceId: bravoDevice });
  assert.equal(limited.status, 429, "persistent per-profile/session issuance limit must reject the sixth challenge");
  console.log("PASS: persistent per-profile/session challenge issuance limit is enforced");

  const bravoExpiredSession = await createAdditionalSession(bravo);
  psql(`UPDATE auth.sessions SET not_after = clock_timestamp() - interval '1 second' WHERE id = '${bravoExpiredSession.sessionId}'::uuid`);
  const expiredAuthSession = await invoke(bravoExpiredSession, {
    action: "issue", purpose: "session_proof", deviceId: bravoDevice,
  });
  assert.equal(expiredAuthSession.status, 400, "session beyond auth.sessions.not_after must be rejected even while its JWT signature is valid");
  console.log("PASS: database rejects Auth sessions beyond their not_after deadline");

  // A still-unexpired JWT after sign-out must not issue a challenge because
  // the database boundary checks the live auth.sessions row.
  const logout = await request(`${api}/auth/v1/logout?scope=local`, {
    method: "POST",
    headers: { ...authHeaders, Authorization: `Bearer ${alpha.token}` },
  });
  assert.ok([200, 204].includes(logout.status), "synthetic local sign-out failed");
  const staleJwt = await invoke(alpha, { action: "issue", purpose: "session_proof", deviceId: alphaDeviceId });
  assert.equal(staleJwt.status, 400, "stale access JWT must not pass the live Auth session database check");
  console.log("PASS: stale access JWT after sign-out cannot issue a device challenge");

  // Private verifier schema/functions must not be reachable through PostgREST.
  const directRpc = await request(`${api}/rest/v1/rpc/device_verifier_issue_challenge`, {
    method: "POST",
    headers: { ...authHeaders, Authorization: `Bearer ${bravo.token}` },
    body: { p_auth_user_id: bravo.id, p_auth_session_id: bravo.sessionId, p_purpose: "registration", p_device_id: null },
  });
  assert.ok([401, 404, 406].includes(directRpc.status), "PostgREST must not expose private verifier routines");
  console.log("PASS: direct authenticated PostgREST calls cannot bypass the Edge verifier");

  // Ensure the device UID returned to the registration client is the one
  // persisted with the device row, not an arbitrary client-selected value.
  const storedUid = psql(`SELECT device_uid::text FROM public.user_devices WHERE id = '${alphaDeviceId}'::uuid`);
  assert.equal(storedUid, alphaDeviceUid);
  console.log("PASS: device UID is generated and persisted by the database");
} finally {
  await cleanup();
}
