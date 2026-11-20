// Grapsee All Time — src/app/api/auth/verify-human/route.ts
//
// POST /api/auth/verify-human   body: { token: string }
//
// Verifies the reCAPTCHA response token with Google (siteverify) and, on
// success, returns a short-lived single-use verificationId. The register
// flow must present that id, and the backend consumes it exactly once.
//
// In production the same logic lives next to /auth/register on the API
// service behind API_BASE (see backend/verify-human.server.ts for the
// reference implementation). This route mirrors it inside the Next.js app
// so the frontend can be wired end-to-end from a single repo.

import { NextRequest, NextResponse } from 'next/server';
import { issueVerificationRecord } from '@/src/app/lib/humanVerification';

const SITEVERIFY_URL = 'https://www.google.com/recaptcha/api/siteverify';

export async function POST(req: NextRequest) {
  const secret = process.env.RECAPTCHA_V2_SECRET;
  if (!secret) {
    return NextResponse.json({ success: false, error: 'server_misconfigured' }, { status: 500 });
  }

  let token = '';
  try {
    const body = await req.json();
    if (typeof body?.token === 'string') token = body.token;
  } catch {
    // fall through to missing-response
  }
  if (!token) {
    return NextResponse.json({ success: false, error: 'missing-response' }, { status: 400 });
  }

  const form = new URLSearchParams({ secret, response: token });
  const ip = req.headers.get('x-forwarded-for')?.split(',')[0]?.trim();
  if (ip) form.set('remoteip', ip);

  let success = false;
  let codes: string[] = [];
  try {
    const res = await fetch(SITEVERIFY_URL, {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: form.toString(),
      cache: 'no-store',
    });
    const data = (await res.json()) as { success?: boolean; 'error-codes'?: string[] };
    success = data.success === true;
    codes = data['error-codes'] ?? [];
  } catch {
    // Vendor unreachable: fail closed, tell the client it may retry.
    return NextResponse.json({ success: false, error: 'vendor_unreachable' }, { status: 502 });
  }

  if (!success) {
    // Common codes: invalid-input-response (bad/expired), timeout-or-duplicate
    // (already used — fetch a fresh token). Treat all as "solve again".
    return NextResponse.json(
      { success: false, error: 'verification-failed', codes },
      { status: 400 },
    );
  }

  return NextResponse.json({ success: true, verificationId: issueVerificationRecord() });
}
