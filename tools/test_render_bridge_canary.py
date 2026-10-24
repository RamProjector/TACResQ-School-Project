#!/usr/bin/env python3
"""Guard the opt-in full-data Render bridge canary without enabling release cutover."""
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
gradle = (ROOT / "app/build.gradle.kts").read_text()
container = (ROOT / "app/src/main/java/com/tacresq/app/AppContainer.kt").read_text()
api = (ROOT / "app/src/main/java/com/tacresq/app/data/remote/SupabaseTacApi.kt").read_text()
bridge_config = (ROOT / "backend/src/config/tacru.php").read_text()

expected_rpcs = {
    "api_active_responder_locations",
    "api_all_incidents",
    "api_assign_incident",
    "api_assign_incident_team",
    "api_cancel_my_incident",
    "api_create_incident_v4",
    "api_me",
    "api_my_incidents",
    "api_record_location_v2",
    "api_set_duty_status",
    "api_units",
    "api_update_incident_status",
    "api_update_my_assignment_status",
    "api_update_my_incident",
}
transport_names = re.findall(r'@POST\("bridge/rpc/(api_[a-z0-9_]+)"\)', api)
allowlist_match = re.search(
    r"'bridge_rpc_allowlist'\s*=>\s*\[(.*?)\]",
    bridge_config,
    re.IGNORECASE | re.DOTALL,
)
allowlist_names = re.findall(
    r"'(api_[a-z0-9_]+)'", allowlist_match.group(1)
) if allowlist_match else []
transport_set = set(transport_names)
allowlist_set = set(allowlist_names)

checks = {
    "Render bridge is opt-in in debug builds":
        'providers.gradleProperty("useRenderBridge").orNull == "true"' in gradle,
    "Render bridge canary requires real Supabase mode":
        'if (useRenderBridgeInDebug && !useSupabaseInDebug)' in gradle,
    "release builds keep the bridge disabled until separately verified":
        'buildConfigField("boolean", "USE_RENDER_BRIDGE", "false")' in gradle,
    "AppContainer passes the bridge build flag to the adapter":
        "useRenderBridge = AppConfig.USE_RENDER_BRIDGE" in container,
    "Android transport list is exactly the reviewed 14-RPC inventory":
        len(transport_names) == len(expected_rpcs)
        and transport_set == expected_rpcs
        and len(transport_names) == len(transport_set),
    "Render allowlist exactly matches the Android transport inventory":
        allowlist_match is not None
        and bool(allowlist_names)
        and len(allowlist_names) == len(allowlist_set)
        and allowlist_set == transport_set,
    "all application data methods use bridge when the opt-in is enabled":
        all(
            re.search(
                rf"if \(bridgeRpc != null\) bridgeRpc\.{method}\(",
                api,
            )
            for method in (
                "me", "setDutyStatus", "myIncidents", "allIncidents", "units",
                "activeResponderLocations", "createIncidentV4", "cancelMyIncident",
                "updateMyIncident", "updateIncidentStatus",
                "updateMyAssignmentStatus", "assignIncident", "assignIncidentTeam",
                "recordLocation",
            )
        ),
    "no nullable-result Elvis fallback can retry a bridge call against Supabase":
        not re.search(r"bridgeRpc\?\.\w+\([^;\n]*\)\s*\?:\s*rpc\.", api),
    "Supabase Auth continues to use Supabase endpoints":
        'baseUrl(AppConfig.SUPABASE_URL.trimEnd(\'/\') + "/")' in api
        and '@POST("auth/v1/token")' in api
        and '@POST("auth/v1/signup")' in api,
    "fresh explicit auth token is preserved over cached session token":
        "withStoredSessionAuthorization(request.build(), tokenProvider)" in api
        and 'if (!original.header("Authorization").isNullOrBlank()) return original' in api
        and 'tokenProvider()?.takeIf { it.isNotBlank() }' in api,
    "bridge requests forward caller bearer token and do not embed service role":
        "withStoredSessionAuthorization(request.build(), tokenProvider)" in api
        and "service_role" not in api.lower()
        and "SUPABASE_SERVICE_ROLE_KEY" not in api,
    "bridge errors are surfaced without catch-and-retry fallback":
        "catch (Exception)" not in api and "catch (Throwable)" not in api,
}
failed = [name for name, passed in checks.items() if not passed]
for name, passed in checks.items():
    print(f"{'PASS' if passed else 'FAIL'} {name}")
if failed:
    raise SystemExit(f"{len(failed)} Render bridge canary contract check(s) failed")
print(f"Render bridge canary contract OK: {len(checks)} checks.")
