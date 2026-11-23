#!/usr/bin/env python3
"""Ensure the read-only authorization catalog does not omit PostgreSQL bypass surfaces."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
inventory = (ROOT / "tools/check_account_suspension_authorization_surface.sql").read_text(encoding="utf-8").lower()
crosswalk = (ROOT / "docs/security/admin-account-suspension-callable-definer-crosswalk.md").read_text(encoding="utf-8").lower()
review = (ROOT / "docs/security/admin-account-suspension-rpc-review-batch-20-invoker-and-private-callables.md").read_text(encoding="utf-8").lower()

# Client-callable routines must be visible in both exposed and private schemas,
# with their effective execution mode and grants.
assert "authenticated-callable public functions" in inventory
assert "authenticated-callable private functions" in inventory
assert "has_function_privilege('authenticated', p.oid, 'execute')" in inventory
assert "p.prosecdef" in inventory
assert "p.prosrc" in inventory

# A complete authorization review cannot stop at RPCs: views, triggers,
# column grants, sequences, schema grants, default ACLs and role inheritance
# can all create alternate access paths or future regressions.
for required in (
    "authenticated-readable public views and materialized views",
    "enabled user-defined triggers on public tables",
    "auth-schema triggers",
    "effective column-level privileges",
    "effective client privileges on public sequences",
    "client schema usage privileges",
    "global and schema-scoped default privileges",
    "client membership in superuser or bypassrls roles",
    "all private security definer functions",
):
    assert required in inventory, f"Authorization catalog omits: {required}"

assert "pg_get_viewdef" in inventory
assert "pg_get_triggerdef" in inventory
assert "has_column_privilege" in inventory
assert "has_sequence_privilege" in inventory
assert "pg_has_role" in inventory
assert "pg_default_acl" in inventory

# The crosswalk is deliberately honest about the difference between listing
# known SECURITY DEFINER signatures and reviewing every nested call path.
assert "20 public" in crosswalk
assert "52 authenticated-callable public security invoker functions" in crosswalk
assert "not an automated proof" in crosswalk or "not proof" in crosswalk
assert "call-graph" in review
print("Account-lifecycle callable inventory contract passed.")
