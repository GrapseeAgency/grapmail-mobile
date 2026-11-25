# Deploy Instructions — close the production 404 gap
Backend commit to deploy: f9c43384d0d2dff34a56b51714225055ffa42be7 (branch main)
Patch export: receipts/backend-f9c43384.patch

## Why this matters
Production currently returns 404 for /grapmail/files, /grapsee/auth/verify-human and /grapmail/notification-preview,
while all other grapmail routes answer 401 (auth-gated). Clients silently fall back to degraded behavior —
attachments, human-verification and notification previews are broken in production even though the code exists.

## Step 1 — get the commit onto the server (choose one)
Option A (patch; no git remote needed):
  1. copy receipts/backend-f9c43384.patch to the server
  2. cd <repo root> && git apply --check backend-f9c43384.patch && git apply backend-f9c43384.patch
     (use `git am` instead if you want the commit object preserved)
Option B (push from a machine that has credentials):
  git -C "F:\Grapsee All Time" push origin main      # then on the server: git pull --ff-only

## Step 2 — rebuild & restart (follow grapsee-backend/DEPLOY.md)
- Build/install the auth-service + shared workspace artifacts as per the repo's DEPLOY.md (cPanel/PM2 setup).
- Restart the PM2 process(es) that serve the API. No database or config changes are involved.

## Step 3 — smoke test (each line must NOT print 404)
  curl -s -o /dev/null -w "%{http_code}\n" https://api.iqracsc.com/grapmail/files
  curl -s -o /dev/null -w "%{http_code}\n" https://api.iqracsc.com/grapsee/auth/verify-human
  curl -s -o /dev/null -w "%{http_code}\n" https://api.iqracsc.com/grapmail/notification-preview
  curl -s -o /dev/null -w "%{http_code}\n" https://api.iqracsc.com/health
Expected after deploy: 401 / 401 / 401 / 200   (401 = route exists, auth-gated — correct for anonymous probes)

## Step 4 — rollback (if needed)
  git revert f9c43384   (or restore the two files from the patch), rebuild, restart. No data migrations involved.

## Notes
- Only two files are affected; nothing else in the repo needs to move.
- After deploy, re-run the smoke test and store the output as a receipt in receipts/.
