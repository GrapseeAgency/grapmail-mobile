# B3 — API verification (files route + deploy-gated features)

Run against the deployed stack after B1/B2. Replace `<host>` with the public gateway host the web client uses. `TOKEN` = a real session bearer from a signed-in account.

## 1. Files route — unauthenticated must be 401

```bash
curl -i "https://<host>/grapmail/files"
# expect: HTTP 401 and {"error":"Unauthorized"} — NOT a list.
```

## 2. Files route — authenticated

```bash
curl -sS "https://<host>/grapmail/files" \
  -H "authorization: Bearer $TOKEN" -H "x-device-id: verify-cli" \
  | head -c 800
# expect: {"files":[{ "id", "messageId", "name", "mimeType", "sizeBytes", "createdAt", "messageSubject" }, …],"count":N}
```

Checks:
- `count` equals `files.length`.
- `messageSubject` is non-null for attachments whose message row exists (LEFT JOIN working).
- Paging: `?limit=2` returns ≤2 rows; `?limit=99999` clamps to 1000 (200 response, no error).

## 3. Through the web origin (gateway proxy)

Same request against the web app's host — expect the identical JSON (no gateway change needed; generic `/grapmail/*` proxy).

## 4. Deploy-gated features (same session token)

These were found **already implemented in the repo** — the checks below confirm the deployment carries them.

```bash
# search operators (path as mounted — see grapmail-search.ts)
curl -sS "https://<host>/grapmail/search?q=from:someone@example.com%20has:attachment" \
  -H "authorization: Bearer $TOKEN" | head -c 400

# scheduled send — the two controls after "send later"
curl -sS "https://<host>/grapmail/scheduled" -H "authorization: Bearer $TOKEN"

# phone login — until SMS_PROVIDER is configured this must be an honest 502,
# never a 500:
curl -i -X POST "https://<host>/phone/start" -H "content-type: application/json" \
  -d '{"phone":"+15550000000"}'
# expect: 502 {"error":{"code":"SMS_DELIVERY_UNAVAILABLE"}} until credentials exist.
```

## 5. Login envelope (regression check)

```bash
curl -sS -X POST "https://<host>/login" -H "content-type: application/json" \
  -d '{"email":"…","password":"…"}' | grep -i "passwordHash\|refreshToken\|otp" 
# expect: no matches — the toPublicUser allowlist must exclude these fields.
```
