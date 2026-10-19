import { createClient, type SupabaseClient } from "@supabase/supabase-js";

export type TacruRole = "requester" | "responder" | "driver" | "dispatcher";
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

type TacruDatabase = {
  public: {
    Tables: Record<string, never>;
    Views: Record<string, never>;
    Functions: {
      api_me: { Args: Record<string, never>; Returns: TacruProfile };
      api_all_incidents: { Args: Record<string, never>; Returns: Incident[] };
      api_units: { Args: Record<string, never>; Returns: TacruProfile[] };
      api_assign_incident: { Args: { p_id: number; p_user_id: number }; Returns: Incident };
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

export async function initializeSupabase(): Promise<boolean> {
  let supabaseUrl = import.meta.env.VITE_SUPABASE_URL?.trim() ?? "";
  let supabasePublishableKey = import.meta.env.VITE_SUPABASE_PUBLISHABLE_KEY?.trim() ?? "";

  if (!supabaseUrl || !supabasePublishableKey) {
    try {
      const response = await fetch("/dispatch/config.json", { cache: "no-store" });
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

export async function assignIncident(incidentId: number, unitId: number): Promise<Incident> {
  const { data, error } = await getSupabase().rpc("api_assign_incident", {
    p_id: incidentId,
    p_user_id: unitId,
  });
  throwIfError(error);
  if (!data) throw new Error("The assignment request returned no incident record.");
  return data as unknown as Incident;
}
