from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SECURITY = (ROOT / "backend/web/src/components/AdminSecurityStatus.tsx").read_text(encoding="utf-8")
ADMIN = (ROOT / "backend/web/src/pages/AdminConsole.tsx").read_text(encoding="utf-8")
CSS = (ROOT / "backend/web/src/pages/AdminConsole.css").read_text(encoding="utf-8")

# MFA management must use the defined method parser and require AAL2.
assert 'activeSection === "security" ? "Review your current administrator session' in ADMIN
assert 'activeSection === "analytics" ? "Review request volume' in ADMIN
assert ".admin-security-status__factor-list li > span:last-child strong" in CSS
assert "@media (max-width:560px)" in CSS

assert "function hasTotpMethod(" in SECURITY
assert 'aal.currentLevel === "aal2" && hasTotpMethod(aal)' in SECURITY
assert 'data.currentLevel === "aal2" && hasTotpMethod(data)' in SECURITY
assert "hasTotpAal2" not in SECURITY

# The manual authenticator secret is hidden by default and explicitly revealed.
assert 'type={showEnrollmentSecret ? "text" : "password"}' in SECURITY
assert 'aria-label={showEnrollmentSecret ? "Hide manual setup key" : "Show manual setup key"}' in SECURITY
assert "Treat it like a password and do not share it." in SECURITY
assert ".admin-security-status__secret-field" in CSS

# The chart has a tabular alternative for precise values and assistive technology.
assert 'className="admin-analytics-data"' in ADMIN
assert "Daily request counts for the selected reporting period" in ADMIN
assert 'scope="row"' in ADMIN
assert ".admin-analytics-data__table-wrap" in CSS

print("Admin MFA and analytics UI source contract passed.")

assert "const [lastCheckedAt, setLastCheckedAt]" in SECURITY
assert "setLastCheckedAt(new Date().toISOString())" in SECURITY
assert "setSnapshot(null);" in SECURITY
assert "setLastCheckedAt(null);" in SECURITY
assert "Do not present the previous session/factor inventory as current" in SECURITY
assert "Last checked " in SECURITY and "Asia/Manila" in SECURITY
assert ".admin-security-status__checked" in CSS


# A security refresh clears stale action feedback and confirmation prompts
# before the new Supabase Auth snapshot is requested.
security_refresh = SECURITY.split("const refresh = useCallback(async () => {", 1)[1].split("}, []);", 1)[0]
assert security_refresh.index("setActionError(null);") < security_refresh.index("setSnapshot(null);")
assert security_refresh.index("setNotice(null);") < security_refresh.index("setSnapshot(null);")
assert security_refresh.index("setConfirmRemoveFactorId(null);") < security_refresh.index("setSnapshot(null);")
assert security_refresh.index("setConfirmGlobalSignOut(false);") < security_refresh.index("setSnapshot(null);")


# Analytics data and export feedback are invalidated synchronously when a
# period change or manual refresh is requested.
assert "function reloadAnalyticsReport(days: 7 | 30 | 90 = analyticsDays)" in ADMIN
analytics_reload = ADMIN.split("function reloadAnalyticsReport(", 1)[1].split("\n  }", 1)[0]
assert analytics_reload.index("setAnalytics(null);") < analytics_reload.index("setAnalyticsRefreshVersion(")
assert analytics_reload.index("setAnalyticsLoadedAt(null);") < analytics_reload.index("setAnalyticsRefreshVersion(")
assert analytics_reload.index("setAnalyticsExportNotice(null);") < analytics_reload.index("setAnalyticsRefreshVersion(")
assert 'onChange={(event) => reloadAnalyticsReport(Number(event.target.value) as 7 | 30 | 90)}' in ADMIN
assert 'onClick={() => reloadAnalyticsReport()}' in ADMIN


# Successful authenticator lifecycle actions refresh the factor snapshot before
# publishing their success notice, because refresh intentionally clears notices.
verify_action = SECURITY.split("async function verifyBackupAuthenticator", 1)[1].split("\n  async function signOutAllDevices", 1)[0]
assert verify_action.index("await refresh();") < verify_action.index('setNotice("Backup authenticator verified and added.");')
remove_action = SECURITY.split("async function removeAuthenticatorFactor", 1)[1].split("\n  async function cancelBackupEnrollment", 1)[0]
assert remove_action.index("await refresh();") < remove_action.index('setNotice(factor.name + " was removed.");')
cancel_action = SECURITY.split("async function cancelBackupEnrollment", 1)[1].split("\n  return (", 1)[0]
assert cancel_action.index("await refresh();") < cancel_action.index('setNotice("Unverified backup enrollment cancelled.");')


# Unsaved browser-local preferences are protected from accidental navigation
# and page unload. Explicitly saving clears the dirty state.
assert "function confirmDiscardPreferenceChanges()" in ADMIN
assert "if (!preferencesDirty) return true;" in ADMIN
assert "window.confirm(" in ADMIN
assert "window.addEventListener(\"beforeunload\", warnBeforeUnload);" in ADMIN
assert "window.removeEventListener(\"beforeunload\", warnBeforeUnload)" in ADMIN
assert "if (section !== activeSection && !confirmDiscardPreferenceChanges()) return;" in ADMIN
assert "if (page !== settingsPage && !confirmDiscardPreferenceChanges()) return;" in ADMIN
assert "setSavedPreferences(preferences);" in ADMIN


# Confirming discard restores the last saved preference values immediately.
discard_helper = ADMIN.split("function confirmDiscardPreferenceChanges()", 1)[1].split("\n  useEffect", 1)[0]
assert "const discard = window.confirm(" in discard_helper
assert "if (!discard) return false;" in discard_helper
assert discard_helper.index("setPreferences(savedPreferences);") < discard_helper.index("return true;")
assert "setPreferencesError(null);" in discard_helper
