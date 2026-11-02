#!/usr/bin/env python3
"""Static safety contracts for the local/private OSRM deployment package."""
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]
ROUTING = ROOT / "backend/routing"
compose = (ROUTING / "docker-compose.yml").read_text()
dockerfile = (ROUTING / "osrm/Dockerfile").read_text()
prepare = (ROUTING / "prepare-graph.sh").read_text()
smoke = (ROUTING / "smoke-test.sh").read_text()
readme = (ROUTING / "README.md").read_text()
gitignore = (ROUTING / ".gitignore").read_text()

required = {
    "pinned runtime image": "ghcr.io/project-osrm/osrm-backend:v26.10.0" in dockerfile,
    "loopback-only published port": '"127.0.0.1:${OSRM_PORT:-5000}:5000"' in compose,
    "read-only graph volume": '"${OSRM_GRAPH_DIR:-./graph}:/var/data:ro"' in compose,
    "read-only container": "read_only: true" in compose,
    "drop container capabilities": "cap_drop:" in compose and "- ALL" in compose,
    "stable graph prefix": "OSRM_GRAPH_PREFIX: /var/data/tacresq" in compose,
    "MLD extract": "osrm-extract -p /opt/car.lua" in prepare,
    "MLD partition": "osrm-partition /data/tacresq.osrm" in prepare,
    "MLD customize": "osrm-customize /data/tacresq.osrm" in prepare,
    "pinned preparation default": 'OSRM_IMAGE:-ghcr.io/project-osrm/osrm-backend:v26.10.0' in prepare,
    "local smoke default": 'OSRM_BASE_URL:-http://127.0.0.1:${OSRM_PORT:-5000}' in smoke,
    "positive route checks": 'route.get(field)' in smoke and 'value <= 0' in smoke,
    "PBF ignored": "*.osm.pbf" in gitignore and "*.osrm*" in gitignore,
    "ODbL documented": "Open Database License (ODbL)" in readme,
    "public router prohibited": "Do not use a public OSRM demo endpoint" in readme,
    "no production deployment": "No Render service, disk, environment variable, live migration, graph, or deployment is created" in readme,
}
for label, passed in required.items():
    assert passed, f"Missing OSRM deployment safety contract: {label}"

tracked = subprocess.run(
    ["git", "ls-files", "*.pbf", "*.osm.pbf", "*.osrm*"],
    cwd=ROOT,
    check=True,
    capture_output=True,
    text=True,
).stdout.strip()
assert not tracked, f"Generated routing artifacts must not be tracked: {tracked}"
print(f"PASS: private OSRM local deployment package satisfies {len(required)} safety contracts.")
