from pathlib import Path

source = Path("backend/web/src/pages/AdminConsole.tsx").read_text()

# The privacy workspace must explain data-subject rights and a cautious
# interim handling procedure without pretending a live request service exists.
for marker in (
    "Data subject rights and request handling",
    "Not verified in this release",
    "Not connected — no online submission or case tracking",
    "Not verified — no retention controls exposed",
    "Rights to recognize",
    "Be informed and access:",
    "Rectification:",
    "Erasure or blocking:",
    "Object and data portability:",
    "Complaint and damages:",
    "Interim handling procedure — manual, not an online workflow",
    "minimum details",
    "verify the requester’s identity",
    "authorized privacy decision-maker",
    "Release gates for an online request centre",
    "per-request authorization",
    "The aggregate Reports CSV is not a personal-data access or portability response.",
    "https://privacy.gov.ph/data-subject-rights/",
):
    assert marker in source, f"Missing privacy workspace safeguard/content: {marker}"

# No user-facing action may imply that the Admin portal can fulfill rights
# requests until an approved backend workflow exists.
for forbidden in (
    "Submit data request",
    "Delete personal data",
    "Export personal data",
    "Approve erasure",
):
    assert forbidden not in source, f"Unverified privacy action exposed: {forbidden}"

print("PASS: Admin privacy guidance, manual handling boundary, and online-workflow release gates are present.")
