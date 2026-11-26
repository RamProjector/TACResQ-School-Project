#!/usr/bin/env python3
"""Static contract for complete, responsive Admin report summary metrics."""
from pathlib import Path

root = Path(__file__).resolve().parents[1]
page = (root / "backend/web/src/pages/AdminConsole.tsx").read_text(encoding="utf-8")
styles = (root / "backend/web/src/pages/AdminConsole.css").read_text(encoding="utf-8")

summary_start = page.index('<div className="admin-analytics-metrics">')
summary_end = page.index("</div>", summary_start)
summary = page[summary_start:summary_end]

# Every current status category is represented in the first summary row;
# status values are explicitly current-at-query-time, not historical totals.
for label, field in (
    ("TOTAL REQUESTS", "analytics.total_requests"),
    ("PENDING", "analytics.pending"),
    ("ACTIVE", "analytics.active"),
    ("RESOLVED", "analytics.resolved"),
    ("CANCELLED", "analytics.cancelled"),
):
    assert label in summary, f"Missing report summary metric: {label}"
    assert field in summary, f"Missing data binding for report metric: {field}"

assert "Currently awaiting assignment" in summary
assert "current status at query time" in page

# The five cards adapt to available width while preserving the established
# two-column mobile layout.
assert ".admin-analytics-metrics { display:grid; grid-template-columns:repeat(auto-fit,minmax(130px,1fr));" in styles
assert ".admin-analytics-metrics { grid-template-columns:repeat(2,minmax(0,1fr));" in styles

print("PASS: Admin report summary includes all current status categories and adapts responsively.")
