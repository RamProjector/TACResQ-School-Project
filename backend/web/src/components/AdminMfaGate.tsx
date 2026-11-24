import { useEffect, useRef, useState } from "react";
import type { FormEvent, ReactNode } from "react";
import { AlertCircle, Eye, EyeOff, LoaderCircle, LogOut, ShieldCheck } from "lucide-react";
import { Brand } from "@/components/Brand";
import { getSupabase } from "@/lib/supabase";
import "./AdminMfaGate.css";

type GateState = "checking" | "enroll" | "verify" | "ready" | "error";
type PreparedMfa =
  | { state: "ready" }
  | { state: "verify"; factors: Array<{ id: string; name: string }> }
  | { state: "enroll"; factorId: string; qrCode: string; secret: string };

async function prepareAdminMfa(): Promise<PreparedMfa> {
  const auth = getSupabase().auth;
  const [aalResult, factorsResult] = await Promise.all([
    auth.mfa.getAuthenticatorAssuranceLevel(),
    auth.mfa.listFactors(),
  ]);
  if (aalResult.error) throw aalResult.error;
  if (factorsResult.error) throw factorsResult.error;

  const usedTotp = aalResult.data.currentAuthenticationMethods?.some((method) => typeof method === "string" ? method === "totp" : typeof method === "object" && method !== null && method.method === "totp") ?? false;
  if (aalResult.data.currentLevel === "aal2" && usedTotp) return { state: "ready" };

  const totpFactors = factorsResult.data.all.filter((factor) => factor.factor_type === "totp");
  const verified = totpFactors.filter((factor) => factor.status === "verified");
  if (verified.length > 0) return {
    state: "verify",
    factors: verified.map((factor) => ({ id: factor.id, name: factor.friendly_name || "Authenticator app" })),
  };

  // Never delete pending factors during sign-in. A pending enrollment may belong
  // to another active browser/device; only the factor's explicit owner action
  // should cancel it. Start this session's own enrollment instead.
  const { data, error } = await auth.mfa.enroll({
    factorType: "totp",
    friendlyName: "TACResQ Administrator",
  });
  if (error) throw error;
  return {
    state: "enroll",
    factorId: data.id,
    qrCode: data.totp.qr_code,
    secret: data.totp.secret,
  };
}

export default function AdminMfaGate({ children, onSignOut }: {
  children: ReactNode;
  onSignOut: () => void;
}) {
  const [state, setState] = useState<GateState>("checking");
  const [factorId, setFactorId] = useState("");
  const [availableFactors, setAvailableFactors] = useState<Array<{ id: string; name: string }>>([]);
  const [qrCode, setQrCode] = useState("");
  const [secret, setSecret] = useState("");
  const [showSecret, setShowSecret] = useState(false);
  const [code, setCode] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  // Cache the preparation promise so React StrictMode's effect replay cannot
  // accidentally enroll duplicate factors or invalidate a fresh QR code.
  const preparation = useRef<Promise<PreparedMfa> | null>(null);

  useEffect(() => {
    let active = true;
    preparation.current ??= prepareAdminMfa();
    void preparation.current.then((result) => {
      if (!active) return;
      if (result.state === "ready") {
        setState("ready");
      } else if (result.state === "verify") {
        setAvailableFactors(result.factors);
        setFactorId(result.factors[0].id);
        setState("verify");
      } else {
        setFactorId(result.factorId);
        setQrCode(result.qrCode);
        setSecret(result.secret);
        setShowSecret(false);
        setState("enroll");
      }
    }).catch((cause: unknown) => {
      if (!active) return;
      setError(cause instanceof Error ? cause.message : "The authenticator could not be prepared.");
      setState("error");
    });
    return () => { active = false; };
  }, []);

  async function cancelEnrollment() {
    if (state !== "enroll" || !factorId || busy) return;
    setBusy(true);
    setError(null);
    try {
      // This ID is the pending factor created by this gate instance only.
      // Never enumerate and remove other pending factors from another device.
      const { error: unenrollError } = await getSupabase().auth.mfa.unenroll({ factorId });
      if (unenrollError) throw unenrollError;
      setSecret("");
      setQrCode("");
      setShowSecret(false);
      setCode("");
      onSignOut();
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "The pending setup could not be cancelled.");
      setBusy(false);
    }
  }

  async function verifyCode(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!factorId || !/^\d{6}$/.test(code) || busy) return;
    setBusy(true);
    setError(null);
    try {
      const auth = getSupabase().auth;
      const { error: verifyError } = await auth.mfa.challengeAndVerify({ factorId, code });
      if (verifyError) throw verifyError;
      const { data, error: aalError } = await auth.mfa.getAuthenticatorAssuranceLevel();
      if (aalError) throw aalError;
      const usedTotp = data.currentAuthenticationMethods?.some((method) => typeof method === "string" ? method === "totp" : typeof method === "object" && method !== null && method.method === "totp") ?? false;
      if (data.currentLevel !== "aal2" || !usedTotp) {
        throw new Error("TOTP verification was not confirmed. Please check your authenticator code and try again.");
      }
      setCode("");
      setSecret("");
      setQrCode("");
      setShowSecret(false);
      setState("ready");
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "The code could not be verified. Check Google Authenticator and try again.");
    } finally {
      setBusy(false);
    }
  }

  if (state === "ready") return <>{children}</>;

  return (
    <main className="gate-screen">
      <header className="gate-header"><Brand /><button className="button button--quiet" type="button" onClick={onSignOut}><LogOut size={16} /> Sign out</button></header>
      <section className="gate-card">
        <div className="gate-icon"><ShieldCheck size={24} /></div>
        <p className="eyebrow">TACRESQ · ADMINISTRATOR MFA</p>
        <h1>{state === "enroll" ? "Set up Google Authenticator" : state === "verify" ? "Verify your identity" : state === "error" ? "MFA setup unavailable" : "Checking your security…"}</h1>
        <p className="muted">
          {state === "enroll"
            ? "Scan this QR code with Google Authenticator, then enter the six-digit code it generates. Administrator access stays locked until setup is verified."
            : state === "verify"
              ? "Enter the current six-digit code from Google Authenticator to continue to the administrator portal."
              : state === "error"
                ? "The administrator portal requires a verified TOTP factor. Retry the check or sign out; no security check is being skipped."
                : "Checking your administrator authentication level…"}
        </p>
        {state === "checking" && <LoaderCircle size={22} className="spin" />}
        {state === "enroll" && (
          <div className="mfa-enrollment">
            {qrCode && <img className="mfa-qr" src={qrCode} alt="Scan this QR code with Google Authenticator" />}
            <label htmlFor="mfa-secret">Manual setup key</label>
            <div className="mfa-secret-field">
              <input id="mfa-secret" type={showSecret ? "text" : "password"} readOnly value={secret} autoComplete="off" spellCheck={false} aria-describedby="mfa-secret-help" />
              <button className="button button--quiet" type="button" onClick={() => setShowSecret((visible) => !visible)} aria-label={showSecret ? "Hide manual setup key" : "Show manual setup key"} aria-pressed={showSecret}>
                {showSecret ? <EyeOff size={15} /> : <Eye size={15} />}{showSecret ? "Hide key" : "Show key"}
              </button>
            </div>
            <p className="mfa-help" id="mfa-secret-help">The setup key is hidden until you choose to reveal it. Keep it private; anyone who has it can generate your administrator codes.</p>
          </div>
        )}
        {(state === "enroll" || state === "verify") && (
          <form className="login-form" onSubmit={verifyCode}>
            {state === "verify" && availableFactors.length > 1 && (
              <>
                <label htmlFor="mfa-factor">Authenticator device</label>
                <select id="mfa-factor" value={factorId} onChange={(event) => setFactorId(event.target.value)}>
                  {availableFactors.map((factor) => <option key={factor.id} value={factor.id}>{factor.name}</option>)}
                </select>
              </>
            )}
            <label htmlFor="mfa-code">Six-digit authenticator code</label>
            <input id="mfa-code" type="text" inputMode="numeric" autoComplete="one-time-code" pattern="[0-9]{6}" maxLength={6} placeholder="000000" value={code} onChange={(event) => setCode(event.target.value.replace(/\D/g, "").slice(0, 6))} required />
            {error && <div className="inline-alert inline-alert--error" role="alert"><AlertCircle size={17} />{error}</div>}
            <button className="button button--primary button--wide" type="submit" disabled={busy || code.length !== 6}>
              {busy ? <><LoaderCircle size={17} className="spin" /> Verifying…</> : "Verify and continue"}
            </button>
            {state === "enroll" && (
              <button className="button button--quiet button--wide" type="button" onClick={() => { void cancelEnrollment(); }} disabled={busy}>
                {busy ? "Cancelling setup…" : "Cancel setup and sign out"}
              </button>
            )}
          </form>
        )}
        {state === "error" && (
          <>
            {error && <div className="inline-alert inline-alert--error" role="alert"><AlertCircle size={17} />{error}</div>}
            <button className="button button--primary button--wide" type="button" onClick={() => window.location.reload()}>Retry security check</button>
          </>
        )}
        <div className="gate-footnote">Supabase Auth generates and verifies the TOTP factor. TACResQ does not send OTP codes by SMS or email. The setup key is shown only during enrollment and is not written to TACResQ application tables.</div>
      </section>
    </main>
  );
}
