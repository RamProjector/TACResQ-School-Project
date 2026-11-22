import { createClient, type SupabaseClient } from "@supabase/supabase-js";

export type TacruRole = "requester" | "responder" | "driver" | "dispatcher" | "admin";
export type AdminManagedRole = Exclude<TacruRole, "admin">;
export interface AdminUser { id: number; name: string; email: string; phone: string | null; role: TacruRole; duty_status: DutyStatus | null; created_at: string; }
export interface AdminAuditEvent { id: number; actor_user_id: number | null; actor_identity: string; target_user_id: number | null; action: "user_role_changed"; old_role: TacruRole; new_role: TacruRole; reason: string; created_at: string; }
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
      api_admin_list_audit_logs: { Args: { p_limit: number }; Returns: AdminAuditEvent[] };
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
  const { data, error } = await getSupabase().rpc("api_me");
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
  if (error.code === "42501") throw new Error("Administrator access is required for this action.");
  if (error.code === "22023") throw new Error(error.message || "The requested administrative change is not valid.");
  if (error.code === "P0002") throw new Error("The selected account could not be found.");
  throw new Error("The Admin service could not complete the request. Check the connection and try again.");
}
export interface AdminAnalyticsDailyPoint { day: string; requests: number; }
export interface AdminAnalyticsSummary {
  period_start: string;
  period_end: string;
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
  if (!Array.isArray(page.users) || !Number.isSafeInteger(page.total_count) || page.total_count < 0) throw new Error("The Admin service returned an invalid user page.");
  return page;
}
export async function setAdminUserRole(userId: number, role: AdminManagedRole, reason: string): Promise<AdminRoleChangeResult> {
  const { data, error } = await getSupabase().rpc("api_admin_set_user_role", { p_user_id: userId, p_role: role, p_reason: reason });
  throwAdminError(error);
  if (!data) throw new Error("The role change returned no confirmation.");
  return data as unknown as AdminRoleChangeResult;
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
  if (!counts.every((value) => Number.isSafeInteger(value) && value >= 0)
      || !Array.isArray(summary.daily_requests)
      || !summary.daily_requests.every((point) => typeof point.day === "string" && Number.isSafeInteger(point.requests) && point.requests >= 0)) {
    throw new Error("The analytics service returned invalid aggregate values.");
  }
  return summary;
}

export async function getAdminAuditLogs(limit = 100): Promise<AdminAuditEvent[]> {
  const { data, error } = await getSupabase().rpc("api_admin_list_audit_logs", { p_limit: limit });
  throwAdminError(error);
  return (data as unknown as AdminAuditEvent[] | null) ?? [];
}


export type DependencyProbeResult = {
  status: "healthy" | "degraded" | "unavailable";
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
      status: controller.signal.aborted ? "degraded" : "unavailable",
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
