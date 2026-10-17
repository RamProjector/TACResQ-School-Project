import { createClient } from "@supabase/supabase-js";

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
  latitude: number;
  longitude: number;
  accuracy_m: number | null;
  status: IncidentStatus;
  requester_name: string | null;
  requester_phone: string | null;
  assigned_to: number | null;
  created_at: string | null;
}

const supabaseUrl = import.meta.env.VITE_SUPABASE_URL?.trim();
const supabasePublishableKey = import.meta.env.VITE_SUPABASE_PUBLISHABLE_KEY?.trim();

export const isSupabaseConfigured = Boolean(supabaseUrl && supabasePublishableKey);
export const supabase = isSupabaseConfigured
  ? createClient(supabaseUrl!, supabasePublishableKey!, {
      auth: {
        autoRefreshToken: true,
        detectSessionInUrl: true,
        persistSession: true,
      },
    })
  : null;

export function getSupabase() {
  if (!supabase) {
    throw new Error("Supabase is not configured. Set VITE_SUPABASE_URL and VITE_SUPABASE_PUBLISHABLE_KEY.");
  }
  return supabase;
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
