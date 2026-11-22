import { useEffect, useRef, useState } from "react";
import { AlertCircle, LoaderCircle, LogOut, ShieldCheck } from "lucide-react";
import { Brand } from "@/components/Brand";
import { getSupabase } from "@/lib/supabase";

type GateState = "checking" | "enroll" | "verify" | "ready" | "error";

export default function AdminMfaGate({ children, onSignOut }: {
  children: React.ReactNode;
  onSignOut: () => void;
}) {
  const [state, setState] = useState<GateState>("checking");
  const [factorId, setFactorId] = useState("");
  const [qrCode, setQrCode] = useState("");
  const [secret, setSecret] = useState("");
  const [code, setCode] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const started = useRef(false);

  useEffect(() => {
    if (started.current) return;
    started.current = true;
    let active = true;
    async function prepare() {
      try {
        const auth = getSupabase().auth;
        const [aalResult, factorsResult] = await Promise.all([
          auth.mfa.getAuthenticatorAssuranceLevel(),
          auth.mfa.listFactors(),
        ]);
        if (aalResult.error) throw aalResult.error;
        if (factorsResult.error) throw factorsResult.error;
        if (!active) return;

        if (aalResult.data.currentLevel === "aal2") {
          setState("ready");
          return;
        }

        const verified = factorsResult.data.totp.find((factor) => factor.status === "verified");
        if (verified) {
          setFactorId(verified.id);
          setState("verify");
          return;
        }

        // Remove abandoned, unverified enrollment attempts before issuing a fresh QR.
        for (const pending of factorsResult.data.totp.filter((factor) => factor.status !== "verified")) {
          const { error: removeError } = await auth.mfa.unenroll({ factorId: pending.id });
          if (removeError) throw removeError;
        }
        const { data, error: enrollError } = await auth.mfa.enroll({
          factorType: "totp",
          friendlyName: "TACResQ Administrator",
        });
        if (enrollError) throw enrollError;
        if (!active) return;
        setFactorId(data.id);
        setQrCode(data.totp.qr_code);
        setSecret(data.totp.secret);
        setState("enroll");
      } catch (cause) {
        if (!active) return;
        setError(cause instanceof Error ? cause.message : "The authenticator could not be prepared.");
        setState("error");
      }
    }
    void prepare();
    return () => { active = false; };
  }, []);

  async function verifyCode(event: React.FormEvent<HTMLFormElement>) {
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
      if (data.currentLevel !== "aal2") throw new Error("The second factor was not confirmed. Please try again.");
      setCode("");
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
        <h1>{state === "enroll" ? "Set up your authenticator" : state === "verify" ? "Verify your identity" : state === "error" ? "MFA setup unavailable" : "Checking your security…"}</h1>
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
            <input id="mfa-secret" type="text" readOnly value={secret} autoComplete="off" />
            <p className="mfa-help">Keep this setup key private. Anyone who has it can generate your administrator codes.</p>
          </div>
        )}
        {(state === "enroll" || state === "verify") && (
          <form className="login-form" onSubmit={verifyCode}>
            <label htmlFor="mfa-code">Six-digit authenticator code</label>
            <input id="mfa-code" type="text" inputMode="numeric" autoComplete="one-time-code" pattern="[0-9]{6}" maxLength={6} placeholder="000000" value={code} onChange={(event) => setCode(event.target.value.replace(/\D/g, "").slice(0, 6))} required />
            {error && <div className="inline-alert inline-alert--error" role="alert"><AlertCircle size={17} />{error}</div>}
            <button className="button button--primary button--wide" type="submit" disabled={busy || code.length !== 6}>
              {busy ? <><LoaderCircle size={17} className="spin" /> Verifying…</> : "Verify and continue"}
            </button>
          </form>
        )}
        {state === "error" && (
          <>
            {error && <div className="inline-alert inline-alert--error" role="alert"><AlertCircle size={17} />{error}</div>}
            <button className="button button--primary button--wide" type="button" onClick={() => window.location.reload()}>Retry security check</button>
          </>
        )}
        <div className="gate-footnote">TOTP verification is handled by Supabase Auth. TACResQ does not send OTP codes by SMS or email, and does not store your Google Authenticator secret in its application database.</div>
      </section>
    </main>
  );
}
