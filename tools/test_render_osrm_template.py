#!/usr/bin/env python3
"""Static safety contracts for the non-applied Render OSRM template."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
template_path = ROOT / "render-osrm.example.yaml"
runbook_path = ROOT / "docs/RENDER_OSRM_DEPLOYMENT.md"
template = template_path.read_text()
runbook = runbook_path.read_text()

required = {
    "template is not an active Blueprint": template_path.name != "render.yaml",
    "private service": "type: pserv" in template,
    "Docker runtime": "runtime: docker" in template,
    "Singapore region": "region: singapore" in template,
    "single instance": "numInstances: 1" in template,
    "pinned graph mount": "mountPath: /var/data" in template and "OSRM_GRAPH_PREFIX" in template,
    "persistent graph disk": "sizeGB: 10" in template,
    "repository Dockerfile": "dockerfilePath: ./backend/routing/osrm/Dockerfile" in template,
    "no public URL": "public URL" in runbook and "Do not use the public `onrender.com` URL" in runbook,
    "free-tier limitation documented": "Free services cannot use persistent disks" in runbook,
    "no pre-deploy graph population": "Do not use a Render pre-deploy command to populate the graph" in runbook,
    "Laravel private hostname": "OSRM_BASE_URL=http://tacresq-osrm:5000" in runbook,
    "Android opt-in gate": "Enable Android route preview only" in runbook,
}
for label, passed in required.items():
    assert passed, f"Missing Render OSRM deployment safety contract: {label}"

for forbidden in ("SUPABASE_SERVICE_ROLE", "OSRM_API_KEY", "password:", "token:"):
    assert forbidden not in template, f"Template must not contain secret-like value: {forbidden}"

assert not (ROOT / "render.yaml").exists(), "Do not activate the paid service Blueprint implicitly"
print(f"PASS: Render OSRM template satisfies {len(required)} safety contracts.")
