#!/usr/bin/env python3
"""Guard the distinction between account login, on-duty availability, and active assignments."""

from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
fake = (ROOT / "app/src/main/java/com/tacresq/app/data/remote/FakeTacApi.kt").read_text()
responder = (ROOT / "app/src/main/java/com/tacresq/app/ui/responder/ResponderScreen.kt").read_text()
dispatcher = (ROOT / "app/src/main/java/com/tacresq/app/ui/dispatcher/DispatcherScreen.kt").read_text()
team_sql = (ROOT / "supabase/migrations/20261122050813_harden_field_claim_exclusivity.sql").read_text()
duty_sql = (ROOT / "supabase/migrations/20261023081706_dispatch_team_assignment.sql").read_text()
profile_guard = (ROOT / "supabase/migrations/20261122050827_allow_dispatcher_progress_duty_sync.sql").read_text()

# Signing in must not silently put a field unit on duty.
assert 'DutyStatus.OFF_DUTY' in fake
assert 'AuthResponse(issueToken(account.user.id), account.user)' in fake
login_start = fake.index("override suspend fun login(")
login_end = fake.index("\n    override suspend fun register(", login_start)
assert "setDuty(" not in fake[login_start:login_end]
assert "setDutyStatus(" not in fake[login_start:login_end]

# Only explicit Available / Off duty choices are user-controlled in the demo API.
duty_start = fake.index("override suspend fun setDutyStatus(")
duty_end = fake.index("\n    override suspend fun myIncidents(", duty_start)
fake_duty = fake[duty_start:duty_end]
assert "me.role != Role.RESPONDER && me.role != Role.DRIVER" in fake_duty
assert "hasActiveIncident(me.id)" in fake_duty
assert "body.dutyStatus != DutyStatus.AVAILABLE" in fake_duty
assert "body.dutyStatus != DutyStatus.OFF_DUTY" in fake_duty

# The persistent API derives operational status from active team membership and
# refuses user duty changes while any active assignment reserves the member.
assert "'duty_status', coalesce((" in duty_sql
assert "FROM public.incident_assignments a JOIN public.incidents i ON i.id = a.incident_id" in duty_sql
assert "a.user_id = p.id AND i.status IN ('assigned','en_route','on_scene','transporting')" in duty_sql
assert "IF p_duty_status NOT IN ('available','off_duty')" in duty_sql
assert "UPDATE public.profiles SET duty_status = p_duty_status" in duty_sql
assert "p.duty_status = 'available'" in team_sql

# The profile guard covers team membership, not only the legacy primary assignee.
assert "FROM public.incident_assignments ia" in profile_guard
assert "ia.user_id = OLD.id" in profile_guard
assert "Finish the active incident before changing a field unit role or duty status" in profile_guard

# Dispatch can select only currently Available units; the UI reconciles an open
# selection against the latest successful availability snapshot.
assert "val availableUnits = state.units.filter { it.dutyStatus == DutyStatus.AVAILABLE }" in dispatcher
assert "selectedUnitIds = selectedUnitIds.intersect(currentlyAvailableIds)" in dispatcher
assert "state.assignmentDataFresh" in dispatcher

# Claimable incidents are relevant only to explicitly Available units; active-response
# states must not trigger claim discovery or be presented as claim-ready.
assert "val claimable = if (userDuty == DutyStatus.AVAILABLE)" in responder
assert "if (state.dutyStatus == DutyStatus.AVAILABLE && state.claimableIncidents.isNotEmpty())" in responder
assert "val claimableResult = if (me.isSuccess && userDuty == DutyStatus.AVAILABLE)" in responder
assert "claimableError = claimableError" in responder
assert "state.claimableError != null" in responder
assert "Claimable requests unavailable" in responder
assert "enabled = !state.busy && !state.refreshing && state.claimableError == null" in responder

# Field-unit duty controls are disabled until assignment state is freshly verified
# and remain unavailable while an active incident is assigned to the member.
assert "canChange = active.isEmpty() && !state.loading && !state.refreshing && state.syncError == null" in responder
assert "Status follows your incident progress." in responder

print("PASS: login does not imply on-duty availability.")
print("PASS: only explicit Available / Off duty changes are user-controlled.")
print("PASS: active team membership derives operational status and blocks duty changes.")
print("PASS: dispatch assignment uses the latest Available-unit snapshot.");
