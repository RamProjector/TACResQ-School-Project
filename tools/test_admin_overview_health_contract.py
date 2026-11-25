from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
ADMIN = (ROOT / "backend/web/src/pages/AdminConsole.tsx").read_text(encoding="utf-8")

# A fresh Overview probe must not leave the previous response looking current.
overview_probe = ADMIN.split('if (activeSection === "overview") {', 1)[1].split("try {", 1)[0]
assert "setDependencyHealth(null);" in overview_probe
assert "setDependencyHealthError(null);" in overview_probe
assert overview_probe.index("setDependencyHealth(null);") < overview_probe.index("setDependencyHealthLoading(true);")
assert overview_probe.index("setDependencyHealthError(null);") < overview_probe.index("setDependencyHealthLoading(true);")

# Probe failure must clear the snapshot and expose an explicit unavailable state.
assert "setDependencyHealth(null);" in overview_probe
assert "setDependencyHealthError(\"Dependency diagnostics are unavailable." in overview_probe
assert 'dependencyHealthLoading ? "Checking…"' in ADMIN
assert '"Not yet checked"' in ADMIN
assert '"Waiting for the first diagnostic check…"' in ADMIN

print("Admin Overview dependency freshness source contract passed.")
