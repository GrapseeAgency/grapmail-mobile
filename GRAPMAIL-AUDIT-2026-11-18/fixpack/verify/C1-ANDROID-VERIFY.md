# C1 — Android: build, manual, and performance acceptance

## Build

1. Apply `A1` (gradle) → `A2` (drop-in file) → `A3`/`A4`/`A5` (patches).
2. First proof:
   ```bash
   cd android
   ./gradlew :app:assembleDebug
   ./gradlew :app:dependencies --configuration debugRuntimeClasspath | grep -i coil
   ```
3. For anything performance-related, **release only**:
   ```bash
   ./gradlew :app:assembleRelease
   # install the release APK on the test device
   ```

## Manual acceptance (≈15 minutes)

- **Files tab:** an image attachment shows a 38dp rounded thumbnail; a PDF/ZIP keeps the paperclip icon; no row is ever a blank box.
- **Attachment screen:** opening an image draws the 84dp hero image; `Save to this device` and `Open in another app` behave exactly as before.
- **Airplane mode, cold open:** thumbnails fall back to the icon without a crash; nothing is fabricated. After going back online the next visit draws the image.
- **Account switch mid-flight:** sign out/in as another user while a thumbnail fetch is in flight — no stale image from the previous account appears.
- **Re-open:** second visit to the same image draws from the app cache — no repeated network fetch.

## Performance acceptance (release build only)

- Compare cold start + mailbox scroll against the debug APK. The gap is expected and normal: debug Compose disables R8/optimizations and runs 2–4× slower; Google's own docs measure ~30% code-execution speedup from first launch with Baseline Profiles alone, and community measurements of full debug→release differences are larger still.
- Context from the repo: `docs/PERF-AND-SMOOTHNESS-RECORD.md` lists the optimizations already merged (optimistic actions, parallel bulk paths, reader prefetch, search pill). The "slow" APKs in circulation were debug builds of pre-fix commits — install a release build before judging speed.

## Troubleshooting

- **`onError` not accepted on `AsyncImage`:** use the state-callback form instead — `onState = { state -> if (state is coil3.compose.AsyncImagePainter.State.Error) failed = true }` (add `import coil3.compose.AsyncImagePainter`). Both forms exist in the coil-compose API; the second is the older-compatible spelling.
- **Dependency resolution failure on Coil:** confirm `io.coil-kt.coil3:coil-compose:3.0.4` resolves from Maven Central (it does; version verified 2026-11-18). Do not jump to 3.1+ while the project is on Kotlin 2.0.20.

## Rollback

All five Android changes are additive/replacements with exact anchors in the patch pages (A1, A3, A4, A5); reverting is removing two gradle lines, one new file, and the three blocks. Nothing else in the module is touched.
