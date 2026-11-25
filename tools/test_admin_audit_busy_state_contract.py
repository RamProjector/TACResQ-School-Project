from pathlib import Path

source = Path("backend/web/src/pages/AuditLogsPage.tsx").read_text()

page_start = source.index('<section className="audit-page"')
page_end = source.index('</section>', page_start)
page = source[page_start:page_end]

assert "aria-busy={loading}" in page, "Audit history must expose active query state"
assert 'className="audit-page__summary" role="status" aria-live="polite"' in source, "Audit query summary must remain politely announced"
assert 'role="alert"' in source, "Audit query failures must remain announced"
assert 'aria-busy={lifecycleLoading}' in source, "Lifecycle history must retain its independent busy state"

print("Admin audit query busy-state contract: passed")
