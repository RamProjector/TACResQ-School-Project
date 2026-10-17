import { useEffect, useState } from "react";
import type { Session } from "@supabase/supabase-js";
import { Activity, LoaderCircle } from "lucide-react";
import Home from "@/pages/Home";
import { Brand } from "@/components/Brand";
import { getSupabase, isSupabaseConfigured } from "@/lib/supabase";

function LoadingScreen() {
  return (
    <main className="loading-screen" aria-live="polite">
      <Brand />
      <LoaderCircle className="spin" size={22} aria-hidden="true" />
      <span>Restoring secure dispatch session…</span>
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
        <h1>Connect the TACRU dispatch desk</h1>
        <p className="muted">
          Add the Supabase project URL and publishable key used by the TACResQ app. The browser client
          uses the existing authenticated RPCs and Row Level Security policies.
        </p>
        <pre><code>VITE_SUPABASE_URL=https://your-project.supabase.co<br />VITE_SUPABASE_PUBLISHABLE_KEY=your-publishable-key</code></pre>
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
    const { data: { subscription } } = client.auth.onAuthStateChange((_event, nextSession) => {
      if (!mounted) return;
      setSession(nextSession);
      setChecking(false);
    });

    void client.auth.getSession().then(({ data, error }) => {
      if (!mounted) return;
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
  if (!isSupabaseConfigured) return <ConfigurationScreen />;
  return <AuthGate />;
}
