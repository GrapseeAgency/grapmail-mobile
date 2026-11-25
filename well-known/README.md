# `well-known/` — the files that let grapsee.com vouch for the GrapMail apps

These two files are what Android and iOS fetch from **grapsee.com** before they
will open a web link (`https://grapsee.com/mail/thread/<id>`) straight in the
app, and before iOS will offer passkeys for `grapsee.com` inside the app. They
live in this repo so the package name, bundle id and signing fingerprint they
name are reviewed beside the code that depends on them, and so
`scripts/mail-honesty.py` §39 can refuse a build whose manifest/entitlements and
these files disagree. **Committing them does not publish them**: they only work
once they are served from the live site, as described below.

| File | Served at | Read by |
|---|---|---|
| `assetlinks.json` | `https://grapsee.com/.well-known/assetlinks.json` | Android App Links (`android:autoVerify="true"` in `AndroidManifest.xml`) and Credential Manager passkeys |
| `apple-app-site-association` (no extension, JSON) | `https://grapsee.com/.well-known/apple-app-site-association` | iOS Universal Links (`applinks:grapsee.com`) and passkeys (`webcredentials:grapsee.com`) in `ios/project.yml` |

## The link formats these files cover

| Link | Works without the website files? |
|---|---|
| `grapmail://thread/<threadId>` | Yes (custom scheme) |
| `grapmail://message/<messageId>` | Yes |
| `grapmail://scheduled` | Yes (the widget and Live Activity already use it) |
| `grapmail://compose` / `grapmail://compose?to=<address>` | Yes |
| `mailto:<address>` | Android: yes, the app appears in the "send email" chooser. iOS: only if Apple grants the default-mail-app entitlement, which this repo does not request |
| `https://grapsee.com/mail/thread/<threadId>` | **No.** It opens the app only once the files below are live. Until then it opens the browser |

Ids are the server's UUIDs (`grapmail_messages.id` / `.thread_id`). Both apps
reject any link whose id is not a canonical 8-4-4-4-12 hex UUID.

---

## 1. Fill in the two values the repo cannot know

### Apple Team ID → `apple-app-site-association`

Replace **both** `TEAMID` occurrences with your 10-character Apple Developer
Team ID, so it reads e.g. `"ABCDE12345.com.grapsee.grapmail"`. You can find it at
<https://developer.apple.com/account> → Membership details → Team ID. It is the
same value as the `APPLE_TEAM_ID` GitHub secret that `ios.yml` injects at
release time. It is not a secret: every AASA file on the internet shows it.
`mail-honesty.py` §39 accepts the placeholder in the repo, but `ios.yml`'s
live check warns for as long as the served file still says `TEAMID`.

### Play App Signing key → `assetlinks.json`

The fingerprint already in the file,
`34:40:05:58:…:A3:7B:0F`, is the **upload** certificate that `android.yml`
verifies (`34400558533b1e70…bd714a37b0f`). If the app is published with **Play
App Signing** (the default for new apps), Google re-signs every install with
its own key, so devices see **that** fingerprint instead, and verification fails
unless it is listed too.

Find it in Play Console → your app → **Test and release → App integrity → App
signing** → "App signing key certificate" → **SHA-256 certificate
fingerprint**. Play Console also offers a ready-made "Digital Asset Links JSON"
snippet on the same page. Add the fingerprint as a second entry, keeping the
upload one (it is what debug/side-loaded release builds are signed with):

```json
"sha256_cert_fingerprints": [
  "34:40:05:58:53:3B:1E:70:63:93:90:B4:DB:E7:4E:8A:E6:6D:A3:84:00:21:5B:7D:49:F8:8B:D7:14:A3:7B:0F",
  "PASTE:THE:PLAY:APP:SIGNING:KEY:SHA256:HERE"
]
```

JSON allows no comments, which is why this slot is documented here rather than
inside the file. Every entry must be 32 colon-separated uppercase hex pairs;
§39 refuses anything else.

---

## 2. Publish them on cPanel

grapsee.com runs the Next.js server (`output: 'standalone'`). The site already
answers some `/.well-known/*` paths itself (`security.txt` and `ai.txt` from
`public/.well-known/`, plus `jwks.json` and `openid-configuration` as routes).
That gives two ways to publish. **Use A if you can**, because a redeploy cannot
silently wipe the files.

### A. With the website's own files (recommended)

Copy both files into the **web** repo at
`F:/Grapsee All Time/public/.well-known/` (next to `security.txt`), then build
and deploy the site as you normally do. Next.js serves `public/` as static
files. Then do the content-type check in section 3. If
`apple-app-site-association` comes back as `application/octet-stream`, add a
`headers()` entry for `/.well-known/apple-app-site-association` in
`next.config.mjs` setting `Content-Type: application/json`, or use B.

### B. Directly in cPanel

1. cPanel → **File Manager** → open `public_html/` (the document root of
   grapsee.com; with an Addon/Node.js app setup, use the directory the domain
   points at). Click **Settings** and enable **Show Hidden Files (dotfiles)**.
2. Create the folder `.well-known` if it does not exist (it often does,
   because AutoSSL uses `.well-known/acme-challenge`). Do not delete
   anything already inside it.
3. **Upload** `assetlinks.json` and `apple-app-site-association` into
   `public_html/.well-known/`. Keep the AASA filename exactly as it is: no
   `.json`, no `.txt`. Windows may try to add an extension, so check the name
   in File Manager after uploading.
4. In the same `public_html/.well-known/` folder, create a file named
   `.htaccess` with exactly these lines (or add them if one exists):

   ```apache
   # Served as-is for Android App Links / iOS Universal Links verification.
   <Files "apple-app-site-association">
       ForceType application/json
   </Files>
   <Files "assetlinks.json">
       ForceType application/json
   </Files>
   # Belt and braces where mod_headers exists. Guarded, because a bare
   # `Header` line on a host without the module answers 500 for the folder.
   <IfModule mod_headers.c>
       <FilesMatch "^(apple-app-site-association|assetlinks\.json)$">
           Header set Content-Type "application/json"
       </FilesMatch>
   </IfModule>
   # Never redirect, rewrite or SPA-fallback these two: both platforms refuse a
   # redirect, and the site's own .htaccess sends unknown paths to index.html.
   RewriteEngine Off
   ```

   `RewriteEngine Off` matters. The site's root `.htaccess` rewrites every
   missing path to `/index.html` and sets `ErrorDocument 404 /index.html`, so
   a typo'd filename would come back as an HTML page, which neither platform
   accepts.
5. cPanel → **Domains** (or **Redirects**): make sure nothing redirects
   `https://grapsee.com/.well-known/...`. That means no forced `www.`, and no
   http→https redirect *on the https URL itself*. Apple and Google fetch the
   https URL and **do not follow redirects**.
6. If the Node.js app (cPanel "Setup Node.js App"/Passenger) owns every request
   so Apache never serves `public_html` files, method B cannot work. Use A.

---

## 3. Verify

Run from any terminal (Git Bash, WSL, macOS):

```bash
curl -sI https://grapsee.com/.well-known/assetlinks.json
curl -sI https://grapsee.com/.well-known/apple-app-site-association
```

Each must show `HTTP/2 200` (or `HTTP/1.1 200`), a
`content-type: application/json` header, and **no** `location:` header. Then
check the bodies are the JSON, not the site's HTML:

```bash
curl -s https://grapsee.com/.well-known/assetlinks.json | python3 -m json.tool
curl -s https://grapsee.com/.well-known/apple-app-site-association | python3 -m json.tool
```

- **Google's Digital Asset Links tester**
  <https://developers.google.com/digital-asset-links/tools/generator>: enter
  host `grapsee.com`, package `com.grapsee.grapmail` and the SHA-256
  fingerprint(s), then press "Test statement". Or call the API directly:
  `https://digitalassetlinks.googleapis.com/v1/statements:list?source.web.site=https://grapsee.com&relation=delegate_permission/common.handle_all_urls`
- **On an Android device** with the Play build installed:
  `adb shell pm verify-app-links --re-verify com.grapsee.grapmail`, then
  `adb shell pm get-app-links com.grapsee.grapmail`, which should list
  `grapsee.com: verified`. Play Console → **Grow → Deep links** shows the same
  status per domain.
- **Apple**: Apple's CDN caches the file, so fetch it the way devices do:
  `curl -s https://app-site-association.cdn-apple.com/a/v1/grapsee.com`.
  It can take up to ~24 h to pick up a change. On a device, Settings →
  Developer → **Associated Domains Development** plus the `?mode=developer`
  suffix (`applinks:grapsee.com?mode=developer`) skips the CDN for debug
  builds. Search "Universal Links" in Apple's developer docs for their
  validation guidance. Third-party validators (e.g. branch.io's AASA
  validator) are handy but not authoritative.
- **CI**: `android.yml` and `ios.yml` each have a non-failing step,
  "Well-known link files are live (warning only)", that curls both URLs on
  every run and prints a `::warning::` while either one is missing,
  redirected, not JSON, or (for the AASA) still carries `TEAMID`.

## 4. Store-side settings

- **Apple Developer portal** → Certificates, IDs & Profiles → Identifiers →
  `com.grapsee.grapmail` → enable **Associated Domains**. Then **regenerate
  the App Store provisioning profile** and update the
  `APPSTORE_PROVISIONING_PROFILE` GitHub secret. The release job exports with
  that profile, and a profile without the capability makes the export reject
  the new `com.apple.developer.associated-domains` entitlement.
- **Play Console**: nothing to switch on. Once `assetlinks.json` is live with
  the app-signing fingerprint, Android verifies at install/update time.
  **Grow → Deep links** reports the result.

## 5. What a person without the app sees

`https://grapsee.com/mail/thread/<id>` is also an ordinary web URL. If the app
isn't installed, or verification hasn't passed yet, the browser opens it.
**The website has no `/mail/thread/[id]` route today.** `src/app/(mail)/mail/`
has no `thread` segment, so the link lands on the site's 404. Until a page
exists there (the simplest useful one: sign in if needed, then open that
conversation in the web mailbox, plus "Get the app" badges), only share the
https form with people who have the app, or share the `grapmail://` form. That
page is web work and is intentionally not part of this change.
