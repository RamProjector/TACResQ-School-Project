import { lazy, Suspense, useCallback, useEffect, useState } from "react";
import type { FormEvent } from "react";
import type { Session } from "@supabase/supabase-js";
import { AlertCircle, ArrowUpRight, BadgeCheck, LoaderCircle, LogOut, ShieldAlert } from "lucide-react";
import { Brand } from "@/components/Brand";
import AdminMfaGate from "@/components/AdminMfaGate";
import { getMyProfile, getSupabase } from "@/lib/supabase";
import type { TacruProfile } from "@/lib/supabase";

const AdminConsole = lazy(() => import("@/pages/AdminConsole"));

interface HomeProps {
  session: Session | null;
}

function AdminWorkspaceLoading() {
  return (
    <main className="loading-screen" aria-live="polite">
      <Brand />
      <LoaderCircle size={22} className="spin" aria-hidden="true" />
      <span>Loading the secure Admin workspace…</span>
    </main>
  );
}

function LoginScreen() {
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setSubmitting(true);
    setError(null);
    try {
      const { error: authError } = await getSupabase().auth.signInWithPassword({ email: email.trim(), password });
      if (authError) throw authError;
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "Sign-in did not complete. Check your details and try again.");
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <main className="login-page">
      <section className="login-story">
        <Brand inverse />
        <div className="login-story__body">
          <p className="eyebrow eyebrow--light"><span className="login-story__signal-dot" /> TACRESQ · ADMIN PORTAL</p>
          <h1>A clear view of the accounts and activity behind TACResQ.</h1>
          <p className="login-story__copy">Manage administrator access, review user accounts, and check service connections. Emergency dispatch remains in the TACResQ Android app.</p>
          <div className="login-story__visual" aria-hidden="true">
            <svg viewBox="0 0 620 270" role="presentation">
              <defs>
                <linearGradient id="portal-route" x1="0" y1="0" x2="1" y2="1">
                  <stop offset="0" stopColor="#91B7A2" />
                  <stop offset="1" stopColor="#F36A43" />
                </linearGradient>
                <linearGradient id="portal-panel" x1="0" y1="0" x2="1" y2="1">
                  <stop offset="0" stopColor="#28434A" />
                  <stop offset="1" stopColor="#1B333B" />
                </linearGradient>
              </defs>
              <rect x="1" y="1" width="618" height="268" rx="16" fill="url(#portal-panel)" stroke="#52716C" strokeOpacity=".5" />
              <g stroke="#A7C0B2" strokeOpacity=".12" strokeWidth="1">
                {Array.from({ length: 13 }, (_, i) => <path key={"v"+i} d={"M"+(28+i*47)+" 18 V252"} />)}
                {Array.from({ length: 5 }, (_, i) => <path key={"h"+i} d={"M18 "+(34+i*48)+" H602"} />)}
              </g>
              <path d="M40 208 C112 208 110 90 192 90 S285 186 354 155 S444 47 570 62" fill="none" stroke="url(#portal-route)" strokeWidth="3" strokeLinecap="round" strokeDasharray="5 8" />
              <path d="M40 208 C112 208 110 90 192 90 S285 186 354 155 S444 47 570 62" fill="none" stroke="#91B7A2" strokeOpacity=".16" strokeWidth="17" strokeLinecap="round" />
              <g fill="#142A31" stroke="#A9C8B4" strokeWidth="2">
                <circle cx="40" cy="208" r="7" /><circle cx="192" cy="90" r="7" /><circle cx="354" cy="155" r="7" /><circle cx="570" cy="62" r="7" />
              </g>
              <g fill="#F3F4ED" fontFamily="IBM Plex Mono, monospace" fontSize="9" letterSpacing="1.2">
                <text x="30" y="235">ACCOUNTS</text><text x="165" y="66">PERMISSIONS</text><text x="326" y="184">ACTIVITY</text><text x="531" y="39">HISTORY</text>
              </g>
              <g transform="translate(376 188)">
                <rect width="214" height="55" rx="9" fill="#F0F1EB" fillOpacity=".96" />
                <circle cx="20" cy="20" r="5" fill="#28735D" />
                <text x="34" y="23" fill="#1C3035" fontFamily="IBM Plex Mono, monospace" fontSize="8" letterSpacing="1">ADMIN ACCESS</text>
                <text x="16" y="42" fill="#52666A" fontFamily="DM Sans, sans-serif" fontSize="11">For approved administrators only</text>
              </g>
            </svg>
            <div className="login-story__visual-caption"><span>ADMIN TOOLS</span><strong>Accounts <i>·</i> Permissions <i>·</i> Activity</strong></div>
          </div>
        </div>
        <div className="login-story__footer"><span>ADMINISTRATOR ACCESS</span><span>APPROVED ADMINISTRATORS ONLY</span></div>
      </section>
      <section className="login-form-wrap">
        <div className="login-form-card">
          <div className="login-mobile-brand"><Brand /></div>
          <p className="eyebrow">ADMIN PORTAL ACCESS</p>
          <h2>Administrator sign in</h2>
          <p className="muted">Sign in with the email and password for your TACResQ administrator account.</p>
          <form className="login-form" onSubmit={handleSubmit}>
            <label htmlFor="email">Email address</label>
            <input id="email" name="email" type="email" autoComplete="username" placeholder="admin@example.org" value={email} onChange={(event) => setEmail(event.target.value)} required />
            <label htmlFor="password">Password</label>
            <input id="password" name="password" type="password" autoComplete="current-password" placeholder="Enter your password" value={password} onChange={(event) => setPassword(event.target.value)} required />
            {error && <div className="inline-alert inline-alert--error" role="alert"><AlertCircle size={17} />{error}</div>}
            <button className="button button--primary button--wide" type="submit" disabled={submitting}>
              {submitting ? <><LoaderCircle size={17} className="spin" /> Verifying access…</> : <>Open admin portal <ArrowUpRight size={17} /></>}
            </button>
          </form>
          <div className="login-access-note"><BadgeCheck size={17} /><p>This portal is for administrator accounts. Dispatchers should use the TACResQ Android app to handle emergency requests.</p></div>
        </div>
        <p className="login-footnote">TACRESQ · ADMIN PORTAL</p>
      </section>
    </main>
  );
}

function AccessScreen({ title, description, onSignOut, icon = "shield" }: {
  title: string;
  description: string;
  onSignOut: () => void;
  icon?: "shield" | "error";
}) {
  return (
    <main className="gate-screen">
      <header className="gate-header"><Brand /><button className="button button--quiet" onClick={onSignOut}><LogOut size={16} /> Sign out</button></header>
      <section className="gate-card">
        <div className={`gate-icon${icon === "error" ? " gate-icon--error" : ""}`}>{icon === "error" ? <AlertCircle size={24} /> : <ShieldAlert size={24} />}</div>
        <p className="eyebrow">ACCOUNT ACCESS</p>
        <h1>{title}</h1>
        <p className="muted">{description}</p>
        <div className="gate-footnote">The web portal is reserved for administrator account management. Dispatchers should use the TACResQ Android app for all incident dispatch operations.</div>
      </section>
    </main>
  );
}

export default function Home({ session }: HomeProps) {
  const [profile, setProfile] = useState<TacruProfile | null>(null);
  const [profileLoading, setProfileLoading] = useState(Boolean(session));
  const [profileError, setProfileError] = useState<string | null>(null);

  useEffect(() => {
    if (!session) {
      setProfile(null);
      setProfileLoading(false);
      setProfileError(null);
      return;
    }
    let current = true;
    setProfile(null);
    setProfileError(null);
    setProfileLoading(true);
    void getMyProfile().then((result) => {
      if (current) setProfile(result);
    }).catch((cause: unknown) => {
      if (current) setProfileError(cause instanceof Error ? cause.message : "Could not verify this account's TACResQ role.");
    }).finally(() => {
      if (current) setProfileLoading(false);
    });
    return () => { current = false; };
  }, [session?.user.id, session?.access_token]);

  const handleSignOut = useCallback(() => {
    void getSupabase().auth.signOut({ scope: "local" });
  }, []);

  if (!session) return <LoginScreen />;
  if (profileLoading) return <main className="loading-screen"><Brand /><LoaderCircle size={22} className="spin" /><span>Checking administrator access…</span></main>;
  if (profileError) return <AccessScreen title="We couldn't verify this profile." description={profileError} onSignOut={handleSignOut} icon="error" />;
  if (!profile) return <AccessScreen title="A TACResQ profile is required." description="This account is signed in, but no linked TACResQ profile was returned. Ask a trusted project administrator to verify the account mapping." onSignOut={handleSignOut} />;
  if (profile.role === "admin") return <AdminMfaGate key={session.access_token} onSignOut={handleSignOut}><Suspense fallback={<AdminWorkspaceLoading />}><AdminConsole profile={profile} onSignOut={handleSignOut} /></Suspense></AdminMfaGate>;
  if (profile.role === "dispatcher") {
    return <AccessScreen title="Dispatch is handled in the Android app." description="The web dispatch dashboard has been retired to keep dispatch operations in one place. Sign in to the TACResQ Android app to manage incident queues, assignments, and response progress." onSignOut={handleSignOut} />;
  }
  return <AccessScreen title="Administrator access is required." description="This account is signed in, but its TACResQ profile is not authorized to use the administrator portal." onSignOut={handleSignOut} />;
}
