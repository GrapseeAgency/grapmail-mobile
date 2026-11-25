// Grapsee All Time — src/app/lib/humanVerification.ts
// Single-use verification records for the GrapMail human-verification gate.
//
// Lifecycle:
//   1. /api/auth/verify-human verifies the reCAPTCHA response token with Google
//      and stores a record, returning its id to the client.
//   2. The register / continue endpoint consumes the record EXACTLY ONCE.
//   3. Missing, expired, or already-consumed records fail closed (403).
//
// Production note: the in-memory Map below is a single-instance fallback for
// local development. In production use Redis (SETEX + atomic GETDEL) or a SQL
// row with `consumed_at IS NULL` guarded by an atomic UPDATE, so the
// "consume exactly once" property holds across instances.

export interface VerificationRecord {
  expiresAt: number;
  consumed: boolean;
}

export interface VerificationStore {
  set(id: string, record: VerificationRecord): void | Promise<void>;
  get(id: string): VerificationRecord | undefined | Promise<VerificationRecord | undefined>;
}

const VERIFICATION_TTL_MS = 10 * 60 * 1000; // client has 10 minutes to finish

type GlobalShape = typeof globalThis & {
  __grapmailVerifications?: Map<string, VerificationRecord>;
};

const g = globalThis as GlobalShape;
const memory: Map<string, VerificationRecord> =
  g.__grapmailVerifications ?? (g.__grapmailVerifications = new Map());

export const verificationStore: VerificationStore = {
  set(id, record) {
    memory.set(id, record);
  },
  get(id) {
    return memory.get(id);
  },
};

/** Issue a fresh single-use verification id (call after a successful siteverify). */
export function issueVerificationRecord(): string {
  const id = crypto.randomUUID();
  void verificationStore.set(id, { expiresAt: Date.now() + VERIFICATION_TTL_MS, consumed: false });
  return id;
}

/**
 * Consume a verification id exactly once.
 * Returns true only for a valid, unused, unexpired record.
 * The record is marked consumed immediately, so replays fail.
 */
export function consumeVerificationRecord(id: string): boolean {
  const rec = verificationStore.get(id) as VerificationRecord | undefined; // memory store is sync
  if (!rec || rec.consumed || rec.expiresAt < Date.now()) return false;
  rec.consumed = true;
  return true;
}
