import { useCallback, useEffect, useState } from "react";
import type { FormEvent } from "react";
import type { Session } from "@supabase/supabase-js";
import { AlertCircle, ArrowUpRight, BadgeCheck, LoaderCircle, LogOut, ShieldAlert } from "lucide-react";
import { Brand } from "@/components/Brand";
import AdminConsole from "@/pages/AdminConsole";
import { getMyProfile, getSupabase } from "@/lib/supabase";
import type { TacruProfile } from "@/lib/supabase";

interface HomeProps {
  session: Session | null;
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
          <p className="eyebrow eyebrow--light">TACRESQ · ADMINISTRATION</p>
          <h1>Clear oversight.<br />One secure place.</h1>
          <p className="login-story__copy">Manage user access and review administrative audit records. Emergency dispatch operations remain in the TACResQ Android app.</p>
        </div>
      </section>
      <section className="login-form-wrap">
        <div className="login-form-card">
          <div className="login-mobile-brand"><Brand /></div>
          <p className="eyebrow">ADMIN PORTAL ACCESS</p>
          <h2>Administrator sign in</h2>
          <p className="muted">Use an authorized administrator account connected to the TACResQ Supabase project.</p>
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
          <div className="login-access-note"><BadgeCheck size={17} /><p>Only administrator accounts can use this portal. Dispatcher accounts cannot access web dispatch controls; use the TACResQ Android app for dispatch operations.</p></div>
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
  }, [session?.user.id]);

  const handleSignOut = useCallback(() => {
    void getSupabase().auth.signOut();
  }, []);

  if (!session) return <LoginScreen />;
  if (profileLoading) return <main className="loading-screen"><Brand /><LoaderCircle size={22} className="spin" /><span>Checking administrator access…</span></main>;
  if (profileError) return <AccessScreen title="We couldn't verify this profile." description={profileError} onSignOut={handleSignOut} icon="error" />;
  if (!profile) return <AccessScreen title="A TACResQ profile is required." description="This account is signed in, but no linked TACResQ profile was returned. Ask a trusted project administrator to verify the account mapping." onSignOut={handleSignOut} />;
  if (profile.role === "admin") return <AdminConsole profile={profile} onSignOut={handleSignOut} />;
  if (profile.role === "dispatcher") {
    return <AccessScreen title="Dispatch is handled in the Android app." description="The web dispatch dashboard has been retired to keep dispatch operations in one place. Sign in to the TACResQ Android app to manage incident queues, assignments, and response progress." onSignOut={handleSignOut} />;
  }
  return <AccessScreen title="Administrator access is required." description="This account is signed in, but its TACResQ profile is not authorized to use the administrator portal." onSignOut={handleSignOut} />;
}
