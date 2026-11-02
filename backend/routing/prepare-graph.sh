#!/usr/bin/env bash
set -euo pipefail

IMAGE="${OSRM_IMAGE:-ghcr.io/project-osrm/osrm-backend:v26.10.0}"
PBF_PATH="${1:-}"
OUTPUT_DIR="${2:-$(cd "$(dirname "$0")" && pwd)/graph}"

if [[ -z "$PBF_PATH" || ! -f "$PBF_PATH" ]]; then
  echo "Usage: $0 /path/to/bounded-area.osm.pbf [output-graph-dir]" >&2
  exit 2
fi
if ! command -v docker >/dev/null 2>&1; then
  echo "Docker is required to prepare the OSRM graph." >&2
  exit 1
fi

PBF_PATH="$(realpath "$PBF_PATH")"
OUTPUT_DIR="$(realpath -m "$OUTPUT_DIR")"
mkdir -p "$OUTPUT_DIR"
BUILD_DIR="$(mktemp -d "$OUTPUT_DIR/.graph-build.XXXXXX")"
cleanup() { rm -rf "$BUILD_DIR"; }
trap cleanup EXIT

# Use a stable graph prefix so the runtime container can mount the output as
# /var/data/tacresq.osrm. The source PBF is copied only to the ignored build dir.
cp -- "$PBF_PATH" "$BUILD_DIR/tacresq.osm.pbf"

run_osrm() {
  docker run --rm \
    --user "$(id -u):$(id -g)" \
    -v "$BUILD_DIR:/data" \
    "$IMAGE" "$@"
}

run_osrm osrm-extract -p /opt/car.lua /data/tacresq.osm.pbf
run_osrm osrm-partition /data/tacresq.osrm
run_osrm osrm-customize /data/tacresq.osrm

rm -f "$BUILD_DIR/tacresq.osm.pbf"
find "$BUILD_DIR" -maxdepth 1 -type f -name 'tacresq.osrm*' -exec mv -f {} "$OUTPUT_DIR/" \;

echo "Prepared OSRM driving graph in $OUTPUT_DIR"
echo "Next: run (cd backend/routing && docker compose up --build)"
