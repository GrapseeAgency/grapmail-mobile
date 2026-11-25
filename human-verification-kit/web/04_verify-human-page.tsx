'use client';

/**
 * GrapMail — hosted human-verification checkpoint.
 *
 * One page, three uses:
 *  1. Android app: loaded in a WebView (…/verify/human?embed=android). On solve,
 *     the token is handed to the native bridge `window.GrapMailAndroid.onVerifyToken`.
 *  2. iOS app: same page (…?embed=ios). Token goes to the WKWebView message
 *     handler `window.webkit.messageHandlers.grapmailVerify`.
 *  3. Standalone browser: the page verifies the token itself against
 *     /api/auth/verify-human and shows the result.
 *
 * The reCAPTCHA token is single-use and expires in ~2 minutes — the page hands
 * it off immediately on solve and never stores it.
 *
 * Requirements: serve over HTTPS (reCAPTCHA requirement); the origin is already
 * registered with the site key in the reCAPTCHA admin console.
 */

import { useCallback, useEffect, useState } from 'react';
import type { CSSProperties } from 'react';
import { RecaptchaV2 } from '@/src/components/RecaptchaV2';
import { API_BASE } from '@/src/app/lib/apiBase';

declare global {
  interface Window {
    GrapMailAndroid?: { onVerifyToken?: (token: string) => void };
    webkit?: {
      messageHandlers?: {
        grapmailVerify?: { postMessage: (token: string) => void };
      };
    };
  }
}

type Status = 'idle' | 'handed_off' | 'verified' | 'error';

export default function VerifyHumanPage() {
  const [status, setStatus] = useState<Status>('idle');
  const [detail, setDetail] = useState('');
  const [embed, setEmbed] = useState(false);

  useEffect(() => {
    setEmbed(new URLSearchParams(window.location.search).has('embed'));
  }, []);

  const handleVerify = useCallback(async (token: string) => {
    setStatus('idle');
    setDetail('');

    // 1) Mobile WebViews — hand the token to the native app. The app performs
    //    the server-side exchange (POST /auth/verify-human) and owns the verdict.
    if (window.GrapMailAndroid?.onVerifyToken) {
      window.GrapMailAndroid.onVerifyToken(token);
      setStatus('handed_off');
      return;
    }
    if (window.webkit?.messageHandlers?.grapmailVerify) {
      window.webkit.messageHandlers.grapmailVerify.postMessage(token);
      setStatus('handed_off');
      return;
    }

    // 2) Standalone browser — verify against our own endpoint.
    try {
      const res = await fetch(`${API_BASE}/auth/verify-human`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ token }),
      });
      const data = (await res.json()) as { success?: boolean };
      if (data.success) {
        setStatus('verified');
      } else {
        setStatus('error');
        setDetail('Verification failed — please try again.');
      }
    } catch {
      setStatus('error');
      setDetail('Network error — please try again.');
    }
  }, []);

  const noticeStyle: CSSProperties = {
    fontSize: 13,
    color: 'rgba(255,255,255,0.75)',
    margin: '12px 0 0',
  };

  const cardStyle: CSSProperties = embed
    ? {}
    : {
        border: '1px solid rgba(255,255,255,0.12)',
        borderRadius: 20,
        background: '#14141b',
        padding: 20,
      };

  return (
    <main
      style={{
        minHeight: '100vh',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        padding: 24,
        background: embed ? 'transparent' : '#0b0b10',
        fontFamily: 'system-ui, -apple-system, sans-serif',
      }}
    >
      <div style={{ width: '100%', maxWidth: 360, textAlign: 'center', ...cardStyle }}>
        <div style={{ fontSize: 15, fontWeight: 600, color: '#ffffff' }}>Human verification</div>
        <p style={{ fontSize: 13, lineHeight: '19px', color: 'rgba(255,255,255,0.65)', margin: '8px 0 16px' }}>
          To keep GrapMail safe and spam-free, we need to make sure you&apos;re a real person.
          Complete the check below to continue.
        </p>

        <div style={{ display: 'flex', justifyContent: 'center', minHeight: 80 }}>
          <RecaptchaV2
            siteKey={process.env.NEXT_PUBLIC_RECAPTCHA_V2_SITE_KEY ?? ''}
            theme="dark"
            onVerify={handleVerify}
            onExpire={() => setStatus('idle')}
            onError={() => {
              setStatus('error');
              setDetail('The check failed to load — please reload and try again.');
            }}
          />
        </div>

        {status === 'handed_off' && <p style={noticeStyle}>Almost done — return to the app to finish.</p>}
        {status === 'verified' && <p style={noticeStyle}>Verified — you can continue.</p>}
        {status === 'error' && <p style={{ ...noticeStyle, color: '#ff6b6b' }}>{detail}</p>}

        <p style={{ fontSize: 11, lineHeight: '15px', color: 'rgba(255,255,255,0.4)', marginTop: 16, marginBottom: 0 }}>
          This site is protected by reCAPTCHA and the Google Privacy Policy and Terms of Service apply.
        </p>
      </div>
    </main>
  );
}
