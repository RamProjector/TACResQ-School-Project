from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SOURCE = (ROOT / "backend/web/src/pages/AdminConsole.tsx").read_text(encoding="utf-8")

start = SOURCE.index("function exportAnalyticsCsv(")
end = SOURCE.index("\nfunction initials(", start) if "\nfunction initials(" in SOURCE[start:] else SOURCE.index("\nexport default function AdminConsole", start)
exporter = SOURCE[start:end]

assert "new Blob([csv], { type: \"text/csv;charset=utf-8;\" })" in exporter
assert "URL.createObjectURL(blob)" in exporter
assert "link.download" in exporter
assert "replaceAll" in exporter
assert "summary.total_requests" in exporter
assert "summary.average_assignment_minutes" in exporter
assert "summary.daily_requests.map" in exporter
assert "requester_name" not in exporter
assert "requester_phone" not in exporter
assert "latitude" not in exporter and "longitude" not in exporter

assert 'aria-label="Export incident performance report as CSV"' in SOURCE
assert "disabled={analyticsLoading || !analytics || Boolean(analyticsError)}" in SOURCE
assert "const filename = exportAnalyticsCsv(analytics)" in SOURCE

assert "setAnalyticsLoadedAt(new Date().toISOString())" in SOURCE
assert '"Updated "' in SOURCE and '"Asia/Manila"' in SOURCE
assert "role=\"status\" aria-live=\"polite\"" in SOURCE
assert "setAnalyticsExportNotice(\"Export prepared: \" + filename)" in SOURCE
assert "window.setTimeout(() => URL.revokeObjectURL(objectUrl), 1000)" in exporter

print("Admin analytics CSV export source contract passed.")


# Spreadsheet applications may evaluate formula-like text even inside quoted CSV
# fields. Keep numeric metrics numeric while neutralizing untrusted text cells.
assert "function escapeSpreadsheetText(value: string)" in SOURCE
assert 'return /^[\\s\\u0000-\\u001f]*[=+@-]/.test(value) ? "\'" + value : value;' in SOURCE
assert 'typeof value === "string" ? escapeSpreadsheetText(value) : String(value)' in exporter


# The analytics failure state offers an in-context retry and avoids claiming
# every failure means the endpoint is undeployed.
assert "Retry report" in SOURCE
assert 'onClick={() => reloadAnalyticsReport()}' in SOURCE
assert "Check your connection and access, then retry." in SOURCE
assert "ask the system maintainer to verify that the analytics service is deployed and reachable." in SOURCE
assert "The endpoint must be deployed before this report can display live data." not in SOURCE
