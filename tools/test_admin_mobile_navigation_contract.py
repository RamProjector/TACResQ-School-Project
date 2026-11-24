from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
CSS = (ROOT / "backend/web/src/pages/AdminConsole.css").read_text(encoding="utf-8")
TSX = (ROOT / "backend/web/src/pages/AdminConsole.tsx").read_text(encoding="utf-8")

mobile = CSS.split("@media (max-width:760px)", 1)[1]
mobile_hide = mobile.split("}", 1)[0]
assert ".admin-nav-item" not in mobile_hide or "display:none" not in mobile_hide
assert ".admin-nav-item { display:inline-flex;" in mobile
assert ".admin-navigation { flex-direction:row;" in CSS
assert "overflow-x:auto" in CSS
assert 'aria-label="Admin sections"' in TSX
assert 'aria-current={activeSection === id ? "page" : undefined}' in TSX

print("Admin mobile section navigation source contract passed.")
