/**
 * GrapMail — reference implementation of the human-verification server gate.
 *
 * This is what makes the check UNSPKIPPABLE: the API service behind API_BASE
 * issues a single-use verification record only after Google confirms the
 * reCAPTCHA token, and account creation (plus any later onboarding step that
 * matters) refuses to proceed without consuming such a record exactly once.
 *
 * Framework-agnostic — the wiring sketch at the bottom shows the two endpoints
 * you need; port them into the GrapMail API service that also serves
 * /auth/register (the web app reaches it through its /api/grapsee proxy, the
 * mobile apps reach it directly). Storage below is an in-memory fallback for
 * a single instance; production should use Redis or an atomic DB update so the
 * consume-exactly-once property survives restarts and scales horizontally.
 */

import crypto from 'node:crypto';

// ── 1. Google siteverify ─────────────────────────────────────────────────────

const SITEVERIFY_URL = 'https://www.google.com/recaptcha/api/siteverify';

export interface SiteverifyResult {
  ok: boolean;
  codes: string[];
}

export async function verifyRecaptchaToken(token: string, remoteIp?: string): Promise<SiteverifyResult> {
  const secret = process.env.RECAPTCHA_V2_SECRET;
  if (!secret) return { ok: false, codes: ['server-misconfigured'] };

  const form = new URLSearchParams({ secret, response: token });
  if (remoteIp) form.set('remoteip', remoteIp);

  try {
    const res = await fetch(SITEVERIFY_URL, {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: form.toString(),
      cache: 'no-store',
    });
    const data = (await res.json()) as { success?: boolean; 'error-codes'?: string[] };
    return { ok: data.success === true, codes: data['error-codes'] ?? [] };
  } catch {
    return { ok: false, codes: ['vendor_unreachable'] };
  }
}

// Common siteverify codes, for logging:
//   invalid-input-response  — token bad or already used (fetch a fresh one)
//   timeout-or-duplicate    — token older than ~2 min, or replayed
//   hostname-mismatch       — widget solved on a different origin

// ── 2. single-use verification records ───────────────────────────────────────

const RECORD_TTL_MS = 10 * 60 * 1000;

interface VerificationRecord {
  expiresAt: number;
  consumed: boolean;
}

// In-memory fallback (single instance only — fail-closed but not shared).
// Production: Redis — SETEX hv:<id> 600 1 ... consume via GETDEL (atomic).
//        or SQL   — UPDATE verifications SET consumed_at = now()
//                   WHERE id = ? AND consumed_at IS NULL AND expires_at > now()
//                   → proceed only when exactly one row was updated.
const records = new Map<string, VerificationRecord>();

export function issueVerification(): string {
  const id = crypto.randomUUID();
  records.set(id, { expiresAt: Date.now() + RECORD_TTL_MS, consumed: false });
  return id;
}

/** True only for a valid, unused, unexpired id; consumes it atomically. */
export function consumeVerification(id: unknown): boolean {
  if (typeof id !== 'string' || id.length === 0) return false;
  const rec = records.get(id);
  if (!rec || rec.consumed || rec.expiresAt < Date.now()) return false;
  rec.consumed = true;
  return true;
}

// ── 3. wiring sketch (Express-style; adapt to the real service) ──────────────
//
// POST /auth/verify-human   { token }                    → { success, verificationId }
// POST /auth/register       { ..., humanVerificationId } → 403 if not consumable
//
// app.post('/auth/verify-human', async (req, res) => {
//   const token = typeof req.body?.token === 'string' ? req.body.token : '';
//   if (!token) return res.status(400).json({ success: false, error: 'missing-response' });
//
//   const result = await verifyRecaptchaToken(token, req.ip);
//   if (!result.ok) {
//     return res.status(400).json({ success: false, error: 'verification-failed', codes: result.codes });
//   }
//   return res.json({ success: true, verificationId: issueVerification() });
// });
//
// app.post('/auth/register', async (req, res) => {
//   // Cheap validations first (duplicate email → 409 WITHOUT consuming, so a
//   // taken email does not silently burn a solved challenge)…
//   //
//   // …then the gate: consume exactly once, before any durable side effect.
//   if (!consumeVerification(req.body?.humanVerificationId)) {
//     return res.status(403).json({ error: 'human-verification-required' });
//   }
//   // …existing account creation…
// });
//
// If onboarding completion is a separate call, give it the same gate.
