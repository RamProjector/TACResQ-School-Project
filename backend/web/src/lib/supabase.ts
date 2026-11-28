import { createClient, type SupabaseClient } from "@supabase/supabase-js";

export type TacruRole = "requester" | "responder" | "driver" | "dispatcher" | "admin";
export type AdminManagedRole = Exclude<TacruRole, "admin">;
export interface AdminUser { id: number; name: string; email: string; role: TacruRole; duty_status: DutyStatus | null; account_status: "active" | "suspended"; created_at: string; }
export interface AdminAuditEvent { id: number; actor_user_id: number | null; actor_identity: string; target_user_id: number | null; action: "user_role_changed"; old_role: TacruRole; new_role: TacruRole; reason: string; created_at: string; }
export type AdminInvestigationCategory = "all" | "administrative" | "authentication" | "operations" | "incident" | "assignment";
export type AdminInvestigationSource = "all" | "admin_audit_logs" | "account_lifecycle_audit_logs" | "supabase_auth_audit_logs" | "incident_status_logs" | "incident_claimability_logs" | "incident_request_edit_logs" | "incident_assignment_events" | "app_action_audit_logs" | "device_security_events" | "api_operation_logs";
export interface AdminInvestigationEvent {
  event_id: string;
  source: string;
  category: Exclude<AdminInvestigationCategory, "all">;
  event_type: string;
  occurred_at: string;
  actor_user_id: number | null;
  actor_label: string;
  target_user_id: number | null;
  target_label: string | null;
  incident_id: number | null;
  summary: string;
  details: Record<string, unknown>;
}
export interface AdminInvestigationPage { contract_version: "session_trace_v1"; events: AdminInvestigationEvent[]; total_count: number; }
export interface AdminAccountLifecycleEvent { id: number; actor_profile_id: number; actor_identity: string; target_profile_id: number; target_name: string; action: "account_suspended" | "account_reactivated"; previous_status: "active" | "suspended"; new_status: "active" | "suspended"; reason: string; created_at: string; }
export interface AdminAccountLifecyclePage { events: AdminAccountLifecycleEvent[]; total_count: number; }
export interface AdminAuthSession { session_id: string; created_at: string | null; updated_at: string | null; last_refreshed_at: string | null; aal: string | null; not_after: string | null; record_status: "record_present"; validity_assessment: "not_evaluated"; }
export interface AdminAuthSessionCursor { created_at: string | null; session_id: string; }
export interface AdminAuthSessionPage { items: AdminAuthSession[]; has_more: boolean; next_cursor: AdminAuthSessionCursor | null; source: "supabase_auth_sessions"; source_status: "available"; observed_at: string; status_semantics: string; }
export interface AdminRoleChangeResult { id: number; name: string; email: string; old_role: AdminManagedRole; role: AdminManagedRole; duty_status: DutyStatus | null; }
export type DutyStatus = "off_duty" | "available" | "en_route" | "on_scene" | "transporting";
export type IncidentType = "medical" | "fire" | "vehicular" | "disaster" | "other";
export type IncidentStatus = "pending" | "assigned" | "en_route" | "on_scene" | "transporting" | "resolved" | "cancelled";

export interface TacruProfile {
  id: number;
  name: string;
  email: string;
  phone: string | null;
  role: TacruRole;
  duty_status: DutyStatus | null;
}

export interface Incident {
  id: number;
  type: IncidentType;
  description: string | null;
  latitude: number | null;
  longitude: number | null;
  accuracy_m: number | null;
  location_notes?: string | null;
  location_recorded_at?: string | null;
  status: IncidentStatus;
  requester_name: string | null;
  requester_phone: string | null;
  assigned_to: number | null;
  created_at: string | null;
}

export interface ResponderLocation {
  incident_id: number;
  user_id: number;
  latitude: number;
  longitude: number;
  accuracy_m: number | null;
  recorded_at: string;
}

type TacruDatabase = {
  public: {
    Tables: Record<string, never>;
    Views: Record<string, never>;
    Functions: {
      api_me: { Args: Record<string, never>; Returns: TacruProfile };
      api_all_incidents: { Args: Record<string, never>; Returns: Incident[] };
      api_units: { Args: Record<string, never>; Returns: TacruProfile[] };
      api_assign_incident: { Args: { p_id: number; p_user_id: number }; Returns: Incident };
      api_active_responder_locations: { Args: Record<string, never>; Returns: ResponderLocation[] };
      api_admin_list_users_page: { Args: { p_limit: number; p_offset: number; p_query: string; p_role: string }; Returns: { users: AdminUser[]; total_count: number } };
      api_admin_set_user_role: { Args: { p_user_id: number; p_role: AdminManagedRole; p_reason: string }; Returns: AdminRoleChangeResult };
      api_admin_set_account_status: { Args: { p_target_profile_id: number; p_expected_account_status: "active" | "suspended"; p_account_status: "active" | "suspended"; p_reason: string }; Returns: Record<string, unknown> };
      api_admin_list_audit_logs: { Args: { p_limit: number }; Returns: AdminAuditEvent[] };
      api_admin_list_audit_logs_page: { Args: { p_limit: number; p_offset: number; p_query: string; p_action: string; p_from: string | null; p_to: string | null; p_actor_user_id: number | null; p_target_user_id: number | null }; Returns: { events: AdminAuditEvent[]; total_count: number } };
      api_admin_list_account_lifecycle_events: { Args: { p_limit: number; p_offset: number }; Returns: AdminAccountLifecyclePage };
      api_admin_list_auth_sessions_page: { Args: { p_target_profile_id: number; p_limit: number; p_before_created_at: string | null; p_before_session_id: string | null }; Returns: AdminAuthSessionPage };
      api_admin_list_investigation_events: { Args: { p_limit: number; p_offset: number; p_query: string; p_category: AdminInvestigationCategory; p_source: AdminInvestigationSource; p_from: string | null; p_to: string | null; p_actor_user_id: number | null; p_target_user_id: number | null; p_incident_id: number | null }; Returns: AdminInvestigationPage };
      api_admin_analytics_summary: { Args: { p_from: string; p_to: string }; Returns: AdminAnalyticsSummary };
      api_dependency_health: { Args: Record<string, never>; Returns: string };
    };
    Enums: Record<string, never>;
    CompositeTypes: Record<string, never>;
  };
};

interface RuntimeSupabaseConfig {
  supabaseUrl?: string | null;
  supabasePublishableKey?: string | null;
}

function randomNonZeroHex(byteCount: number): string | null {
  if (!globalThis.crypto?.getRandomValues) return null;
  try {
    const bytes = new Uint8Array(byteCount);
    do {
      globalThis.crypto.getRandomValues(bytes);
    } while (bytes.every((value) => value === 0));
    return Array.from(bytes, (value) => value.toString(16).padStart(2, "0")).join("");
  } catch {
    // Tracing is optional; an unavailable random source must not block an API call.
    return null;
  }
}

// A new trace and span are generated for each Supabase HTTP request. This is
// caller-side observability context only; it is never used for authorization.
function createTraceparent(): string | null {
  const traceId = randomNonZeroHex(16);
  const spanId = randomNonZeroHex(8);
  return traceId && spanId ? `00-${traceId}-${spanId}-01` : null;
}

const traceAwareFetch: typeof fetch = (input, init) => {
  const traceparent = createTraceparent();
  if (!traceparent) return globalThis.fetch(input, init);

  const headers = new Headers(input instanceof Request ? input.headers : undefined);
  new Headers(init?.headers).forEach((value, key) => headers.set(key, value));
  headers.set("traceparent", traceparent);
  return globalThis.fetch(input, { ...init, headers });
};

let supabaseClient: SupabaseClient<TacruDatabase> | null = null;
let supabaseHealthConfig: { url: string; publishableKey: string } | null = null;

export async function initializeSupabase(): Promise<boolean> {
  let supabaseUrl = import.meta.env.VITE_SUPABASE_URL?.trim() ?? "";
  let supabasePublishableKey = import.meta.env.VITE_SUPABASE_PUBLISHABLE_KEY?.trim() ?? "";

  if (!supabaseUrl || !supabasePublishableKey) {
    try {
      const response = await fetch("/runtime-config.json", { cache: "no-store" });
      if (response.ok) {
        const runtimeConfig = await response.json() as RuntimeSupabaseConfig;
        supabaseUrl ||= runtimeConfig.supabaseUrl?.trim() ?? "";
        supabasePublishableKey ||= runtimeConfig.supabasePublishableKey?.trim() ?? "";
      }
    } catch {
      // Local Vite development can instead use VITE_SUPABASE_* in .env.local.
    }
  }

  if (!supabaseUrl || !supabasePublishableKey) return false;
  supabaseHealthConfig = { url: supabaseUrl.replace(/\/$/, ''), publishableKey: supabasePublishableKey };
  supabaseClient = createClient<TacruDatabase>(supabaseUrl, supabasePublishableKey, {
    auth: {
      autoRefreshToken: true,
      detectSessionInUrl: true,
      persistSession: true,
    },
    global: {
      fetch: traceAwareFetch,
    },
  });
  return true;
}

export function getSupabase() {
  if (!supabaseClient) {
    throw new Error("Supabase has not been initialized. Configure the Laravel runtime or local VITE_SUPABASE_* values.");
  }
  return supabaseClient;
}

function throwIfError(error: { message: string } | null) {
  if (error) throw new Error(error.message);
}

export async function getMyProfile(): Promise<TacruProfile> {
  const { data, error } = await getSupabase().rpc("api_me", {}, { get: true });
  throwIfError(error);
  if (!data) throw new Error("No TACRU profile is linked to this account.");
  return data as unknown as TacruProfile;
}

export async function getAllIncidents(): Promise<Incident[]> {
  const { data, error } = await getSupabase().rpc("api_all_incidents");
  throwIfError(error);
  return (data as unknown as Incident[] | null) ?? [];
}

export async function getFieldUnits(): Promise<TacruProfile[]> {
  const { data, error } = await getSupabase().rpc("api_units");
  throwIfError(error);
  return (data as unknown as TacruProfile[] | null) ?? [];
}

export async function getActiveResponderLocations(): Promise<ResponderLocation[]> {
  const { data, error } = await getSupabase().rpc("api_active_responder_locations");
  throwIfError(error);
  return (data as unknown as ResponderLocation[] | null) ?? [];
}

export async function assignIncident(incidentId: number, unitId: number): Promise<Incident> {
  const { data, error } = await getSupabase().rpc("api_assign_incident", {
    p_id: incidentId,
    p_user_id: unitId,
  });
  throwIfError(error);
  if (!data) throw new Error("The assignment request returned no incident record.");
  return data as unknown as Incident;
}

function throwAdminError(error: { code?: string; message?: string } | null) {
  if (!error) return;
  if (error.code === "42501") throw new Error("Administrator access and a verified authenticator session are required.");
  if (error.code === "22023") throw new Error(error.message || "The requested administrative change is not valid.");
  if (error.code === "P0002") throw new Error("The selected account could not be found.");
  if (error.code === "55006") throw new Error("Release or complete this account’s active incident assignments before suspending it.");
  if (error.code === "PGRST202") throw new Error("The audit-history database function is not installed. The database migration must be applied before this page can load.");
  if (error.code === "PGRST301" || error.code === "PGRST302") throw new Error("Your administrator session could not be verified. Sign in again and complete the authenticator check.");
  if (error.code === "57014") throw new Error("The audit query took too long. Narrow the date range or search filters and try again.");
  if (!error.code) throw new Error("The Admin service could not be reached. Check your connection and try again.");
  throw new Error("The Admin service returned an unexpected response (" + error.code + "). Refresh the page; if it continues, contact the system operator.");
}
export interface AdminAnalyticsDailyPoint { day: string; requests: number; }
export interface AdminAnalyticsSummary {
  period_start: string;
  period_end: string;
  reporting_timezone: string;
  total_requests: number;
  pending: number;
  active: number;
  resolved: number;
  cancelled: number;
  average_assignment_minutes: number | null;
  median_assignment_minutes: number | null;
  assigned_request_count: number;
  daily_requests: AdminAnalyticsDailyPoint[];
}

export interface AdminUserPage { users: AdminUser[]; total_count: number; }
export async function getAdminUsersPage(limit: number, offset: number, query: string, role: string): Promise<AdminUserPage> {
  const { data, error } = await getSupabase().rpc("api_admin_list_users_page", { p_limit: limit, p_offset: offset, p_query: query.trim(), p_role: role });
  throwAdminError(error);
  if (!data || typeof data !== "object") throw new Error("The Admin service returned an invalid user page.");
  const page = data as unknown as AdminUserPage;
  if (
    !Array.isArray(page.users)
    || !Number.isSafeInteger(page.total_count)
    || page.total_count < 0
    || page.users.length > limit
    || page.total_count < offset + page.users.length
  ) {
    throw new Error("The Admin service returned an invalid user page.");
  }
  const roles = new Set<TacruRole>(["requester", "responder", "driver", "dispatcher", "admin"]);
  const dutyStatuses = new Set<DutyStatus>(["off_duty", "available", "en_route", "on_scene", "transporting"]);
  const userIds = new Set<number>();
  for (const user of page.users) {
    if (
      !user || typeof user !== "object"
      || !Number.isSafeInteger(user.id) || user.id < 1
      || typeof user.name !== "string" || !user.name.trim()
      || typeof user.email !== "string" || !user.email.trim()
      || !roles.has(user.role)
      || (user.duty_status !== null && !dutyStatuses.has(user.duty_status))
      || !["active", "suspended"].includes(user.account_status)
      || typeof user.created_at !== "string"
      || !Number.isFinite(Date.parse(user.created_at))
    ) {
      throw new Error("The Admin service returned an invalid account record.");
    }
    if (userIds.has(user.id)) {
      throw new Error("The Admin service returned duplicate account identifiers.");
    }
    userIds.add(user.id);
  }
  return page;
}
export async function getAdminAuthSessionsPage(
  profileId: number,
  limit = 10,
  cursor: AdminAuthSessionCursor | null = null,
): Promise<AdminAuthSessionPage> {
  if (!Number.isSafeInteger(profileId) || profileId < 1) {
    throw new Error("A valid account is required to retrieve session records.");
  }
  if (!Number.isSafeInteger(limit) || limit < 1 || limit > 50) {
    throw new Error("The session page size is outside the allowed range.");
  }

  const { data, error } = await getSupabase().rpc("api_admin_list_auth_sessions_page", {
    p_target_profile_id: profileId,
    p_limit: limit,
    p_before_created_at: cursor?.created_at ?? null,
    p_before_session_id: cursor?.session_id ?? null,
  });
  if (error?.code === "PGRST202") {
    throw new Error("The Admin session inventory RPC is not visible in this database's API schema cache. Confirm migration 20261127020000 is applied, refresh the schema cache, and reload the Admin portal.");
  }
  throwAdminError(error);

  if (!data || typeof data !== "object") {
    throw new Error("The Admin service returned an invalid session page.");
  }
  const page = data as unknown as AdminAuthSessionPage;
  if (
    !Array.isArray(page.items)
    || page.items.length > limit
    || typeof page.has_more !== "boolean"
    || page.source !== "supabase_auth_sessions"
    || page.source_status !== "available"
    || typeof page.observed_at !== "string"
    || !Number.isFinite(Date.parse(page.observed_at))
    || typeof page.status_semantics !== "string"
    || (page.next_cursor !== null && (
      !page.next_cursor
      || typeof page.next_cursor.session_id !== "string"
      || !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(page.next_cursor.session_id)
      || (page.next_cursor.created_at !== null && (
        typeof page.next_cursor.created_at !== "string"
        || !Number.isFinite(Date.parse(page.next_cursor.created_at))
      ))
    ))
    || (page.has_more && page.next_cursor === null)
    || (!page.has_more && page.next_cursor !== null)
  ) {
    throw new Error("The Admin service returned an invalid session page.");
  }

  const seen = new Set<string>();
  for (const item of page.items) {
    if (
      !item || typeof item !== "object"
      || typeof item.session_id !== "string"
      || !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(item.session_id)
      || !["record_present"].includes(item.record_status)
      || item.validity_assessment !== "not_evaluated"
      || (item.aal !== null && !["aal1", "aal2"].includes(item.aal))
      || [item.created_at, item.updated_at, item.last_refreshed_at, item.not_after].some(
        (value) => value !== null && (typeof value !== "string" || !Number.isFinite(Date.parse(value))),
      )
      || seen.has(item.session_id)
    ) {
      throw new Error("The Admin service returned an invalid session record.");
    }
    seen.add(item.session_id);
  }

  if (page.has_more && page.items.length !== limit) {
    throw new Error("The Admin service returned an incomplete session cursor.");
  }
  if (page.has_more && page.items[page.items.length - 1]?.session_id !== page.next_cursor?.session_id) {
    throw new Error("The Admin service returned a mismatched session cursor.");
  }
  return page;
}

export async function setAdminUserRole(userId: number, role: AdminManagedRole, reason: string): Promise<AdminRoleChangeResult> {
  const { data, error } = await getSupabase().rpc("api_admin_set_user_role", { p_user_id: userId, p_role: role, p_reason: reason });
  if (error) {
    if (error.code === "42501" || error.code === "22023" || error.code === "P0002") {
      throwAdminError(error);
    }
    throw new Error("The role change outcome for profile #" + userId + " could not be confirmed. The database may have applied it before the connection was interrupted. Do not retry yet: refresh the account directory, then open Audit Logs, select “Admin role changes”, filter Target profile ID to " + userId + ", and refresh the audit results.");
  }
  if (!data || typeof data !== "object") {
    throw new Error("The role change outcome for profile #" + userId + " could not be confirmed. Do not retry yet: refresh the account directory, then open Audit Logs, select “Admin role changes”, filter Target profile ID to " + userId + ", and refresh the audit results.");
  }
  const result = data as unknown as AdminRoleChangeResult;
  const validRoles = new Set<AdminManagedRole>(["requester", "responder", "driver", "dispatcher"]);
  const expectedDutyStatus: DutyStatus | null = role === "responder" || role === "driver" ? "off_duty" : null;
  if (
    !Number.isSafeInteger(result.id) || result.id !== userId
    || typeof result.name !== "string" || !result.name.trim()
    || typeof result.email !== "string" || !result.email.trim()
    || !validRoles.has(result.old_role)
    || result.old_role === role
    || result.role !== role
    || result.duty_status !== expectedDutyStatus
  ) {
    throw new Error("The role change outcome for profile #" + userId + " could not be confirmed because the service returned an invalid confirmation. Do not retry yet: refresh the account directory, then open Audit Logs, select “Admin role changes”, filter Target profile ID to " + userId + ", and refresh the audit results.");
  }
  return result;
}
export async function setAdminAccountStatus(profileId: number, expectedStatus: "active" | "suspended", status: "active" | "suspended", reason: string): Promise<void> {
  const { data, error } = await getSupabase().rpc("api_admin_set_account_status", {
    p_target_profile_id: profileId, p_expected_account_status: expectedStatus, p_account_status: status, p_reason: reason,
  });
  if (error) {
    if (error.code === "40001") throw new Error("This account was changed by another administrator. Refresh the directory and review its current status before trying again.");
    if (["42501", "22023", "P0002", "55006"].includes(error.code ?? "")) throwAdminError(error);
    throw new Error("The account status change for profile #" + profileId + " could not be confirmed. Refresh the directory and lifecycle history before retrying.");
  }
  const result = data as Record<string, unknown> | null;
  if (!result || !["changed", "unchanged"].includes(String(result.status))
    || result.target_profile_id !== profileId || result.account_status !== status) {
    throw new Error("The account status change for profile #" + profileId + " returned an invalid confirmation. Refresh the directory and lifecycle history before retrying.");
  }
}
export async function getAdminAnalyticsSummary(from: string, to: string): Promise<AdminAnalyticsSummary> {
  const { data, error } = await getSupabase().rpc("api_admin_analytics_summary", {
    p_from: from,
    p_to: to,
  });
  throwAdminError(error);
  if (!data || typeof data !== "object") throw new Error("The analytics service returned an invalid summary.");
  const summary = data as unknown as AdminAnalyticsSummary;
  const counts = [summary.total_requests, summary.pending, summary.active, summary.resolved, summary.cancelled, summary.assigned_request_count];
  const validTimestamp = (value: unknown) => typeof value === "string" && Number.isFinite(Date.parse(value));
  const validDuration = (value: unknown) => value === null || (typeof value === "number" && Number.isFinite(value) && value >= 0);
  const validDailyPoints = Array.isArray(summary.daily_requests)
    && summary.daily_requests.every((point) =>
      point !== null
      && typeof point === "object"
      && typeof point.day === "string"
      && /^\d{4}-\d{2}-\d{2}$/.test(point.day)
      && Number.isFinite(Date.parse(point.day + "T00:00:00Z"))
      && new Date(point.day + "T00:00:00Z").toISOString().slice(0, 10) === point.day
      && Number.isSafeInteger(point.requests)
      && point.requests >= 0
    );
  const dailyTotal = validDailyPoints
    ? summary.daily_requests.reduce((total, point) => total + point.requests, 0)
    : -1;
  if (!counts.every((value) => Number.isSafeInteger(value) && value >= 0)
      || !validTimestamp(summary.period_start)
      || !validTimestamp(summary.period_end)
      || Date.parse(summary.period_end) <= Date.parse(summary.period_start)
      || summary.reporting_timezone !== "Asia/Manila"
      || !validDuration(summary.average_assignment_minutes)
      || !validDuration(summary.median_assignment_minutes)
      || summary.assigned_request_count > summary.total_requests
      || (summary.assigned_request_count === 0 && (summary.average_assignment_minutes !== null || summary.median_assignment_minutes !== null))
      || (summary.assigned_request_count > 0 && (summary.average_assignment_minutes === null || summary.median_assignment_minutes === null))
      || summary.pending + summary.active + summary.resolved + summary.cancelled > summary.total_requests
      || !validDailyPoints
      || dailyTotal !== summary.total_requests) {
    throw new Error("The analytics service returned invalid aggregate values.");
  }
  return summary;
}

export async function getAdminInvestigationEventsPage(
  limit: number,
  offset: number,
  query: string,
  category: AdminInvestigationCategory,
  source: AdminInvestigationSource,
  from: string | null,
  to: string | null,
  actorUserId: number | null,
  targetUserId: number | null,
  incidentId: number | null,
): Promise<AdminInvestigationPage> {
  const { data, error } = await getSupabase().rpc("api_admin_list_investigation_events", {
    p_limit: limit,
    p_offset: offset,
    p_query: query.trim(),
    p_category: category,
    p_source: source,
    p_from: from,
    p_to: to,
    p_actor_user_id: actorUserId,
    p_target_user_id: targetUserId,
    p_incident_id: incidentId,
  });
  if (error?.code === "PGRST202") {
    throw new Error("The Admin investigation reader is not visible in this database's API schema cache. Confirm migration 20261127080000 is applied, refresh the schema cache, and reload the Admin portal.");
  }
  throwAdminError(error);
  if (!data || typeof data !== "object") {
    throw new Error("The investigation service returned an invalid page.");
  }
  const page = data as unknown as AdminInvestigationPage;
  if (page.contract_version !== "session_trace_v1") {
    throw new Error("This database's Admin investigation feed does not include the reviewed session and trace correlation contract. Apply migration 20261127080000 and refresh the Admin portal before relying on cross-source searches.");
  }
  if (
    !Array.isArray(page.events)
    || !Number.isSafeInteger(page.total_count)
    || page.total_count < 0
    || page.events.length > limit
    || page.total_count < offset + page.events.length
  ) {
    throw new Error("The investigation service returned an invalid page.");
  }
  const categories = new Set(["administrative", "authentication", "operations", "incident", "assignment"]);
  const eventIds = new Set<string>();
  for (const event of page.events) {
    if (
      !event || typeof event !== "object"
      || typeof event.event_id !== "string" || !event.event_id
      || typeof event.source !== "string" || !event.source
      || !categories.has(event.category)
      || typeof event.event_type !== "string" || !event.event_type
      || typeof event.occurred_at !== "string" || !Number.isFinite(Date.parse(event.occurred_at))
      || typeof event.actor_label !== "string"
      || (event.target_label !== null && typeof event.target_label !== "string")
      || typeof event.summary !== "string"
      || (event.actor_user_id !== null && (!Number.isSafeInteger(event.actor_user_id) || event.actor_user_id < 1))
      || (event.target_user_id !== null && (!Number.isSafeInteger(event.target_user_id) || event.target_user_id < 1))
      || (event.incident_id !== null && (!Number.isSafeInteger(event.incident_id) || event.incident_id < 1))
      || !event.details || typeof event.details !== "object" || Array.isArray(event.details)
    ) {
      throw new Error("The investigation service returned an invalid event.");
    }
    if (eventIds.has(event.event_id)) {
      throw new Error("The investigation service returned duplicate event identifiers.");
    }
    eventIds.add(event.event_id);
  }
  return page;
}

export async function getAdminAccountLifecycleEvents(limit = 5, offset = 0): Promise<AdminAccountLifecyclePage> {
  const { data, error } = await getSupabase().rpc("api_admin_list_account_lifecycle_events", {
    p_limit: limit,
    p_offset: offset,
  });
  if (error?.code === "PGRST202") {
    throw new Error("Account lifecycle history will appear after the reviewed database migration is applied.");
  }
  throwAdminError(error);
  if (!data || typeof data !== "object") throw new Error("The account lifecycle service returned an invalid page.");
  const page = data as unknown as AdminAccountLifecyclePage;
  if (!Array.isArray(page.events) || !Number.isSafeInteger(page.total_count) || page.total_count < 0 || page.events.length > limit || page.total_count < offset + page.events.length) {
    throw new Error("The account lifecycle service returned an invalid page.");
  }
  const ids = new Set<number>();
  for (const event of page.events) {
    if (!event || !Number.isSafeInteger(event.id) || event.id < 1 || ids.has(event.id)
      || !Number.isSafeInteger(event.actor_profile_id) || event.actor_profile_id < 1
      || !Number.isSafeInteger(event.target_profile_id) || event.target_profile_id < 1
      || typeof event.actor_identity !== "string" || !event.actor_identity.trim()
      || typeof event.target_name !== "string" || !event.target_name.trim()
      || !["account_suspended", "account_reactivated"].includes(event.action)
      || !["active", "suspended"].includes(event.previous_status)
      || !["active", "suspended"].includes(event.new_status)
      || typeof event.reason !== "string" || event.reason.length < 10 || event.reason.length > 500
      || typeof event.created_at !== "string" || !Number.isFinite(Date.parse(event.created_at))) {
      throw new Error("The account lifecycle service returned an invalid event.");
    }
    ids.add(event.id);
  }
  return page;
}

export interface AdminAuditPage { events: AdminAuditEvent[]; total_count: number; }

export async function getAdminAuditLogsPage(
  limit: number,
  offset: number,
  query: string,
  action: "all" | "user_role_changed",
  from: string | null,
  to: string | null,
  actorUserId: number | null,
  targetUserId: number | null,
): Promise<AdminAuditPage> {
  const { data, error } = await getSupabase().rpc("api_admin_list_audit_logs_page", {
    p_limit: limit,
    p_offset: offset,
    p_query: query.trim(),
    p_action: action,
    p_from: from,
    p_to: to,
    p_actor_user_id: actorUserId,
    p_target_user_id: targetUserId,
  });
  throwAdminError(error);
  if (!data || typeof data !== "object") throw new Error("The audit service returned an invalid page.");
  const page = data as unknown as AdminAuditPage;
  if (!Array.isArray(page.events) || !Number.isSafeInteger(page.total_count) || page.total_count < 0) {
    throw new Error("The audit service returned an invalid page.");
  }
  return page;
}


export type DependencyProbeResult = {
  status: "healthy" | "slow" | "unavailable";
  duration_ms: number | null;
};

export type AdminDependencyHealth = {
  checked_at: string;
  laravel_backend: DependencyProbeResult;
  supabase_auth: DependencyProbeResult;
  supabase_database: DependencyProbeResult;
};

async function runDependencyProbe(
  request: (signal: AbortSignal) => Promise<boolean>,
): Promise<DependencyProbeResult> {
  const controller = new AbortController();
  const timeout = window.setTimeout(() => controller.abort(), 5000);
  const started = performance.now();
  let abortHandler: (() => void) | null = null;
  const aborted = new Promise<never>((_resolve, reject) => {
    abortHandler = () => reject(new DOMException("Dependency probe timed out.", "AbortError"));
    controller.signal.addEventListener("abort", abortHandler, { once: true });
  });
  try {
    // Race the whole probe, not only fetch(): session retrieval and other
    // preflight work may not accept an AbortSignal themselves.
    const healthy = await Promise.race([request(controller.signal), aborted]);
    return {
      status: healthy ? "healthy" : "unavailable",
      duration_ms: Math.max(0, Math.round(performance.now() - started)),
    };
  } catch {
    return {
      status: controller.signal.aborted ? "slow" : "unavailable",
      duration_ms: Math.max(0, Math.round(performance.now() - started)),
    };
  } finally {
    window.clearTimeout(timeout);
    if (abortHandler) controller.signal.removeEventListener("abort", abortHandler);
  }
}

/**
 * Data-free provider diagnostics for the already-authorized Admin console.
 * The database probe returns a fixed literal and the Auth probe calls only
 * Laravel liveness and Supabase's public Auth health endpoint. The current session token is used only
 * to authorize that fixed RPC; no profile, incident, or operational data is read.
 */
export async function getAdminDependencyHealth(): Promise<AdminDependencyHealth> {
  if (!supabaseHealthConfig || !supabaseClient) {
    throw new Error("Supabase is not initialized.");
  }

  const { url, publishableKey } = supabaseHealthConfig;
  const [backend, auth, database] = await Promise.all([
    runDependencyProbe(async (signal) => {
      const response = await fetch("/up", {
        method: "GET",
        credentials: "omit",
        headers: { Accept: "application/json" },
        cache: "no-store",
        signal,
      });
      return response.ok;
    }),
    runDependencyProbe(async (signal) => {
      const response = await fetch(url + "/auth/v1/health", {
        method: "GET",
        credentials: "omit",
        headers: { apikey: publishableKey, Accept: "application/json" },
        cache: "no-store",
        signal,
      });
      return response.ok;
    }),
    runDependencyProbe(async (signal) => {
      const { data, error } = await getSupabase().auth.getSession();
      const accessToken = data.session?.access_token;
      if (error || !accessToken) return false;
      const response = await fetch(url + "/rest/v1/rpc/api_dependency_health", {
        method: "POST",
        credentials: "omit",
        headers: {
          apikey: publishableKey,
          Authorization: "Bearer " + accessToken,
          "Content-Type": "application/json",
          Accept: "application/json",
        },
        body: "{}",
        cache: "no-store",
        signal,
      });
      if (!response.ok) return false;
      return await response.json() === "tacresq_database_ok";
    }),
  ]);

  return {
    checked_at: new Date().toISOString(),
    laravel_backend: backend,
    supabase_auth: auth,
    supabase_database: database,
  };
}
