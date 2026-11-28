import { useEffect, useState } from "react";
import type { Session } from "@supabase/supabase-js";
import { Activity, LoaderCircle } from "lucide-react";
import Home from "@/pages/Home";
import { Brand } from "@/components/Brand";
import { getSupabase, initializeSupabase } from "@/lib/supabase";

function LoadingScreen() {
  return (
    <main className="loading-screen" aria-live="polite">
      <Brand />
      <LoaderCircle className="spin" size={22} aria-hidden="true" />
      <span>Starting the secure admin portal…</span>
    </main>
  );
}

function ConfigurationScreen() {
  return (
    <main className="config-screen">
      <Brand />
      <section className="config-card">
        <div className="config-icon"><Activity size={22} /></div>
        <p className="eyebrow">CONNECTION REQUIRED</p>
        <h1>Connect the TACResQ Admin Portal</h1>
        <p className="muted">
          Configure the Supabase URL and publishable key used by the TACResQ Admin Portal. The backend serves
          these browser-safe settings at runtime; the portal uses administrator-only RPCs and Row Level Security.
        </p>
        <pre><code>SUPABASE_URL=https://your-project.supabase.co<br />SUPABASE_PUBLISHABLE_KEY=your-publishable-key</code></pre>
        <p className="config-note">
          Use a publishable key only. Never place a Supabase service-role or secret key in a browser app.
        </p>
      </section>
    </main>
  );
}

function AuthGate() {
  const [session, setSession] = useState<Session | null>(null);
  const [checking, setChecking] = useState(true);

  useEffect(() => {
    const client = getSupabase();
    let mounted = true;
    let authEventVersion = 0;
    const initialAuthEventVersion = authEventVersion;
    const { data: { subscription } } = client.auth.onAuthStateChange((_event, nextSession) => {
      if (!mounted) return;
      // A live auth event is newer than the initial getSession() snapshot.
      // Prevent that slower snapshot from overwriting a sign-in/sign-out event.
      authEventVersion += 1;
      setSession(nextSession);
      setChecking(false);
    });

    void client.auth.getSession().then(({ data, error }) => {
      if (!mounted || authEventVersion !== initialAuthEventVersion) return;
      if (error) console.error("Unable to restore the Supabase session:", error.message);
      setSession(data.session);
      setChecking(false);
    });

    return () => {
      mounted = false;
      subscription.unsubscribe();
    };
  }, []);

  if (checking) return <LoadingScreen />;
  return <Home session={session} />;
}

export default function App() {
  const [initializing, setInitializing] = useState(true);
  const [configured, setConfigured] = useState(false);

  useEffect(() => {
    let active = true;
    void initializeSupabase().then((ready) => {
      if (active) setConfigured(ready);
    }).catch((error: unknown) => {
      console.error("Could not initialize the Supabase client:", error);
      if (active) setConfigured(false);
    }).finally(() => {
      if (active) setInitializing(false);
    });
    return () => { active = false; };
  }, []);

  if (initializing) return <LoadingScreen />;
  if (!configured) return <ConfigurationScreen />;
  return <AuthGate />;
}
