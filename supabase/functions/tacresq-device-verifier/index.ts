import { createClient } from "npm:@supabase/supabase-js@2.119.2";
import postgres from "npm:postgres@3.4.7";
import {
  buildDeviceProofMessage,
  verifyP256DeviceSignature,
} from "../_shared/device_proof_crypto.mjs";

const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), {
  status,
  headers: {
    "Content-Type": "application/json; charset=utf-8",
    "Cache-Control": "no-store",
    "Pragma": "no-cache",
  },
});
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const MAX_BODY_BYTES = 8192;

const supabaseUrl = Deno.env.get("SUPABASE_URL") ?? "";
const publishableKey = Deno.env.get("SUPABASE_ANON_KEY") ?? Deno.env.get("SUPABASE_PUBLISHABLE_KEY") ?? "";
const databaseUrl = Deno.env.get("TACRESQ_DEVICE_VERIFIER_DATABASE_URL") ?? "";

if (!supabaseUrl || !publishableKey || !databaseUrl) {
  throw new Error("Device verifier server configuration is incomplete");
}
const authClient = createClient(supabaseUrl, publishableKey, {
  auth: { persistSession: false, autoRefreshToken: false, detectSessionInUrl: false },
});
const localDatabase = /(?:127\\.0\\.0\\.1|localhost|host\\.docker\\.internal)/i.test(databaseUrl);
const db = postgres(databaseUrl, {
  max: 1,
  prepare: false,
  ssl: localDatabase ? false : "require",
});

type Claims = { sub?: unknown; session_id?: unknown; role?: unknown; exp?: unknown };
type ChallengeContext = {
  challenge_id: string;
  purpose: "registration" | "session_proof";
  profile_id: number | string;
  auth_session_id: string;
  device_id: string | null;
  device_uid: string;
  public_key: string | null;
  key_algorithm: string | null;
  expires_at: string;
};

async function readJsonBounded(request: Request): Promise<Record<string, unknown> | null> {
  const declared = request.headers.get("content-length");
  if (declared && /^\d+$/.test(declared) && Number(declared) > MAX_BODY_BYTES) return null;
  if (!request.body) return null;
  const reader = request.body.getReader();
  const chunks: Uint8Array[] = [];
  let size = 0;
  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      size += value.byteLength;
      if (size > MAX_BODY_BYTES) {
        await reader.cancel();
        return null;
      }
      chunks.push(value);
    }
  } catch {
    return null;
  }
  const bytes = new Uint8Array(size);
  let offset = 0;
  for (const chunk of chunks) {
    bytes.set(chunk, offset);
    offset += chunk.byteLength;
  }
  try {
    const value: unknown = JSON.parse(new TextDecoder("utf-8", { fatal: true }).decode(bytes));
    return value && typeof value === "object" && !Array.isArray(value)
      ? value as Record<string, unknown>
      : null;
  } catch {
    return null;
  }
}

async function verifiedClaims(request: Request): Promise<{
  authUserId: string;
  authSessionId: string;
}> {
  const header = request.headers.get("authorization") ?? "";
  const match = /^Bearer\s+([^\s]+)$/i.exec(header);
  if (!match) throw new Error("unauthorized");
  // getClaims verifies the JWT against Supabase's configured signing keys.
  // For legacy symmetric signing keys the SDK asks Auth to verify the token.
  const { data, error } = await authClient.auth.getClaims(match[1]);
  if (error || !data?.claims) throw new Error("unauthorized");
  const claims = data.claims as Claims;
  if (typeof claims.sub !== "string" || !UUID.test(claims.sub)
      || typeof claims.session_id !== "string" || !UUID.test(claims.session_id)
      || claims.role !== "authenticated"
      || typeof claims.exp !== "number" || claims.exp <= Math.floor(Date.now() / 1000)) {
    throw new Error("unauthorized");
  }
  return { authUserId: claims.sub, authSessionId: claims.session_id };
}

// postgres.js cannot parameterize an identifier. Keep the function names
// fixed in this allowlist; all values remain bound parameters.
async function issue(
  authUserId: string,
  authSessionId: string,
  purpose: "registration" | "session_proof",
  deviceId: string | null,
) {
  const rows = await db`
    select private.device_verifier_issue_challenge(
      ${authUserId}::uuid, ${authSessionId}::uuid, ${purpose}::text, ${deviceId}::uuid
    ) as result
  `;
  return rows[0]?.result;
}

async function getChallenge(
  authUserId: string,
  authSessionId: string,
  challengeId: string,
): Promise<ChallengeContext> {
  const rows = await db`
    select private.device_verifier_get_challenge(
      ${authUserId}::uuid, ${authSessionId}::uuid, ${challengeId}::uuid
    ) as result
  `;
  return rows[0]?.result as ChallengeContext;
}

async function complete(
  authUserId: string,
  authSessionId: string,
  payload: Record<string, unknown>,
) {
  const rows = await db`
    select private.device_verifier_complete_challenge(
      ${authUserId}::uuid,
      ${authSessionId}::uuid,
      ${payload.challengeId as string}::uuid,
      ${payload.challenge as string}::text,
      ${(payload.publicKeySpkiBase64 ?? null) as string | null}::text,
      ${(payload.deviceName ?? "Android device") as string}::text,
      ${(payload.deviceModel ?? null) as string | null}::text,
      ${(payload.appVersion ?? null) as string | null}::text
    ) as result
  `;
  return rows[0]?.result;
}

Deno.serve(async (request: Request) => {
  if (request.method !== "POST") return json({ error: "Method not allowed" }, 405);
  let auth: { authUserId: string; authSessionId: string };
  try {
    auth = await verifiedClaims(request);
  } catch {
    return json({ error: "Valid authenticated session required" }, 401);
  }

  const payload = await readJsonBounded(request);
  if (!payload) return json({ error: "Invalid or oversized request body" }, 400);

  try {
    if (payload.action === "issue") {
      if (payload.purpose !== "registration" && payload.purpose !== "session_proof") {
        return json({ error: "Unsupported proof purpose" }, 400);
      }
      const deviceId = payload.deviceId === undefined ? null : payload.deviceId;
      if (deviceId !== null && (typeof deviceId !== "string" || !UUID.test(deviceId))) {
        return json({ error: "Invalid device identifier" }, 400);
      }
      const challenge = await issue(
        auth.authUserId,
        auth.authSessionId,
        payload.purpose,
        deviceId as string | null,
      );
      return json(challenge);
    }

    if (payload.action !== "verify"
        || typeof payload.challengeId !== "string" || !UUID.test(payload.challengeId)
        || typeof payload.challenge !== "string"
        || typeof payload.signatureDerBase64Url !== "string") {
      return json({ error: "Invalid proof request" }, 400);
    }

    const context = await getChallenge(
      auth.authUserId,
      auth.authSessionId,
      payload.challengeId,
    );
    const proposedKey = context.purpose === "registration"
      ? payload.publicKeySpkiBase64
      : context.public_key;
    if (typeof proposedKey !== "string" || !proposedKey
        || (context.purpose === "session_proof" && context.key_algorithm !== "ES256")) {
      return json({ error: "Invalid device proof" }, 400);
    }

    let valid = false;
    try {
      const message = buildDeviceProofMessage({
        challengeId: context.challenge_id,
        purpose: context.purpose,
        profileId: String(context.profile_id),
        sessionId: context.auth_session_id,
        deviceUid: context.device_uid,
        challenge: payload.challenge,
      });
      valid = await verifyP256DeviceSignature({
        publicKeySpkiBase64: proposedKey,
        signatureDerBase64Url: payload.signatureDerBase64Url,
        message,
      });
    } catch {
      return json({ error: "Device proof could not be verified" }, 400);
    }
    if (!valid) return json({ error: "Device proof could not be verified" }, 400);

    const result = await complete(auth.authUserId, auth.authSessionId, payload);
    return json(result);
  } catch (error) {
    const message = error instanceof Error ? error.message : "";
    const sqlState = error && typeof error === "object" && "code" in error
      ? String((error as { code?: unknown }).code ?? "")
      : "";
    // Do not echo SQL details, challenge material, signatures, tokens, or keys.
    if (sqlState === "53300" || message.toLowerCase().includes("rate limit")) {
      return json({ error: "Too many device challenges. Try again later." }, 429);
    }
    if (["42501", "23505", "22023"].includes(sqlState)) {
      return json({ error: "Device proof is invalid or unavailable" }, 400);
    }
    return json({ error: "Device verification is temporarily unavailable" }, 503);
  }
});
