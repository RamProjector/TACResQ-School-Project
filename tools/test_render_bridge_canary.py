#!/usr/bin/env python3
"""Guard the authenticated Render bridge as the release data path."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
gradle = (ROOT / "app/build.gradle.kts").read_text()
container = (ROOT / "app/src/main/java/com/tacresq/app/AppContainer.kt").read_text()
api = (ROOT / "app/src/main/java/com/tacresq/app/data/remote/SupabaseTacApi.kt").read_text()
bridge_config = (ROOT / "backend/src/config/tacru.php").read_text()

rpc_names = (
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
)

checks = {
    "Render bridge debug QA remains opt-in":
        'providers.gradleProperty("useRenderBridge").orNull == "true"' in gradle,
    "Render bridge debug QA requires Supabase mode":
        'if (useRenderBridgeInDebug && !useSupabaseInDebug)' in gradle,
    "release builds require the Render bridge":
        'buildConfigField("boolean", "USE_RENDER_BRIDGE", "true")' in gradle,
    "AppContainer passes the build flag to the adapter":
        "useRenderBridge = AppConfig.USE_RENDER_BRIDGE" in container,
    "all 14 data RPCs have bridge transports":
        all(f'@POST("bridge/rpc/{name}")' in api for name in rpc_names)
        and api.count('@POST("bridge/rpc/') == len(rpc_names),
    "server allowlist covers the complete client RPC surface":
        all(f"'{name}'" in bridge_config for name in rpc_names),
    "all data methods use bridge transport when enabled":
        all(f"bridgeRpc?.{name}(" in api for name in (
            "me", "setDutyStatus", "myIncidents", "allIncidents", "units",
            "activeResponderLocations", "updateIncidentStatus",
            "updateMyAssignmentStatus", "assignIncident", "assignIncidentTeam",
            "recordLocation",
        ))
        and all(f"if (bridgeRpc != null) bridgeRpc.{name}(" in api for name in (
            "createIncidentV4", "cancelMyIncident", "updateMyIncident",
        )),
    "Supabase Auth still uses Supabase endpoints":
        'baseUrl(AppConfig.SUPABASE_URL.trimEnd(\'/\') + "/")' in api
        and '@POST("auth/v1/token")' in api
        and '@POST("auth/v1/signup")' in api,
    "bridge forwards caller bearer token":
        '.header("Authorization", "Bearer $it")' in api
        and '@Header("Authorization") authorization: String? = null' in api,
    "bridge errors are not caught and retried against direct Supabase":
        "catch (Exception)" not in api and "catch (Throwable)" not in api,
}
failed = [name for name, passed in checks.items() if not passed]
for name, passed in checks.items():
    print(f"{'PASS' if passed else 'FAIL'} {name}")
if failed:
    raise SystemExit(f"{len(failed)} Render bridge contract check(s) failed")
print(f"Render bridge contract OK: {len(checks)} checks.")
