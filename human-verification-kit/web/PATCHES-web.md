# PATCHES — Web (Grapsee All Time, Next.js)

Applies the real reCAPTCHA v2 checkbox to the signup flow and makes the
challenge a hard requirement for account creation.

## How the pieces fit

```
 [Step 4 UI]  reCAPTCHA widget ──token──▶ POST ${API_BASE}/auth/verify-human
                                               │  (proxy → backend does siteverify)
                                        verificationId ◀─┘ (single-use, 10 min)
 [SUBMIT]  POST ${API_BASE}/auth/register { ..., humanVerificationId }
                                               │  backend consumes ONCE, then creates the account
                                        OK, or 403 human-verification-required
                                               (missing / expired / replayed)
```

`API_BASE` comes from `src/app/lib/apiBase.ts` (browser → `/api/grapsee` proxy;
the same path `/auth/register` already uses). The backend endpoint and the
register gate live in `backend/` of this kit — apply that FIRST, or the web UI
will call a route that does not exist yet.

## Files to copy

| Kit file | Goes to | Required? |
|---|---|---|
| `web/01_RecaptchaV2.tsx` | `src/components/RecaptchaV2.tsx` (create folder if missing) | yes |
| `web/04_verify-human-page.tsx` | `src/app/verify/human/page.tsx` (checkpoint page for the mobile WebViews) | yes |
| `web/03_verify-human.route.ts` | `src/app/api/auth/verify-human/route.ts` | optional — Next-local equivalent for previews; see note |
| `web/02_humanVerification.store.ts` | `src/app/lib/humanVerification.ts` | only if you use `web/03` |

Note on routing: production verification should run in the service behind
`API_BASE` (same place as `/auth/register`), because the single-use record must
be issued and consumed by the SAME service. `web/02`+`web/03` are a reference
port if you ever implement the gate inside Next instead.

## Environment

`.env.local` (and the hosting provider's env):

```
NEXT_PUBLIC_RECAPTCHA_V2_SITE_KEY=6L...   # v2 checkbox site key (browser-visible)
RECAPTCHA_V2_SECRET=6L...                 # server-side secret; goes wherever /auth/verify-human runs
```

- Register the site's production origin for the site key in the reCAPTCHA
  admin console (tag/domain list), or the widget will error.
- Dev tip: Google publishes v2 **test keys** that always pass (classic pair:
  site `6LeIxAcTAAAAAJcZVRqyHh71UMIEGNQ_MXjiZKhI`, secret
  `6LeIxAcTAAAAAGG-vFI1TnRWxMZNFuojJ4WifJWe`) — check the reCAPTCHA FAQ's
  developer section for the current values.

## Edits to `src/app/(auth)/signup/SignUpForm.tsx`

### 1. Imports

Extend the React import (around line 32):

```tsx
// before
import { useState } from 'react';
// after
import { useRef, useState } from 'react';
```

Add with the other component imports:

```tsx
import { RecaptchaV2, type RecaptchaHandle } from '@/src/components/RecaptchaV2';
```

(`API_BASE` is already imported.)

### 2. State

Next to the other `useState` calls (e.g. after `confirmationEmail`):

```tsx
const [humanVerificationId, setHumanVerificationId] = useState<string | null>(null);
const captchaRef = useRef<RecaptchaHandle>(null);
```

### 3. Exchange handler

Add above `handleSubmit` (or next to it):

```tsx
const handleCaptchaSolved = async (token: string) => {
  try {
    const res = await fetch(`${API_BASE}/auth/verify-human`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ token }),
    });
    const data = await res.json();
    if (res.ok && data.success && data.verificationId) {
      setHumanVerificationId(data.verificationId);
      setErrors(p => ({ ...p, submit: undefined }));
    } else {
      captchaRef.current?.reset();
      setErrors(p => ({ ...p, submit: 'The verification check failed — please try again.' }));
    }
  } catch {
    captchaRef.current?.reset();
    setErrors(p => ({ ...p, submit: 'Connection error during verification — please retry.' }));
  }
};
```

### 4. Gate the submit — `handleSubmit`

a) Right after `if (!validate()) return;` insert:

```tsx
if (!humanVerificationId) {
  setErrors(p => ({ ...p, submit: 'Please complete the human verification check first.' }));
  return;
}
```

b) Add the record id to the register payload:

```tsx
        body: JSON.stringify({
          name: `${form.firstName} ${form.lastName}`.trim(),
          email: form.email,
          password: form.password,
          clientType: form.accountType,
          companyName: form.companyName,
          projectType: form.projectType,
          budgetRange: form.budget,
          projectDescription: form.projectDescription,
          humanVerificationId,   // ← ADD THIS LINE
        }),
```

c) In the `if (!res.ok)` block add the fail-closed branch:

```tsx
      if (!res.ok) {
        if (res.status === 409) setErrors({ email: 'This email is already registered' });
        else if (res.status === 403 && data.error === 'human-verification-required') {
          // The single-use verification expired or was already used — solve again.
          setHumanVerificationId(null);
          captchaRef.current?.reset();
          setErrors({ submit: 'Your verification expired — please complete the check again.' });
        }
        else setErrors({ submit: data.error || 'Registration failed, please try again' });
        return;
      }
```

### 5. Render the widget on step 4

Inside the `{step === 4 && (<> … </>)}` fragment, immediately after the
terms/marketing checkboxes' wrapper (`space-y-4`) closes and before the
fragment's closing `</>`, insert (match the local indentation):

```tsx
                    {/* Human verification — mandatory before SUBMIT */}
                    <div className="pt-4">
                      <RecaptchaV2
                        ref={captchaRef}
                        siteKey={process.env.NEXT_PUBLIC_RECAPTCHA_V2_SITE_KEY!}
                        onVerify={handleCaptchaSolved}
                        onExpire={() => setHumanVerificationId(null)}
                        onError={() => {
                          captchaRef.current?.reset();
                          setErrors(p => ({ ...p, submit: 'The verification check failed to load — please retry.' }));
                        }}
                      />
                    </div>
```

Anchor (what it looks like before the edit):

```tsx
                          Receive project insights and agency updates by email.
                        </label>
                      </div>
                    </div>
                    // ← insert here
                  </>
```

Optional: the SUBMIT button could additionally show disabled state until
`humanVerificationId` exists, if your `Button` component supports `disabled`;
the guard in (a) already enforces the behavior.

## Verify (manual checklist)

1. Step 4 renders the genuine checkbox; hard puzzles escalate to image
   challenges — exactly the reference UX.
2. SUBMIT before solving → friendly error, and **no network request** fires.
3. Solve → SUBMIT → account created; the request body contains
   `humanVerificationId`.
4. Replay: resubmit with the same id (edit via dev tools) → **403**.
5. Leave the form open >10 min after solving, then SUBMIT → 403 → the widget
   resets and asks for a new check.
6. `npx tsc --noEmit` (or your typecheck script) is clean.
