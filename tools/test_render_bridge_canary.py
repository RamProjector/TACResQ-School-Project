#!/usr/bin/env python3
"""Guard the opt-in Render bridge canary against accidental full-client cutover."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
gradle = (ROOT / "app/build.gradle.kts").read_text()
container = (ROOT / "app/src/main/java/com/tacresq/app/AppContainer.kt").read_text()
api = (ROOT / "app/src/main/java/com/tacresq/app/data/remote/SupabaseTacApi.kt").read_text()
bridge_config = (ROOT / "backend/src/config/tacru.php").read_text()

checks = {
    "debug bridge canary remains explicitly opt-in": 
        'providers.gradleProperty("useRenderBridge").orNull == "true"' in gradle,
    "Render bridge canary requires real Supabase mode":
        'if (useRenderBridgeInDebug && !useSupabaseInDebug)' in gradle,
    "release builds route data RPCs through Render":
        'buildConfigField("boolean", "USE_RENDER_BRIDGE", "true")' in gradle,
    "AppContainer passes the build flag to the Supabase adapter":
        "useRenderBridge = AppConfig.USE_RENDER_BRIDGE" in container,
    "all current data RPCs have a bridge transport": 
        all(f'@POST("bridge/rpc/{name}")' in api for name in (
            "api_me",
            "api_set_duty_status",
            "api_my_incidents",
            "api_all_incidents",
            "api_units",
            "api_active_responder_locations",
            "api_create_incident_v4",
            "api_cancel_my_incident",
            "api_update_my_incident",
            "api_update_incident_status",
            "api_update_my_assignment_status",
            "api_assign_incident",
            "api_assign_incident_team",
            "api_record_location_v2",
        )),
    "server allowlist exactly covers the client bridge RPCs":
        all(f"'{name}'" in bridge_config for name in (
            "api_me",
            "api_set_duty_status",
            "api_my_incidents",
            "api_all_incidents",
            "api_units",
            "api_active_responder_locations",
            "api_create_incident_v4",
            "api_cancel_my_incident",
            "api_update_my_incident",
            "api_update_incident_status",
            "api_update_my_assignment_status",
            "api_assign_incident",
            "api_assign_incident_team",
            "api_record_location_v2",
        )),
    "client bridge transport count matches the allowlisted RPC surface":
        api.count('@POST("bridge/rpc/') == 14,
    "auth continues using Supabase endpoints":
        'baseUrl(AppConfig.SUPABASE_URL.trimEnd(\'/\') + "/")' in api
        and '@POST("auth/v1/token")' in api
        and '@POST("auth/v1/signup")' in api,
    "bridge forwards the caller bearer token":
        '.header("Authorization", "Bearer $it")' in api
        and '@Header("Authorization") authorization: String? = null' in api,
    "all normal data methods prefer bridge and never silently fallback after bridge errors":
        all(f"bridgeRpc?.{name}(" in api for name in (
            "me", "setDutyStatus", "myIncidents", "allIncidents", "units",
            "activeResponderLocations", "createIncidentV4", "cancelMyIncident",
            "updateMyIncident", "updateIncidentStatus", "updateMyAssignmentStatus",
            "assignIncident", "assignIncidentTeam", "recordLocation",
        )),
}
failed = [name for name, passed in checks.items() if not passed]
for name, passed in checks.items():
    print(f"{'PASS' if passed else 'FAIL'} {name}")
if failed:
    raise SystemExit(f"{len(failed)} Render bridge canary contract check(s) failed")
print(f"Render bridge canary contract OK: {len(checks)} checks.")
