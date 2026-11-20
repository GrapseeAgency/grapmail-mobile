# Repo Health & Recovery Notes — 2026-11-19 (for future rounds)

## Current state (verified)
- grapmail-mobile: branch feat/onboarding-native-scenes, HEAD 0fb5998e…, .git/index 44,011 bytes (healed), scoped status clean.
- Grapsee_All_Time (backend): branch main, HEAD f9c43384…, .git/index 376,126 bytes (healed), scoped status clean.
- No index.lock / stale locks remain.

## The disease that blocked commits (diagnosis)
The share's .git/index for both repos was damaged: truncated content + stale index.lock (EIO on mount).
Symptoms: every `git write-tree` failed with cache-tree verification errors ("invalid object <sha> for '<dir>'"),
and direct writes to the mount index never stuck. `git status` on the full repo also times out on this mount.

## The recovery recipe that works (used this round)
1. Sandbox index: `GIT_INDEX_FILE=/mnt/work/.../idx-<repo>`; `git read-tree HEAD`; `git add -- <paths>`
   (or `git add -A -- ios android` / the recorded staged list)
2. Write tree with: `git write-tree --missing-ok`   (skips cache-tree object checks; ids came from read-tree)
   Fallback if needed: rebuild a fresh "notree" index from a `git ls-files -s -z` listing via a small Python writer,
   then write-tree from that.
3. VERIFY before committing: `git ls-tree -r <tree>` vs `git ls-files -s` sorted listing diff must be empty.
4. Commit: `git commit-tree <tree> -p <old-head> -m "<msg>"`; then CAS: `git update-ref refs/heads/<branch> <new> <old>`.
5. Heal the mount index: copy the sandbox index over .git/index (size check), verify.

## Never do on this mount (learned the hard way)
- Direct read-tree/write-tree against the mount .git/index.
- Blind `git update-ref` (always pass the expected old value).
- Full-repo `git status` (times out) — use path-scoped status (`-- <paths>`).
- Long `format-patch` of large ranges in the foreground (takes minutes) — run it in the background with a generous timeout.

## Read-verification quirk (observed 2026-11-19 05:12 UTC)
After this folder was written, direct full-file reads on the share (md5sum / cmp / python / wc) began failing with
"Input/output error ... failed to close <path>" while file sizes and timestamps stayed correct. Diagnosis: the content
reads succeed (first 150 bytes readable; full copies match the source byte-for-byte) and only the close() on the read
handle errors — a close-time EIO quirk of this share, not data corruption. Writes and immediate read-backs work.

Verification recipe that works when you suspect this quirk:
1. cp <file-on-share> /mnt/work/check.tmp   (ignore the close error; verify the copy size)
2. cmp -s <sandbox-source-of-truth> /mnt/work/check.tmp && echo IDENTICAL
Do not conclude corruption from md5sum/cmp failing directly on the share; retry later or use the cp-back method.
(Cross-check: cat <file> | md5sum sometimes completes fully even when close reports errors.)
