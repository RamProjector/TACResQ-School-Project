import { useCallback, useEffect, useState } from "react";
import { AlertCircle, CheckCircle2, Clock3, RefreshCw, ShieldCheck, ShieldX } from "lucide-react";
import { getSupabase } from "@/lib/supabase";

type SecuritySnapshot = {
  currentLevel: string | null;
  usedTotp: boolean;
  factors: Array<{ id: string; name: string; status: string }>;
};

function formatLevel(level: string | null) {
  if (level === "aal2") return "Elevated (AAL2)";
  if (level === "aal1") return "Password session (AAL1)";
  return "Unavailable";
}

export default function AdminSecurityStatus() {
  const [snapshot, setSnapshot] = useState<SecuritySnapshot | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

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
        factors: factorsResult.data.totp.map((factor) => ({
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

  return (
    <section className="admin-panel admin-security-status" aria-live="polite" aria-labelledby="admin-security-status-title">
      <div className="admin-panel__heading">
        <div>
          <p className="admin-eyebrow">LIVE SESSION CHECK</p>
          <h2 id="admin-security-status-title"><ShieldCheck size={19} /> Current MFA status</h2>
        </div>
        <button className="admin-refresh" type="button" onClick={() => { void refresh(); }} disabled={loading}>
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
          <p className="admin-security-status__footnote">Read-only status from the signed-in Supabase Auth session. Factor secrets, QR setup data, and recovery material are never displayed here. Changes to authenticator enrollment remain in the protected sign-in flow.</p>
        </>
      ) : null}
    </section>
  );
}
