'use client';

import { useCallback, useEffect, useRef, useState } from 'react';

type GoogleCredentialResponse = { credential?: string; select_by?: string };
type GoogleIdentity = {
  initialize: (config: {
    client_id: string;
    callback: (response: GoogleCredentialResponse) => void;
    auto_select?: boolean;
    cancel_on_tap_outside?: boolean;
    context?: 'signin' | 'signup' | 'use';
    error_callback?: (error: { type?: string }) => void;
  }) => void;
  renderButton: (target: HTMLElement, options: Record<string, string | number | boolean>) => void;
};
declare global {
  interface Window {
    google?: { accounts?: { id?: GoogleIdentity } };
  }
}

let gisScriptPromise: Promise<void> | null = null;

function loadGoogleIdentityServices(): Promise<void> {
  if (typeof window !== 'undefined' && window.google?.accounts?.id) return Promise.resolve();
  if (!gisScriptPromise) {
    gisScriptPromise = new Promise<void>((resolve, reject) => {
      const existing = document.querySelector<HTMLScriptElement>('script[data-qe-google-identity="true"]');
      const script = existing ?? document.createElement('script');
      const loaded = () => resolve();
      const failed = () => {
        gisScriptPromise = null;
        reject(new Error('Google sign-in could not be loaded. Check your connection and retry.'));
      };
      script.addEventListener('load', loaded, { once: true });
      script.addEventListener('error', failed, { once: true });
      if (!existing) {
        script.src = 'https://accounts.google.com/gsi/client';
        script.async = true;
        script.defer = true;
        script.dataset.qeGoogleIdentity = 'true';
        document.head.appendChild(script);
      }
      if (window.google?.accounts?.id) resolve();
    });
  }
  return gisScriptPromise;
}

type Props = {
  locale?: string;
  context?: 'signin' | 'signup' | 'use';
  endpoint?: string;
  onAuthenticated?: (result: Record<string, unknown>) => void;
};

export default function GoogleSignInButton({
  locale = 'en',
  context = 'signin',
  endpoint = '/api/v1/auth/google',
  onAuthenticated,
}: Props) {
  const target = useRef<HTMLDivElement>(null);
  const [error, setError] = useState('');
  const [ready, setReady] = useState(false);
  const [busy, setBusy] = useState(false);
  const clientId = (process.env.NEXT_PUBLIC_GOOGLE_CLIENT_ID || '').trim();

  const onCredential = useCallback(async (response: GoogleCredentialResponse) => {
    const credential = String(response.credential || '').trim();
    if (!credential) {
      setError('Google did not return an identity token. Please try again.');
      return;
    }
    setBusy(true);
    setError('');
    try {
      const result = await fetch(endpoint, {
        method: 'POST',
        credentials: 'same-origin',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ credential }),
      });
      const body = await result.json().catch(() => ({}));
      if (!result.ok) throw new Error(body.message || body.error || 'Google sign-in could not be verified.');
      if (body.gmailVerified !== true || !Array.isArray(body.verifiedMethods) || !body.verifiedMethods.includes('GMAIL')) {
        throw new Error('Google sign-in did not confirm a verified Gmail identity.');
      }
      if (onAuthenticated) {
        // Account-linking flows return verified identity data without issuing a new session.
        onAuthenticated(body as Record<string, unknown>);
      } else {
        // Never redirect based on a caller-controlled or incomplete role/workspace response.
        const parentSession =
          body.authenticated === true && body.workspace === 'PARENT' &&
          body.role === 'PARENT' && Number(body.userId) > 0;
        const learnerSession =
          body.authenticated === true && body.workspace === 'STUDENT' &&
          body.role === 'STUDENT' && Number(body.studentId) > 0;
        if (!parentSession && !learnerSession) {
          throw new Error('Could not confirm a valid account session. Please sign in again.');
        }
        window.location.assign(parentSession ? '/parent' : '/student');
      }
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : 'Google sign-in could not be completed.');
    } finally {
      setBusy(false);
    }
  }, [endpoint, onAuthenticated]);

  useEffect(() => {
    let active = true;
    setReady(false);
    setError('');
    if (!clientId) {
      setError('Google sign-in is not configured for this deployment yet.');
      return () => { active = false; };
    }
    loadGoogleIdentityServices().then(() => {
      if (!active || !target.current || !window.google?.accounts?.id) return;
      target.current.replaceChildren();
      window.google.accounts.id.initialize({
        client_id: clientId,
        callback: response => { void onCredential(response); },
        auto_select: false,
        cancel_on_tap_outside: true,
        context,
        error_callback: () => setError('Google sign-in was cancelled or could not be started.'),
      });
      window.google.accounts.id.renderButton(target.current, {
        type: 'standard',
        theme: 'outline',
        size: 'large',
        text: context === 'signup' ? 'signup_with' : 'continue_with',
        shape: 'pill',
        logo_alignment: 'left',
        width: 320,
        locale,
      });
      setReady(true);
    }).catch(reason => {
      if (active) setError(reason instanceof Error ? reason.message : 'Google sign-in could not be loaded.');
    });
    return () => { active = false; };
  }, [clientId, context, locale, onCredential]);

  return (
    <div className="qe-google-signin">
      <div ref={target} className="qe-google-signin__button" aria-label={context === 'signup' ? 'Sign up with Google' : 'Sign in with Google'} />
      {busy && <p role="status" className="auth-note">Verifying your Google account…</p>}
      {!ready && !error && <p role="status" className="auth-note">Loading secure Google sign-in…</p>}
      {error && <p className="auth-message is-error" role="alert">{error}</p>}
    </div>
  );
}
