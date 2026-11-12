# Perf & Smoothness Record — six rounds, code-read verified, no device

Founder mandate: full-app smoothness on Android + iOS, verified by reading
code only; builds stay with the founder; 551-pin honesty gate is the guard.

## What was slow, and what fixed it (all pushed)

| Dead air | Root cause found in code | Fix | Commit |
|---|---|---|---|
| Every swipe/star/archive | `actOn` waited the full server round trip before moving the row | Optimistic first paint; server reconciles; refusal re-reads the box and restores the row; undo banners still list only server-accepted ids | `9b9692b` |
| Bulk actions | one serial request per selected message, toolbar locked | 4-at-a-time (`Semaphore(4)` / `parallelWrites`), rows leave as each write lands | `9b9692b` |
| Reader opens | body fetched on every open | First screen's bodies prefetched into the same cache `loadBody` reads; first open is a cache hit | `9a299fb` |
| Home chrome | dead "Good morning" greeting composables (nothing composed them) | Deleted at source, both platforms | `9b9692b` |
| Compose affordance | bare 56dp circle FAB; unread badge was an 8dp dot | Extended "Compose" pill; count-pill badge capped "99+", from `/stats` | `ab76fa2` |
| Search pill clutter | sort/advanced glyphs inside the pill | Pill = one clean field; both sheets moved to the drawer's Search section, still reachable in every state | `a566bbc` |

Also folded in: the ChatGPT pass (lazy conversation rendering, off-main
encryption, network-layer simplification) — committed in `9b9692b`.

## Audited clean (evidence in transcript)

- Transport: shared OkHttp client, async enqueue, IO dispatchers, own
  dispatcher for token rotation. iOS: nonisolated statics + shared URLSession.
- Session store: IO-dispatched, keystore-encrypted, mutex-guarded writes.
- Cache (`MailStore` / `MailStore.swift`): in-memory, mutex, LRU bodies.
- Lists: Compose `items(key=id, contentType)` + `derivedStateOf` prefetch;
  SwiftUI lazy `List` with stable ids.
- Startup: `GrapMailApp.onCreate` is four cheap calls; system splash before
  `super.onCreate`; iOS launch = `UILaunchScreen` (Info.plist, XcodeGen).
- Poll: WorkManager 30-min floor (Android), `BGTaskScheduler` (iOS).
- Artwork clock: bounded — runs the 3.2s entrance, then stops; honors the
  reduced-motion switch.
- Live endpoints probed with a real session: stats/me/labels/assignments/
  inbox/screener/aliases all 200.
- Colors traced on both platforms: home chrome has no red; red only exists
  as trash-swipe tint and honest error banners.

## Deliberately NOT copied from the reference (Gmail)

Category summary rows ("Updates 99+ new" / "Promotions 99+ new"): the server
has no category column and `/inbox` takes no category filter. Drawing those
rows would put numbers on screen no system produced — the exact class of
claim the honesty pins exist to block. Backend needs a classifier first.

## The remaining variable

Every APK tested so far was a DEBUG build of a pre-fix commit. Debug Compose
without R8 runs the same code 2–4× slower. Release config is already correct
(minified, shrunk, non-debuggable, founder-key path wired).

Founder-side build (their stated job):

```bash
cd android
export CI_KEYSTORE_FILE=/home/arafat/grapmail-release.jks
export CI_KEYSTORE_PASSWORD=$(grep -i storePass /home/arafat/grapmail-release-credentials.txt | awk '{print $2}')
export CI_KEYSTORE_ALIAS=grapmail
./gradlew assembleRelease   # app/build/outputs/apk/release/app-release.apk
```

If a specific screen still lags or errors on the RELEASE build: report
screen + symptom + timing, and the next round starts there — not from zero.

## Gate

`python3 scripts/mail-honesty.py` — 551 pins hold at every commit above.
