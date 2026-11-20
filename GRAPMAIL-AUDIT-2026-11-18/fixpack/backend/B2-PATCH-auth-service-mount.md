# B2 — Mount the files route in auth-service

**File:** `services/auth-service/src/index.ts`
**Depends on:** B1 (`services/shared/src/routes/grapmail-files.ts`).

## 1. Import — line 38 (exact, verified in the 2026-11-18 snapshot)

### Anchor

```ts
import grapmailScheduledRoutes from '../../shared/src/routes/grapmail-scheduled'
```

### Add after it

```ts
import grapmailFilesRoutes from '../../shared/src/routes/grapmail-files'
```

(Path style with no extension matches the existing imports in this file. Note the attachments route is imported as a named export — `{ attachmentRoutes }` — while scheduled uses a default export; B1 provides both `grapmailFilesRoutes` (named) and a default export, so either style compiles.)

## 2. Mount — right after the scheduled mounts (lines ~123–126 in the snapshot)

### Anchor

```ts
app.use('/grapmail/scheduled', authMiddleware, requireAccountType(['USER']))
app.use('/grapmail/scheduled/*', authMiddleware, requireAccountType(['USER']))
app.route('/grapmail/scheduled', grapmailScheduledRoutes())
```

### Add after it

```ts
// Files list-all (audit fixpack B1). Same gate-on-the-prefix pattern as the
// routes above: the module stays a plain Hono app a test can drive, and a
// mounting mistake is a 401 rather than an unauthenticated read.
app.use('/grapmail/files', authMiddleware, requireAccountType(['USER']))
app.use('/grapmail/files/*', authMiddleware, requireAccountType(['USER']))
app.route('/grapmail/files', grapmailFilesRoutes())
```

## 3. Gateway

**No change.** `services/gateway/src/index.ts` already proxies everything:

```ts
app.all('/grapmail/*', (c) => proxyTo('auth', c))
```

The multipart special-case in the gateway only concerns `/grapmail/attachments` uploads, so a read-only JSON GET under `/grapmail/files` needs nothing.

## 4. After deploying

Run `fixpack/backend/B3-VERIFY-api.md`. A missing `userId` (middleware mis-registered) must answer `401`, never a list.
