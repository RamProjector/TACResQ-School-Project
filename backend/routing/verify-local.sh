#!/usr/bin/env bash
set -euo pipefail

ROUTING_DIR="$(cd "$(dirname "$0")" && pwd)"
COMPOSE=(docker compose --project-directory "$ROUTING_DIR")
BASE_URL="${OSRM_BASE_URL:-http://127.0.0.1:${OSRM_PORT:-5000}}"
GRAPH_DIR="${OSRM_GRAPH_DIR:-$ROUTING_DIR/graph}"

if ! command -v docker >/dev/null 2>&1; then
  echo "Docker is required for local OSRM verification." >&2
  exit 1
fi
if [[ ! -f "$GRAPH_DIR/tacresq.osrm" ]]; then
  echo "Missing $GRAPH_DIR/tacresq.osrm; prepare an approved graph first." >&2
  exit 2
fi

cleanup() {
  "${COMPOSE[@]}" down --volumes --remove-orphans >/dev/null 2>&1 || true
}
trap cleanup EXIT

"${COMPOSE[@]}" config --quiet
"${COMPOSE[@]}" up --build --detach

ready=0
for _ in $(seq 1 30); do
  if curl --fail --silent --max-time 2 "$BASE_URL/route/v1/driving/125.0030,11.2443;125.0200,11.2500?overview=false&steps=false" >/dev/null; then
    ready=1
    break
  fi
  sleep 2
done
if [[ "$ready" != 1 ]]; then
  echo "OSRM did not become ready within 60 seconds." >&2
  "${COMPOSE[@]}" logs --no-color >&2 || true
  exit 1
fi

"$ROUTING_DIR/smoke-test.sh"

logs="$(${COMPOSE[@]} logs --no-color)"
if grep -q '/route/v1/driving/' <<<"$logs"; then
  echo "OSRM logs contain a coordinate-bearing route URL; refuse verification." >&2
  exit 1
fi
if grep -Eiq '127\.0\.0\.1,[0-9]|125\.0[0-9]+,11\.' <<<"$logs"; then
  echo "OSRM logs contain coordinate-like data; refuse verification." >&2
  exit 1
fi

echo "PASS: local private OSRM graph, route response, and log privacy checks passed."
