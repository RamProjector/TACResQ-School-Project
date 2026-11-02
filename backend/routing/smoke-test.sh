#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${OSRM_BASE_URL:-http://127.0.0.1:${OSRM_PORT:-5000}}"
ORIGIN="${ROUTE_ORIGIN:-125.0030,11.2443}"
DESTINATION="${ROUTE_DESTINATION:-125.0200,11.2500}"
RESPONSE_FILE="$(mktemp)"
cleanup() { rm -f "$RESPONSE_FILE"; }
trap cleanup EXIT

if ! command -v curl >/dev/null 2>&1; then
  echo "curl is required for the OSRM smoke test." >&2
  exit 1
fi

curl --fail --silent --show-error --max-time 15 \
  "$BASE_URL/route/v1/driving/$ORIGIN;$DESTINATION?overview=false&steps=false" \
  -o "$RESPONSE_FILE"

python3 - "$RESPONSE_FILE" <<'PY'
import json
import math
import sys

with open(sys.argv[1], encoding="utf-8") as handle:
    body = json.load(handle)

if body.get("code") != "Ok":
    raise SystemExit(f"OSRM returned code {body.get('code')!r}")
routes = body.get("routes")
if not isinstance(routes, list) or not routes:
    raise SystemExit("OSRM returned no route for the synthetic in-area request")
route = routes[0]
for field in ("distance", "duration"):
    value = route.get(field)
    if not isinstance(value, (int, float)) or not math.isfinite(value) or value <= 0:
        raise SystemExit(f"OSRM returned an invalid {field}")
print(f"PASS: private OSRM returned a route ({route['distance']:.0f} m, {route['duration']:.0f} s).")
PY
