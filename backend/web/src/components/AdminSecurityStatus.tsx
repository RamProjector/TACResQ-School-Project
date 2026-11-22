import { useCallback, useEffect, useState } from "react";
import type { FormEvent } from "react";
import { AlertCircle, CheckCircle2, Clock3, RefreshCw, ShieldCheck, ShieldX } from "lucide-react";
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

  const refresh = useCallback(async () => {
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
      setError(cause instanceof Error ? cause.message : "Could not retrieve the current MFA status.");
      setSnapshot(null);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void refresh();
  }, [refresh]);

  const verifiedFactors = snapshot?.factors.filter((factor) => factor.status === "verified") ?? [];
  const pendingFactors = snapshot?.factors.filter((factor) => factor.status !== "verified") ?? [];

  async function addBackupAuthenticator() {
    if (busy || enrollment || !snapshot || snapshot.factors.length >= 10) return;
    setBusy(true);
    setActionError(null);
    setNotice(null);
    try {
      const existingNames = new Set(snapshot.factors.map((factor) => factor.name));
      let suffix = 1;
      while (existingNames.has(`TACResQ Backup ${suffix}`)) suffix += 1;
      const { data, error: enrollError } = await getSupabase().auth.mfa.enroll({
        factorType: "totp",
        friendlyName: `TACResQ Backup ${suffix}`,
      });
      if (enrollError) throw enrollError;
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

  async function cancelBackupEnrollment() {
    if (!enrollment || busy) return;
    setBusy(true);
    setActionError(null);
    try {
      const { error: unenrollError } = await getSupabase().auth.mfa.unenroll({ factorId: enrollment.factorId });
      if (unenrollError) throw unenrollError;
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
    <section className="admin-panel admin-security-status" aria-live="polite" aria-labelledby="admin-security-status-title">
      <div className="admin-panel__heading">
        <div>
          <p className="admin-eyebrow">LIVE SESSION CHECK</p>
          <h2 id="admin-security-status-title"><ShieldCheck size={19} /> Current MFA status</h2>
        </div>
        <button className="admin-refresh" type="button" onClick={() => { void refresh(); }} disabled={loading || busy}>
          <RefreshCw size={15} className={loading ? "admin-spin" : ""} /> Refresh status
        </button>
      </div>
      {loading && !snapshot ? (
        <p className="admin-security-status__message"><Clock3 size={16} /> Checking Supabase Auth…</p>
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
          <div className="admin-security-status__factor-list">
            <h3>Registered authenticator factors</h3>
            {snapshot.factors.length === 0 ? (
              <p>No TOTP factors are registered for this account.</p>
            ) : (
              <ul>
                {snapshot.factors.map((factor) => (
                  <li key={factor.id}>
                    <span className="admin-security-status__factor-icon">{factor.status === "verified" ? <CheckCircle2 size={16} /> : <ShieldX size={16} />}</span>
                    <span><strong>{factor.name}</strong><small>{factor.status === "verified" ? "Verified" : "Pending verification"}</small></span>
                  </li>
                ))}
              </ul>
            )}
            {pendingFactors.length > 0 && <p className="admin-security-status__note">{pendingFactors.length} pending factor(s) are not accepted for privileged operations.</p>}
          </div>
          <div className="admin-security-status__backup">
            <div>
              <h3>Backup authenticator</h3>
              <p>Add a second authenticator on a separate device so access does not depend on one phone. TACResQ uses Supabase's supported TOTP factor flow; recovery codes are not assumed to be enabled for project users.</p>
            </div>
            {!enrollment && (
              <button className="admin-refresh" type="button" onClick={() => { void addBackupAuthenticator(); }} disabled={busy || snapshot.factors.length >= 10}>
                Add backup authenticator
              </button>
            )}
            {snapshot.factors.length >= 10 && <p className="admin-security-status__note">Supabase's per-user MFA factor limit has been reached.</p>}
          </div>
          {enrollment && (
            <form className="admin-security-status__enrollment" onSubmit={verifyBackupAuthenticator}>
              <h3>Register the backup device</h3>
              <p>Scan this QR code with an authenticator app on a different device. Keep the setup key private and do not send it to anyone.</p>
              <img src={enrollment.qrCode} alt="QR code for the backup authenticator" />
              <label htmlFor="admin-backup-secret">Manual setup key</label>
              <input id="admin-backup-secret" type="text" readOnly value={enrollment.secret} autoComplete="off" />
              <label htmlFor="admin-backup-code">Six-digit code</label>
              <input id="admin-backup-code" type="text" inputMode="numeric" autoComplete="one-time-code" pattern="[0-9]{6}" maxLength={6} placeholder="000000" value={verificationCode} onChange={(event) => setVerificationCode(event.target.value.replace(/\D/g, "").slice(0, 6))} required />
              <div className="admin-security-status__actions">
                <button className="admin-refresh" type="button" onClick={() => { void cancelBackupEnrollment(); }} disabled={busy}>Cancel</button>
                <button className="admin-refresh" type="submit" disabled={busy || verificationCode.length !== 6}>{busy ? "Verifying…" : "Verify backup device"}</button>
              </div>
            </form>
          )}
          {actionError && <p className="admin-security-status__message admin-security-status__message--error" role="alert"><AlertCircle size={16} /> {actionError}</p>}
          {notice && <p className="admin-security-status__message admin-security-status__message--success" role="status"><CheckCircle2 size={16} /> {notice}</p>}
          <p className="admin-security-status__footnote">Authenticator setup keys and QR codes are shown only while registering a factor. This page does not expose factor deletion or disable controls, and a verified authenticator is never removed automatically.</p>
        </>
      ) : null}
    </section>
  );
}
