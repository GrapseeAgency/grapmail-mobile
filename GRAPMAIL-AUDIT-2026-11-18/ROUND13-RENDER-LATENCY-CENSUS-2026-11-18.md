# Round 13 — Mail Render & Interaction Latency Census (iOS · Android)

**Date:** 2026-11-18 UTC (sandbox clock) · **Type:** Read-only static census · **Round 13** of the standing deepening mandate.

**Question answered:** *"Why does it feel slow / what is still incomplete?"* — inspected at the render layer: list containers, body pipeline, WebView behavior, caches, images, pagination. Verdict first: **no brutal render-path defect exists in either tree**; the client already mitigates the one lag source its own code comments acknowledge (a distant server). Details and every pin below.

## 1 · Lists and scroll cost
- **iOS mailbox** — native lazy `List` (MailboxView.swift; container comment ~L414); rows via `ForEach` (~L425) with stable box identity `.id(state.box)` (L437); `MessageRow` conforms to `Equatable` (L369) so unchanged rows skip re-evaluation; hairline divider at 1/displayScale (L374); next-page prefetch watches the last 4 visible rows and calls `loadMoreWhenReady` behind a `.task` gate (L386–388, L422).
- **Android mailbox** — `LazyColumn` (MailboxScreen.kt L466) with `itemsIndexed(key = { r.id }, contentType = "row")` (L493); near-end trigger (within 4 rows of the end) feeds a guarded `LaunchedEffect` → `vm.loadMoreWhenReady()` (L234, L241–242); state collected lifecycle-aware.
- **Threads** — Android keyed items (ThreadScreen.kt L233/L275); iOS `LazyVStack` + `ForEach` (MailReaderViews.swift L57/L67), single expanded card (`activeMessage`, L72).

## 2 · Body pipeline (the "open a mail" path)
- **iOS** — needs-gated: `.task(id: showsBody ? detailsAttempt : nil)` for details; `.task(id: needsFormattedBody ? RenderRequest(allowImages:attempt:) : nil)` for the formatted body, so consent toggles and retries re-fire cleanly; a failed refresh keeps the previous HTML (`if render.error == nil || formattedBody?.html == nil`); one `loadBody` call site (reader body region L458–496); snapshot-seeded bodies short-circuit in the model (MailboxModel.swift L1789–1790).
- **Android** — reader shows blocked-images banner + "Show images" affordance; body view fed at ThreadScreen.kt L542 with text/error fallbacks (L505–558); view-model render cache serves the images-off render only (read gate MailboxViewModel.kt L2299).

## 3 · WebView body rendering
- **iOS** (MailHtmlBody.swift) — page script disabled ("No page script, ever", L39); non-persistent data store (L40) so trackers cannot persist; reload guard keyed on html/allows/scheme (L53–65); DOM height measured via injected JS (L82–95) with a single settle re-measure at 0.65 s (L103).
- **Android** (MailHtmlBody.kt) — script off (same posture); `blockNetworkImage` + no-cache load flags; reload guard keyed on `feed.document` / `feed.allowImages` (L177); `loadDataWithBaseURL`; WebView destroyed on release (L185–191); measure with 60 ms + 650 ms settle.
- Net effect both sides: no page script, no persistent web storage, no unconsented network images, no reload churn; heights settle so lists don't jump.

## 4 · Caches and persistence
- **iOS** — bodies `NSCache` ≈8 MB / 96 items, cost-accounted (MailStore.swift L170–175; set ≈L440, get ≈L385, flush ≈L603); formatted renders `NSCache<NSString, CachedBody>` (count ≈24 / cost ≈8 MB; MailboxModel.swift L1777–1787; read L1792; write with cost = length × 2, L1808); thread index ≈24 entries / 30 s (MailStore.swift ≈L163); per-search `PageCache` struct with `maxCachedSearches = 8`; `snapshotVersion = 1`.
- **Android** — formatted renders `android.util.LruCache(24)` (MailboxViewModel.kt L2282; read L2299; write L2312); test-only snapshot seam (`snapshotBodies` / `seedBodyForSnapshot`); disk snapshot v2 (data/MailStore.kt L42–74): debounced atomic rename; "the first frame of a cold open is the mail that was on screen when the process died"; bodies deliberately **not** persisted ("payload-heavy, they warm through prefetch"); `MAX_CACHED_SEARCHES = 8`.

## 5 · Images and attachments
- **iOS** — attachments open via QuickLook path (SecondaryViews.swift L613–617); avatars decode in-app (avatar thumbnail; APIClient.swift L1626–1635). The dormant `RemoteImageView` remains unreferenced (Round 10; MailHandoff.swift L169 def only).
- **Android** — Coil3 `AttachmentThumb`; documented limitation: non-previewable types download the whole file in a single pass to build an inline thumbnail. The corresponding dormant remote-image loader stands (Round 10).

## 6 · Pagination and concurrency
- Both platforms fetch 25/page (Android Api.kt L1257; iOS MailAPI.swift L1385). Android warms bodies via prefetch as soon as a page lands (data/MailStore.kt ≈L66). The only blocking primitive found (`runBlocking`) is on an auth path (Api.kt L749) — off the render path.

## 7 · Verdict
- **No brutal render-path defect.** The checked pipeline matches mainstream-client practice end to end: keyed lazy lists, equatable rows, single-load guard-reloaded script-free body webviews, bounded cost-accounted caches, near-end pagination prefetch, lifecycle-aware collection, deliberate cold-open snapshot.
- The only lag source the code itself names is a **distant server** ("on a distant server that is seconds of nothing, twice a day, forever" — snapshot rationale, data/MailStore.kt). First paint is snapshot-backed; remaining latency is backend/deploy posture (push, HTML handling, scheduled-send worker, etc. still deploy-gated per the research report), not client rendering.
- Micro-observations (documented, not defects): Android inline thumbnails use whole-file single-pass downloads; iOS settle re-measure is one fixed 0.65 s pass; both keep body renders only for the images-off mode by design.
- Residuals unchanged: iOS `ScheduledView` = sole orphan (Round 12); dormant remote-image loaders (Round 10).

**Files touched:** none (census round). **Delivery:** COS `ROUND13-RENDER-LATENCY-CENSUS-<date>.md`; mounts unattached — backfill queued. **Follow-up:** the internet half of the mandate (perceived slowness vs. competitors; why interactive preview remains incomplete) proceeds next through the Deep Research pipeline (multi-source, citation-checked, md + html).
