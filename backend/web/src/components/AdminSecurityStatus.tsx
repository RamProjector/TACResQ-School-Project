import { useCallback, useEffect, useRef, useState } from "react";
import type { FormEvent } from "react";
import { AlertCircle, CheckCircle2, Clock3, LogOut, RefreshCw, ShieldCheck, ShieldX } from "lucide-react";
import { getSupabase } from "@/lib/supabase";

type SecurityFactor = { id: string; name: string; status: string };
type SecuritySnapshot = {
  currentLevel: string | null;
  usedTotp: boolean;
  factors: SecurityFactor[];
};
type Enrollment = { factorId: string; qrCode: string; secret: string };

function formatLevel(level: string | null) {
  if (level === "aal2") return "Elevated (AAL2)";
  if (level === "aal1") return "Password session (AAL1)";
  return "Unavailable";
}

export default function AdminSecurityStatus() {
  const [snapshot, setSnapshot] = useState<SecuritySnapshot | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [enrollment, setEnrollment] = useState<Enrollment | null>(null);
  const [verificationCode, setVerificationCode] = useState("");
  const [busy, setBusy] = useState(false);
  const [actionError, setActionError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [confirmGlobalSignOut, setConfirmGlobalSignOut] = useState(false);
  const [confirmRemoveFactorId, setConfirmRemoveFactorId] = useState<string | null>(null);
  const refreshVersion = useRef(0);
  const pendingEnrollmentId = useRef<string | null>(null);

  const refresh = useCallback(async () => {
    const requestId = ++refreshVersion.current;
    setLoading(true);
    setError(null);
    try {
      const auth = getSupabase().auth;
      const [aalResult, factorsResult] = await Promise.all([
        auth.mfa.getAuthenticatorAssuranceLevel(),
        auth.mfa.listFactors(),
      ]);
      if (aalResult.error) throw aalResult.error;
      if (factorsResult.error) throw factorsResult.error;
      const methods = aalResult.data.currentAuthenticationMethods ?? [];
      const usedTotp = methods.some((method) =>
        typeof method === "string" ? method === "totp" :
          typeof method === "object" && method !== null && method.method === "totp"
      );
      if (requestId !== refreshVersion.current) return;
      setSnapshot({
        currentLevel: aalResult.data.currentLevel ?? null,
        usedTotp,
        factors: factorsResult.data.all.filter((factor) => factor.factor_type === "totp").map((factor) => ({
          id: factor.id,
          name: factor.friendly_name || "Authenticator app",
          status: factor.status,
        })),
      });
    } catch (cause) {
      if (requestId !== refreshVersion.current) return;
      setError(cause instanceof Error ? cause.message : "Could not retrieve the current MFA status.");
      setSnapshot(null);
    } finally {
      if (requestId === refreshVersion.current) setLoading(false);
    }
  }, []);

  useEffect(() => {
    void refresh();
    return () => {
      // Ignore a response from an earlier request after unmount or effect cleanup.
      refreshVersion.current += 1;
    };
  }, [refresh]);

  useEffect(() => () => {
    // Navigating away must not leave an abandoned, unverified factor behind.
    // This is best-effort cleanup; Supabase remains the authority on factor state.
    const factorId = pendingEnrollmentId.current;
    if (factorId) void getSupabase().auth.mfa.unenroll({ factorId });
  }, []);

  const verifiedFactors = snapshot?.factors.filter((factor) => factor.status === "verified") ?? [];
  const pendingFactors = snapshot?.factors.filter((factor) => factor.status !== "verified") ?? [];
  const canManageFactors = snapshot?.currentLevel === "aal2" && snapshot.usedTotp === true;

  async function addBackupAuthenticator() {
    if (busy || enrollment || !snapshot || snapshot.factors.length >= 10) return;
    setBusy(true);
    setActionError(null);
    setNotice(null);
    try {
      // Do not rely on the status snapshot rendered earlier; require a fresh
      // TOTP-verified AAL2 session immediately before creating another factor.
      const auth = getSupabase().auth;
      const { data: aal, error: aalError } = await auth.mfa.getAuthenticatorAssuranceLevel();
      if (aalError) throw aalError;
      const methods = aal.currentAuthenticationMethods ?? [];
      const usedTotp = methods.some((method) =>
        typeof method === "string" ? method === "totp" :
          typeof method === "object" && method !== null && method.method === "totp"
      );
      if (aal.currentLevel !== "aal2" || !usedTotp) {
        throw new Error("Verify your authenticator again before adding a backup device.");
      }
      const existingNames = new Set(snapshot.factors.map((factor) => factor.name));
      let suffix = 1;
      while (existingNames.has(`TACResQ Backup ${suffix}`)) suffix += 1;
      const { data, error: enrollError } = await getSupabase().auth.mfa.enroll({
        factorType: "totp",
        friendlyName: `TACResQ Backup ${suffix}`,
      });
      if (enrollError) throw enrollError;
      pendingEnrollmentId.current = data.id;
      setEnrollment({ factorId: data.id, qrCode: data.totp.qr_code, secret: data.totp.secret });
      setVerificationCode("");
    } catch (cause) {
      setActionError(cause instanceof Error ? cause.message : "A backup authenticator could not be started.");
    } finally {
      setBusy(false);
    }
  }

  async function verifyBackupAuthenticator(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!enrollment || !/^\d{6}$/.test(verificationCode) || busy) return;
    setBusy(true);
    setActionError(null);
    try {
      const auth = getSupabase().auth;
      const { error: verifyError } = await auth.mfa.challengeAndVerify({
        factorId: enrollment.factorId,
        code: verificationCode,
      });
      if (verifyError) throw verifyError;
      const { data, error: aalError } = await auth.mfa.getAuthenticatorAssuranceLevel();
      if (aalError) throw aalError;
      const methods = data.currentAuthenticationMethods ?? [];
      const usedTotp = methods.some((method) =>
        typeof method === "string" ? method === "totp" :
          typeof method === "object" && method !== null && method.method === "totp"
      );
      if (data.currentLevel !== "aal2" || !usedTotp) {
        throw new Error("The backup authenticator was not confirmed for this session. Please try again.");
      }
      pendingEnrollmentId.current = null;
      setEnrollment(null);
      setVerificationCode("");
      setNotice("Backup authenticator verified and added.");
      await refresh();
    } catch (cause) {
      setActionError(cause instanceof Error ? cause.message : "The authenticator code could not be verified.");
    } finally {
      setBusy(false);
    }
  }

  async function signOutAllDevices() {
    if (busy) return;
    setBusy(true);
    setActionError(null);
    setNotice(null);
    try {
      const auth = getSupabase().auth;
      const { data, error: aalError } = await auth.mfa.getAuthenticatorAssuranceLevel();
      if (aalError) throw aalError;
      const methods = data.currentAuthenticationMethods ?? [];
      const usedTotp = methods.some((method) =>
        typeof method === "string" ? method === "totp" :
          typeof method === "object" && method !== null && method.method === "totp"
      );
      if (data.currentLevel !== "aal2" || !usedTotp) {
        setConfirmGlobalSignOut(false);
        throw new Error("Verify your authenticator again before revoking all sessions.");
      }
      const { error: signOutError } = await auth.signOut({ scope: "global" });
      if (signOutError) throw signOutError;
      // A successful global sign-out clears this browser session as well.
    } catch (cause) {
      setActionError(cause instanceof Error ? cause.message : "All-device sign-out could not be completed.");
      setBusy(false);
    }
  }

  async function removeAuthenticatorFactor(factor: SecurityFactor) {
    if (busy) return;
    if (factor.status === "verified" && verifiedFactors.length <= 1) {
      setActionError("Keep at least one verified authenticator registered for administrator access.");
      setConfirmRemoveFactorId(null);
      return;
    }
    setBusy(true);
    setActionError(null);
    setNotice(null);
    try {
      const auth = getSupabase().auth;
      const { data: aal, error: aalError } = await auth.mfa.getAuthenticatorAssuranceLevel();
      if (aalError) throw aalError;
      const methods = aal.currentAuthenticationMethods ?? [];
      const usedTotp = methods.some((method) =>
        typeof method === "string" ? method === "totp" :
          typeof method === "object" && method !== null && method.method === "totp"
      );
      if (aal.currentLevel !== "aal2" || !usedTotp) {
        throw new Error("Verify your authenticator again before removing a factor.");
      }
      const { error: unenrollError } = await auth.mfa.unenroll({ factorId: factor.id });
      if (unenrollError) throw unenrollError;
      if (pendingEnrollmentId.current === factor.id) pendingEnrollmentId.current = null;
      if (enrollment?.factorId === factor.id) {
        setEnrollment(null);
        setVerificationCode("");
      }
      setConfirmRemoveFactorId(null);
      setNotice(factor.name + " was removed.");
      await refresh();
    } catch (cause) {
      setActionError(cause instanceof Error ? cause.message : "The authenticator factor could not be removed.");
    } finally {
      setBusy(false);
    }
  }

  async function cancelBackupEnrollment() {
    if (!enrollment || busy) return;
    setBusy(true);
    setActionError(null);
    try {
      const { error: unenrollError } = await getSupabase().auth.mfa.unenroll({ factorId: enrollment.factorId });
      if (unenrollError) throw unenrollError;
      pendingEnrollmentId.current = null;
      setEnrollment(null);
      setVerificationCode("");
      setNotice("Unverified backup enrollment cancelled.");
      await refresh();
    } catch (cause) {
      setActionError(cause instanceof Error ? cause.message : "The pending enrollment could not be cancelled.");
    } finally {
      setBusy(false);
    }
  }

  return (
    <section className="admin-panel admin-security-status" aria-labelledby="admin-security-status-title" aria-busy={loading || busy}>
      <div className="admin-panel__heading">
        <div>
          <p className="admin-eyebrow">LIVE SESSION CHECK</p>
          <h2 id="admin-security-status-title"><ShieldCheck size={19} /> Current MFA status</h2>
        </div>
        <button className="admin-refresh" type="button" onClick={() => { void refresh(); }} disabled={loading || busy}>
          <RefreshCw size={15} className={loading ? "admin-spin" : ""} /> Refresh status
        </button>
      </div>
      {loading && snapshot && <p className="admin-security-status__message" role="status" aria-live="polite"><Clock3 size={16} /> Refreshing Supabase Auth status…</p>}
      {loading && !snapshot ? (
        <p className="admin-security-status__message" role="status" aria-live="polite"><Clock3 size={16} /> Checking Supabase Auth…</p>
      ) : error ? (
        <p className="admin-security-status__message admin-security-status__message--error" role="alert"><AlertCircle size={16} /> {error}</p>
      ) : snapshot ? (
        <>
          <div className="admin-security-status__grid">
            <div className="admin-security-status__item">
              <span>Current session</span>
              <strong>{formatLevel(snapshot.currentLevel)}</strong>
              <small>{snapshot.currentLevel === "aal2" && snapshot.usedTotp ? "TOTP verified for this session" : "This session has not completed TOTP verification"}</small>
            </div>
            <div className="admin-security-status__item">
              <span>Verified authenticator factors</span>
              <strong>{verifiedFactors.length}</strong>
              <small>{verifiedFactors.length > 0 ? verifiedFactors.map((factor) => factor.name).join(", ") : "No verified TOTP factor found"}</small>
            </div>
          </div>
          {verifiedFactors.length < 2 && (
            <div className="admin-security-status__recovery-warning" role="status">
              <strong>{verifiedFactors.length === 0 ? "No verified authenticator is available" : "Add a backup authenticator to prevent lockout"}</strong>
              <p>{verifiedFactors.length === 0
                ? "A verified TOTP factor is required for administrator access. Complete authenticator setup before relying on this account for incident operations."
                : "Only one verified device is protecting this administrator account. If it is lost or unavailable, you may be locked out of privileged controls. Register and verify a second authenticator on a separate device before removing the current one."}</p>
            </div>
          )}
          <section className="admin-security-status__factor-list" aria-labelledby="admin-security-factor-list-title">
            <h3 id="admin-security-factor-list-title">Registered authenticator factors</h3>
            {snapshot.factors.length === 0 ? (
              <p>No TOTP factors are registered for this account.</p>
            ) : (
              <ul>
                {snapshot.factors.map((factor) => (
                  <li key={factor.id}>
                    <span className="admin-security-status__factor-icon">{factor.status === "verified" ? <CheckCircle2 size={16} /> : <ShieldX size={16} />}</span>
                    <span><strong>{factor.name}</strong><small>{factor.status === "verified" ? "Verified" : "Pending verification"}</small></span>
                    {confirmRemoveFactorId === factor.id ? (
                      <span className="admin-security-status__actions">
                        <button className="admin-refresh" type="button" onClick={() => setConfirmRemoveFactorId(null)} disabled={busy}>Keep</button>
                        <button className="admin-refresh admin-security-status__danger-action" type="button" onClick={() => { void removeAuthenticatorFactor(factor); }} disabled={busy || !canManageFactors || (factor.status === "verified" && verifiedFactors.length <= 1)}>{busy ? "Removing…" : "Confirm remove"}</button>
                      </span>
                    ) : (
                      <button className="admin-refresh admin-security-status__danger-action" type="button" onClick={() => { setActionError(null); setConfirmRemoveFactorId(factor.id); }} disabled={busy || !canManageFactors || (factor.status === "verified" && verifiedFactors.length <= 1)} title={!canManageFactors ? "Verify an authenticator in this session before managing factors" : factor.status === "verified" && verifiedFactors.length <= 1 ? "Add and verify a backup authenticator before removing this factor" : "Remove this authenticator"}>{factor.status === "verified" && verifiedFactors.length <= 1 ? "Primary factor locked" : "Remove"}</button>
                    )}
                  </li>
                ))}
              </ul>
            )}
            {pendingFactors.length > 0 && <p className="admin-security-status__note">{pendingFactors.length} pending factor(s) are not accepted for privileged operations.</p>}
            {!canManageFactors && <p className="admin-security-status__note" role="status">Verify a registered authenticator in this session to add or remove factors. Factor-management actions remain unavailable until the session reaches TOTP-verified AAL2.</p>}
          </section>
          <section className="admin-security-status__revocation" aria-labelledby="admin-security-revocation-title">
            <div>
              <h3 id="admin-security-revocation-title">Sign out all devices</h3>
              <p>Revoke refreshable Supabase sessions for this account across TACResQ browsers and devices, including the Android app. This action requires a current TOTP-verified AAL2 session.</p>
            </div>
            {!confirmGlobalSignOut ? (
              <button className="admin-refresh admin-security-status__danger-action" type="button" onClick={() => { setActionError(null); setConfirmGlobalSignOut(true); }} disabled={busy || snapshot.currentLevel !== "aal2" || !snapshot.usedTotp}>
                <LogOut size={15} /> Sign out all devices
              </button>
            ) : (
              <div className="admin-security-status__confirm">
                <p><strong>Confirm global sign-out?</strong> All devices will need to sign in again. Already-issued access tokens may remain valid until they expire.</p>
                <div className="admin-security-status__actions">
                  <button className="admin-refresh" type="button" onClick={() => setConfirmGlobalSignOut(false)} disabled={busy}>Cancel</button>
                  <button className="admin-refresh admin-security-status__danger-action" type="button" onClick={() => { void signOutAllDevices(); }} disabled={busy}>{busy ? "Revoking sessions…" : "Confirm sign out everywhere"}</button>
                </div>
              </div>
            )}
          </section>
          <section className="admin-security-status__backup" aria-labelledby="admin-security-backup-title">
            <div>
              <h3 id="admin-security-backup-title">Backup authenticator</h3>
              <p>Add a second authenticator on a separate device so access does not depend on one phone. TACResQ uses Supabase's supported TOTP factor flow; recovery codes are not assumed to be enabled for project users.</p>
            </div>
            {!enrollment && (
              <button className="admin-refresh" type="button" onClick={() => { void addBackupAuthenticator(); }} disabled={busy || !canManageFactors || snapshot.factors.length >= 10}>
                Add backup authenticator
              </button>
            )}
            {snapshot.factors.length >= 10 && <p className="admin-security-status__note">Supabase's per-user MFA factor limit has been reached.</p>}
          </section>
          {enrollment && (
            <form className="admin-security-status__enrollment" aria-labelledby="admin-security-enrollment-title" onSubmit={verifyBackupAuthenticator}>
              <h3 id="admin-security-enrollment-title">Register the backup device</h3>
              <p id="admin-backup-enrollment-guidance">Scan this QR code with an authenticator app on a different device. Keep the setup key private and do not send it to anyone.</p>
              <img src={enrollment.qrCode} alt="QR code for the backup authenticator" />
              <label htmlFor="admin-backup-secret">Manual setup key</label>
              <input id="admin-backup-secret" type="text" readOnly value={enrollment.secret} autoComplete="off" />
              <label htmlFor="admin-backup-code">Six-digit code</label>
              <input id="admin-backup-code" type="text" aria-describedby="admin-backup-enrollment-guidance" inputMode="numeric" autoComplete="one-time-code" pattern="[0-9]{6}" maxLength={6} placeholder="000000" value={verificationCode} onChange={(event) => setVerificationCode(event.target.value.replace(/\D/g, "").slice(0, 6))} required />
              <div className="admin-security-status__actions">
                <button className="admin-refresh" type="button" onClick={() => { void cancelBackupEnrollment(); }} disabled={busy}>Cancel</button>
                <button className="admin-refresh" type="submit" disabled={busy || verificationCode.length !== 6}>{busy ? "Verifying…" : "Verify backup device"}</button>
              </div>
            </form>
          )}
          {actionError && <p className="admin-security-status__message admin-security-status__message--error" role="alert"><AlertCircle size={16} /> {actionError}</p>}
          {notice && <p className="admin-security-status__message admin-security-status__message--success" role="status"><CheckCircle2 size={16} /> {notice}</p>}
          <section className="admin-security-status__coverage" aria-labelledby="admin-security-coverage-title">
            <div className="admin-security-status__coverage-heading">
              <div>
                <p className="admin-eyebrow">BRANCH IMPLEMENTATION INVENTORY</p>
                <h3 id="admin-security-coverage-title">Database controls defined in this branch</h3>
                <p>This inventory describes code and migrations in the current branch only. It is separate from the live MFA and session controls above and does not confirm that any database migration has been applied to a deployed environment.</p>
              </div>
            </div>
            <div className="admin-security-status__coverage-grid">
              <div className="admin-security-status__coverage-group">
                <h4><ShieldCheck size={15} /> Implemented in branch · deployment unverified</h4>
                <ul>
                  <li><strong>TOTP + AAL2 gate</strong><span>Admin RPCs require an authenticated user, a TOTP method in the session claims, AAL2, and an Admin profile role.</span></li>
                  <li><strong>Protected Admin operations</strong><span>User listing, role changes, audit retrieval, and analytics summary are wrapped by the MFA gate.</span></li>
                  <li><strong>Audit-table read protection</strong><span>Direct reads of Admin audit records also require TOTP-verified AAL2 claims.</span></li>
                  <li><strong>Role-change accountability</strong><span>Role changes require a reason and are recorded through the protected Admin operation.</span></li>
                </ul>
                <p className="admin-security-status__coverage-caveat">Environment verification: unavailable. Do not treat these controls as active in production until the target project's migration history and deployed database behavior have been independently checked.</p>
              </div>
              <div className="admin-security-status__coverage-group admin-security-status__coverage-group--gaps">
                <h4><ShieldX size={15} /> Not verified by this Admin console</h4>
                <ul>
                  <li><strong>Account suspension</strong><span>No verified Admin suspend/disable-account control is available in this console.</span></li>
                  <li><strong>Denied-action audit trail</strong><span>Rejected privileged requests are not yet recorded in a trusted, durable Admin event stream.</span></li>
                  <li><strong>Device-security telemetry</strong><span>No trusted server-side writer currently feeds device-security events into this control center.</span></li>
                  <li><strong>Backup and restore status</strong><span>Live backup-job and restore-drill evidence is not connected to this page.</span></li>
                  <li><strong>Push-delivery monitoring</strong><span>FCM delivery attempts and outcomes are not verified by this console.</span></li>
                </ul>
              </div>
            </div>
          </section>
          <p className="admin-security-status__footnote">Authenticator setup keys and QR codes are shown only while registering a factor. A verified factor can be removed only when another verified factor remains, and removal requires fresh TOTP verification. Cancel only removes the unverified factor created by the active setup flow. Global sign-out revokes refreshable sessions; existing access tokens remain valid until expiry.</p>
        </>
      ) : null}
    </section>
  );
}
