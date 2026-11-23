import { createClient } from "npm:@supabase/supabase-js@2";
const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
function base64Url(value: string | Uint8Array): string {
  const bytes = typeof value === "string" ? new TextEncoder().encode(value) : value;
  let binary = "";
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary).replaceAll("+", "-").replaceAll("/", "_").replaceAll("=", "");
}
async function getGoogleAccessToken(account: Record<string, string>): Promise<string> {
  const header = base64Url(JSON.stringify({ alg: "RS256", typ: "JWT" }));
  const issuedAt = Math.floor(Date.now() / 1000);
  const claim = base64Url(JSON.stringify({ iss: account.client_email, scope: "https://www.googleapis.com/auth/firebase.messaging", aud: account.token_uri, iat: issuedAt, exp: issuedAt + 3600 }));
  const unsigned = `${header}.${claim}`;
  const pkcs8 = account.private_key
    .split(/\r?\n/)
    .filter((line) => !line.startsWith("-----"))
    .join("")
    .replace(/\s/g, "");
  const keyBytes = Uint8Array.from(atob(pkcs8), (character) => character.charCodeAt(0));
  const key = await crypto.subtle.importKey("pkcs8", keyBytes, { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" }, false, ["sign"]);
  const signature = new Uint8Array(await crypto.subtle.sign("RSASSA-PKCS1-v1_5", key, new TextEncoder().encode(unsigned)));
  const assertion = `${unsigned}.${base64Url(signature)}`;
  const response = await fetch(account.token_uri, { method: "POST", headers: { "Content-Type": "application/x-www-form-urlencoded" }, body: new URLSearchParams({ grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer", assertion }) });
  const data = await response.json();
  if (!response.ok || !data.access_token) throw new Error(`Google OAuth token request failed (${response.status})`);
  return data.access_token as string;
}
Deno.serve(async (request: Request) => {
  if (request.method !== "POST") return json({ error: "POST required" }, 405);
  const authHeader = request.headers.get("Authorization") ?? "";
  const userToken = authHeader.replace(/^Bearer\s+/i, "");
  if (!userToken) return json({ error: "Authorization bearer token required" }, 401);
  const supabaseUrl = Deno.env.get("SUPABASE_URL");
  const serviceRoleEnvName = ["SUPABASE", "SERVICE_ROLE", "KEY"].join("_");
  const serviceKey = Deno.env.get(serviceRoleEnvName);
  if (!supabaseUrl || !serviceKey) return json({ error: "Supabase server configuration is missing" }, 503);
  const admin = createClient(supabaseUrl, serviceKey, { auth: { persistSession: false } });
  const { data: userResult, error: authError } = await admin.auth.getUser(userToken);
  if (authError || !userResult.user) return json({ error: "Invalid or expired session" }, 401);
  let payload: { event?: string; incident_id?: number };
  try { payload = await request.json(); } catch { return json({ error: "Invalid JSON body" }, 400); }
  const event = payload.event;
  const incidentId = Number(payload.incident_id);
  if (!event || !Number.isSafeInteger(incidentId) || incidentId <= 0) return json({ error: "event and positive incident_id are required" }, 400);
  if (!["incident_created", "incident_assigned", "incident_status"].includes(event)) return json({ error: "Unsupported incident event" }, 400);
  const { data: actor, error: actorError } = await admin.from("profiles").select("id,role,account_status").eq("auth_user_id", userResult.user.id).single();
  if (actorError || !actor || actor.account_status !== "active") return json({ error: "Active TACResQ account required" }, 403);
  const { data: incident, error: incidentError } = await admin.from("incidents").select("id,requester_id,assigned_to,status").eq("id", incidentId).single();
  if (incidentError || !incident) return json({ error: "Incident not found" }, 404);
  let targetProfileIds: number[] = [];
  let title = "TACResQ update";
  let body = `Incident #${incidentId} was updated.`;
  if (event === "incident_created") {
    if (actor.role !== "requester" || actor.id !== incident.requester_id) return json({ error: "Not allowed to notify for this incident" }, 403);
    const { data: dispatchers, error: dispatcherError } = await admin.from("profiles").select("id").eq("role", "dispatcher").eq("account_status", "active");
    if (dispatcherError) return json({ error: "Could not resolve notification recipients" }, 500);
    targetProfileIds = (dispatchers ?? []).map((profile) => profile.id as number);
    title = "New emergency request";
    body = `Incident #${incidentId} is waiting in the dispatch queue.`;
  } else if (event === "incident_assigned") {
    if (actor.role !== "dispatcher" || incident.status !== "assigned" || !incident.assigned_to) return json({ error: "Only dispatchers can notify a unit assignment" }, 403);
    targetProfileIds = [incident.assigned_to as number];
    title = "New dispatch assignment";
    body = `Incident #${incidentId} has been assigned to you.`;
  } else {
    if (!["responder", "driver"].includes(actor.role) || actor.id !== incident.assigned_to) return json({ error: "Only the assigned unit can notify status changes" }, 403);
    targetProfileIds = [incident.requester_id as number];
    title = "Emergency request update";
    body = `Incident #${incidentId} status: ${incident.status}.`;
  }
  if (targetProfileIds.length === 0) return json({ sent: 0, reason: "No target accounts are provisioned" });

  // Resolve every recipient against current account state before loading any
  // FCM registration. A stale assignment must not notify a suspended account.
  const { data: activeRecipients, error: recipientError } = await admin
    .from("profiles")
    .select("id")
    .in("id", targetProfileIds)
    .eq("account_status", "active");
  if (recipientError) return json({ error: "Could not verify notification recipients" }, 500);
  targetProfileIds = (activeRecipients ?? []).map((profile) => profile.id as number);
  if (targetProfileIds.length === 0) return json({ sent: 0, reason: "No active target accounts are provisioned" });

  const { data: registrations, error: tokenError } = await admin.from("fcm_tokens").select("id,profile_id,token").in("profile_id", targetProfileIds);
  if (tokenError) return json({ error: "Could not load notification registrations" }, 500);
  if (!registrations?.length) return json({ sent: 0, reason: "Recipients have not registered an FCM token" });
  const serviceAccountSecret = Deno.env.get("FCM_SERVICE_ACCOUNT_JSON");
  if (!serviceAccountSecret) return json({ error: "FCM is not configured; add the FCM service-account secret" }, 503);
  let googleAccount: Record<string, string>;
  try {
    googleAccount = JSON.parse(serviceAccountSecret);
    if (!googleAccount.client_email || !googleAccount.private_key || !googleAccount.project_id || !googleAccount.token_uri) throw new Error("missing service-account fields");
  } catch { return json({ error: "FCM service-account secret is not valid JSON or lacks required fields" }, 503); }
  let accessToken: string;
  try {
    accessToken = await getGoogleAccessToken(googleAccount);
  } catch {
    console.error("Google OAuth token request failed.");
    return json({ error: "Notification provider authentication failed" }, 502);
  }
  let sent = 0;
  const invalidTokenIds: number[] = [];
  for (const registration of registrations) {
    try {
      const response = await fetch(`https://fcm.googleapis.com/v1/projects/${googleAccount.project_id}/messages:send`, { method: "POST", headers: { Authorization: `Bearer ${accessToken}`, "Content-Type": "application/json" }, body: JSON.stringify({ message: { token: registration.token, notification: { title, body }, data: { event, incident_id: String(incidentId) }, android: { priority: "HIGH" } } }) });
      if (response.ok) sent++;
      else {
        const errorBody = await response.text();
        if (response.status === 404 || errorBody.includes("UNREGISTERED")) invalidTokenIds.push(registration.id as number);
        console.error(`FCM send failed (${response.status}) for registration ${registration.id}`);
      }
    } catch {
      console.error(`FCM network request failed for registration ${registration.id}`);
    }
  }
  if (invalidTokenIds.length) await admin.from("fcm_tokens").delete().in("id", invalidTokenIds);
  return json({ sent, attempted: registrations.length });
});
