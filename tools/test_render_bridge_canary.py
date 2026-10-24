#!/usr/bin/env python3
"""Guard the opt-in Render bridge canary against accidental full-client cutover."""
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
gradle = (ROOT / "app/build.gradle.kts").read_text()
container = (ROOT / "app/src/main/java/com/tacresq/app/AppContainer.kt").read_text()
api = (ROOT / "app/src/main/java/com/tacresq/app/data/remote/SupabaseTacApi.kt").read_text()
bridge_config = (ROOT / "backend/src/config/tacru.php").read_text()

bridge_transport_names = re.findall(r'@POST\("bridge/rpc/(api_[a-z0-9_]+)"\)', api)
allowlist_match = re.search(
    r"'bridge_rpc_allowlist'\s*=>\s*\[(.*?)\]",
    bridge_config,
    re.IGNORECASE | re.DOTALL,
)
allowlist_names = re.findall(r"'(api_[a-z0-9_]+)'", allowlist_match.group(1)) if allowlist_match else []
allowlist_set = set(allowlist_names)
transport_set = set(bridge_transport_names)

checks = {
    "Render bridge canary is explicitly opt-in":
        'providers.gradleProperty("useRenderBridge").orNull == "true"' in gradle,
    "server bridge allowlist is explicit and has no duplicate entries":
        allowlist_match is not None and bool(allowlist_names) and len(allowlist_names) == len(allowlist_set),
    "every Android bridge transport is explicitly allowlisted by Render":
        bool(transport_set) and transport_set <= allowlist_set,
    "bridge route declarations use RPC identifiers":
        all(name.startswith("api_") for name in bridge_transport_names),
    "service-role credentials are not embedded in the Android bridge adapter":
        "service_role" not in api.lower() and "service-role" not in api.lower(),
    "Render bridge canary requires real Supabase mode":
        'if (useRenderBridgeInDebug && !useSupabaseInDebug)' in gradle,
    "release builds keep the bridge disabled":
        'buildConfigField("boolean", "USE_RENDER_BRIDGE", "false")' in gradle,
    "AppContainer passes the build flag to the Supabase adapter":
        "useRenderBridge = AppConfig.USE_RENDER_BRIDGE" in container,
    "only four currently allowlisted RPCs have a bridge transport":
        all(f'@POST("bridge/rpc/{name}")' in api for name in (
            "api_units",
            "api_create_incident_v4",
            "api_cancel_my_incident",
            "api_update_my_incident",
        ))
        and api.count('@POST("bridge/rpc/') == 4,
    "client bridge paths match the server allowlist":
        all(f"'{name}'" in bridge_config for name in (
            "api_units",
            "api_create_incident_v4",
            "api_cancel_my_incident",
            "api_update_my_incident",
        )),
    "unsupported RPCs remain direct to Supabase":
        "rpc.myIncidents(EmptyRpcBody())" in api
        and "rpc.allIncidents(EmptyRpcBody())" in api
        and "rpc.setDutyStatus(" in api
        and "rpc.activeResponderLocations(EmptyRpcBody())" in api
        and "rpc.updateIncidentStatus(" in api
        and "rpc.updateMyAssignmentStatus(" in api
        and "rpc.assignIncidentTeam(" in api
        and "rpc.recordLocation(" in api,
    "auth continues using Supabase endpoints":
        'baseUrl(AppConfig.SUPABASE_URL.trimEnd(\'/\') + "/")' in api
        and '@POST("auth/v1/token")' in api
        and '@POST("auth/v1/signup")' in api,
    "bridge uses the caller bearer token":
        '.header("Authorization", "Bearer $it")' in api,
    "write errors do not silently trigger direct-Supabase fallback":
        "return if (bridgeRpc != null) bridgeRpc.createIncidentV4(request) else rpc.createIncidentV4(request)" in api,
}
failed = [name for name, passed in checks.items() if not passed]
for name, passed in checks.items():
    print(f"{'PASS' if passed else 'FAIL'} {name}")
if failed:
    raise SystemExit(f"{len(failed)} Render bridge canary contract check(s) failed")
print(f"Render bridge canary contract OK: {len(checks)} checks.")
