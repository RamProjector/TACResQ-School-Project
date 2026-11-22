#!/usr/bin/env python3
"""Guard the multi-layer TACResQ app-flow validation inventory.

This is a coverage-wiring test, not a substitute for running the referenced
Android, PostgreSQL, or live-environment tests.
"""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def read(path: str) -> str:
    file = ROOT / path
    assert file.is_file(), f"Required app-flow test file is missing: {path}"
    return file.read_text()


android_auth = read("app/src/androidTest/java/com/tacresq/app/AuthScreenInstrumentedTest.kt")
android_navigation = read("app/src/androidTest/java/com/tacresq/app/NavigationInstrumentedTest.kt")
claim_tests = read("app/src/test/java/com/tacresq/app/data/remote/FakeTacApiClaimIncidentTest.kt")
chat_tests = read("app/src/test/java/com/tacresq/app/data/remote/FakeTacApiChatTest.kt")
notification_tests = read("app/src/test/java/com/tacresq/app/ui/notifications/NotificationsViewModelTest.kt")
push_tests = read("app/src/test/java/com/tacresq/app/data/remote/FakeTacApiNotificationTest.kt")
image_tests = read("app/src/test/java/com/tacresq/app/data/remote/SupabaseIncidentImageUploadTest.kt")
auth_transport = read("app/src/test/java/com/tacresq/app/data/remote/SupabaseTacApiAuthorizationTest.kt")
gps_tests = read("app/src/test/java/com/tacresq/app/ui/GpsMapPresentationTest.kt")
replay = read("tools/test_supabase_migration_replay.sh")
ci = read(".github/workflows/ci.yml")
emulator = read(".github/workflows/android-emulator-manual.yml")
admin_contract = read("tools/test_admin_investigation_events_contract.py")

required = {
    "Android sign-in and registration UI": (
        android_auth,
        "signInScreenExplainsDemoModeAndShowsPrimaryAction",
        "registrationModeClearlyStatesRequesterOnlyIntent",
        "emptySignInShowsInlineRequiredFieldErrorsWithoutCallingBackend",
    ),
    "Requester SOS creation and active-request UI": (
        android_auth,
        "demoRequesterCanSubmitOneDemoSosAndSeesActiveRequestInsteadOfAnotherForm",
    ),
    "Requester, responder, driver, and dispatcher navigation": (
        android_navigation,
        "responderDriverAndDispatcherNavigationJourneysRemainRoleScoped",
        "requesterMessagesWithAnActiveRequestDoesNotCrashAndOpensConversation",
    ),
    "Responder/driver claim authorization and rejection": (
        claim_tests,
        "responderCanClaimAvailableIncidentWhenOnDuty",
        "claimFailsWhenOffDutyOrNotClaimable",
    ),
    "Incident chat privacy and retry/idempotency": (
        chat_tests,
        "imageMessagesArePrivateToIncidentParticipantsAndDownloadable",
        "committedMessageWithLostResponseRetriesAfterConversationReopenWithoutDuplicate",
        "incidentChatIsScopedAndClientRetriesAreIdempotent",
    ),
    "Image reservation, Storage upload order, and retry transport": (
        image_tests,
        "consumedReservationSkipsDuplicateStorageUploadAndFinalizesExistingMessage",
        "liveReservationUploadsBeforeFinalization",
    ),
    "Notifications, push deep links, and read state": (
        notification_tests,
        "pushTapLoadsExactNotificationAndRefreshesTheInbox",
        "markReadUpdatesFocusedNotificationOutsideInboxPage",
        "lookupForRemovedOrUnownedNotificationReturnsSafeNotFoundState",
    ),
    "Notification recipient scoping": (
        push_tests,
        "assignmentAndStatusNotificationsAreRecipientScopedAndReadIsIdempotent",
    ),
    "Authenticated Supabase transport/session behavior": (
        auth_transport,
        "cachedSessionTokenIsAddedWhenRequestHasNoAuthorizationHeader",
        "missingOrBlankCachedTokenDoesNotInventAuthorization",
    ),
    "GPS/map presentation": (
        gps_tests,
        "GpsMapPresentationTest",
    ),
    "Admin audit authorization/privacy contract": (
        admin_contract,
        "app_action_audit_logs",
        "private.tacresq_require_admin_mfa()",
        "Coverage boundaries",
    ),
}
for journey, (source, *markers) in required.items():
    for marker in markers:
        assert marker in source, f"{journey} is missing test/contract marker: {marker}"

database_suites = (
    "tools/test_incident_chat_storage_policies.sql",
    "tools/test_app_action_audit.sql",
    "tools/test_notification_rpc_security.sql",
    "tools/test_responder_incident_claiming.sql",
    "tools/test_dispatcher_release_assignment.sql",
    "tools/test_authenticated_requester_location_edit.sql",
    "tools/test_authenticated_incident_write_boundary.sql",
    "tools/test_authenticated_admin_user_list_boundary.sql",
    "tools/test_durable_sos_idempotency.sql",
)
for suite in database_suites:
    assert f"-f {suite}" in replay, f"PostgreSQL replay does not execute {suite}"

assert "python3 tools/test_app_flow_coverage.py" in ci
assert "bash tools/test_supabase_migration_replay.sh" in ci
assert "testDebugUnitTest" in ci
assert "connectedDebugAndroidTest" in emulator
assert "workflow_dispatch:" in emulator, "Physical/emulator UI suite must remain manually invoked"

print("PASS: TACResQ app-flow validation inventory is wired into CI.")
print(f"Covered journey groups: {len(required)}")
print(f"PostgreSQL integration suites required: {len(database_suites)}")
print("Limits: source wiring is not live Supabase acceptance, physical GPS testing, FCM delivery proof, or panel/user acceptance.")
