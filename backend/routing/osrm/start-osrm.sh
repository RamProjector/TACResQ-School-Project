#!/bin/sh
set -eu

GRAPH_PREFIX="${OSRM_GRAPH_PREFIX:-/var/data/tacresq}"
if [ ! -f "${GRAPH_PREFIX}.osrm" ]; then
  echo "OSRM graph is missing at ${GRAPH_PREFIX}.osrm; attach and populate the persistent graph volume before starting." >&2
  exit 1
fi

exec osrm-routed --algorithm mld --port "${PORT:-5000}" "${GRAPH_PREFIX}.osrm"
