'use client';

/**
 * GrapMail — real reCAPTCHA v2 checkbox component (explicit render).
 *
 * Renders the genuine "I'm not a robot" checkbox that escalates to image
 * challenges when Google's risk analysis demands it — the exact interaction
 * users know from other platforms.
 *
 * Usage (see PATCHES-web.md):
 *   <RecaptchaV2
 *     ref={captchaRef}
 *     siteKey={process.env.NEXT_PUBLIC_RECAPTCHA_V2_SITE_KEY!}
 *     onVerify={(token) => { ... }}
 *     onExpire={() => { ... }}   // token expired (~2 min) — treat as "not verified"
 *     onError={() => { ... }}    // network error — ask the user to retry
 *   />
 *
 * Notes:
 * - Exactly one v2 widget should be rendered per page.
 * - Never call reset() before the widget has rendered.
 * - The token is single-use and expires in ~2 minutes: fetch a fresh token at
 *   the moment the user submits, don't hold one from minutes ago.
 */

import { forwardRef, useEffect, useImperativeHandle, useRef } from 'react';

declare global {
  interface Window {
    grecaptcha?: {
      render: (el: HTMLElement, params: Record<string, unknown>) => number;
      reset: (id?: number) => void;
      getResponse: (id?: number) => string;
    };
    __grapmailRecaptchaOnLoad?: () => void;
  }
}

export type RecaptchaHandle = {
  /** Reset the widget (e.g. after a failed server-side verification). */
  reset: () => void;
  /** Current token, or '' when unsolved/expired. */
  getResponse: () => string;
};

type RecaptchaV2Props = {
  siteKey: string;
  onVerify: (token: string) => void;
  onExpire?: () => void;
  onError?: () => void;
  theme?: 'light' | 'dark';
};

const SCRIPT_ID = 'grapmail-recaptcha-script';
const SCRIPT_SRC =
  'https://www.google.com/recaptcha/api.js?render=explicit&onload=__grapmailRecaptchaOnLoad';

export const RecaptchaV2 = forwardRef<RecaptchaHandle, RecaptchaV2Props>(function RecaptchaV2(
  { siteKey, onVerify, onExpire, onError, theme = 'light' },
  ref,
) {
  const containerRef = useRef<HTMLDivElement | null>(null);
  const widgetIdRef = useRef<number | null>(null);

  useImperativeHandle(
    ref,
    () => ({
      reset: () => {
        if (widgetIdRef.current !== null) window.grecaptcha?.reset(widgetIdRef.current);
      },
      getResponse: () =>
        widgetIdRef.current !== null ? window.grecaptcha?.getResponse(widgetIdRef.current) ?? '' : '',
    }),
    [],
  );

  useEffect(() => {
    let cancelled = false;

    const renderWidget = () => {
      if (cancelled || widgetIdRef.current !== null) return;
      if (!containerRef.current || !window.grecaptcha) return;
      widgetIdRef.current = window.grecaptcha.render(containerRef.current, {
        sitekey: siteKey,
        theme,
        callback: (token: string) => onVerify(token),
        'expired-callback': () => onExpire?.(),
        'error-callback': () => onError?.(),
      });
    };

    if (window.grecaptcha) {
      renderWidget();
    } else {
      // api.js calls this global once loaded.
      window.__grapmailRecaptchaOnLoad = renderWidget;
      if (!document.getElementById(SCRIPT_ID)) {
        const script = document.createElement('script');
        script.id = SCRIPT_ID;
        script.src = SCRIPT_SRC;
        script.async = true;
        script.defer = true;
        document.body.appendChild(script);
      }
    }

    return () => {
      cancelled = true;
    };
  }, [siteKey, theme, onVerify, onExpire, onError]);

  return <div ref={containerRef} />;
});
