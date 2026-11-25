from pathlib import Path

source = Path("backend/web/src/pages/AuditLogsPage.tsx").read_text()

assert '<span aria-live="polite" aria-atomic="true">Page {lifecyclePage + 1} of {lifecyclePageCount}' in source
assert '<span aria-live="polite" aria-atomic="true">Showing {rangeStart}–{rangeEnd} of {total.toLocaleString()}' in source
assert 'aria-label="Account lifecycle history pagination"' in source
assert 'aria-label="Investigation history pagination"' in source
assert 'aria-current={pageIndex === page ? "page" : undefined}' in source

print("Audit pagination announcement contract: passed")
