from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SOURCE = (ROOT / "backend/web/src/pages/AdminConsole.tsx").read_text(encoding="utf-8")

start = SOURCE.index("function exportAnalyticsCsv(")
end = SOURCE.index("\nfunction initials(", start) if "\nfunction initials(" in SOURCE[start:] else SOURCE.index("\nexport default function AdminConsole", start)
exporter = SOURCE[start:end]

assert "new Blob([csv], { type: \"text/csv;charset=utf-8;\" })" in exporter
assert "URL.createObjectURL(blob)" in exporter
assert "link.download" in exporter
assert "replaceAll('\\\"', '\\\\"\\\"')" in exporter or "replaceAll('\\"', '\\"\\"')" in exporter
assert "summary.total_requests" in exporter
assert "summary.average_assignment_minutes" in exporter
assert "summary.daily_requests.map" in exporter
assert "requester_name" not in exporter
assert "requester_phone" not in exporter
assert "latitude" not in exporter and "longitude" not in exporter

assert 'aria-label="Export incident performance report as CSV"' in SOURCE
assert "disabled={analyticsLoading || !analytics || Boolean(analyticsError)}" in SOURCE
assert "if (analytics) exportAnalyticsCsv(analytics)" in SOURCE

print("Admin analytics CSV export source contract passed.")
