# A4 — Attachment screen: hero image + comment rewrite

**File:** `android/app/src/main/java/com/grapsee/grapmail/ui/screens/mail/MailSecondaryScreens.kt`
**Screen:** `AttachmentPreviewScreen` (unit 79; declared at line 478 in the 2026-11-18 snapshot). Depends on: A1 + A2.

## 1. Rewrite the doc comment (it is now false)

### Replace this exact comment (lines 469–476)

```kotlin
/**
 * 79 — an attachment's own screen.
 *
 * It reports size, type and where it came from, and saves the bytes. It does not
 * claim to preview formats the app cannot decode: an image is offered as a
 * download, not a thumbnail, because there is no image loader in this build and
 * pretending otherwise would show a broken box.
 */
```

### With

```kotlin
/**
 * 79 — an attachment's own screen.
 *
 * It reports size, type and where it came from, and saves the bytes. Images are
 * drawn as a thumbnail through the same authenticated fetch the download button
 * uses (fixpack A2, Coil); every other format keeps the type icon rather than a
 * box that would decode to nothing.
 */
```

## 2. Replace the hero icon block with the thumbnail

### Replace this exact block (lines 514–517)

```kotlin
            Box(Modifier.size(84.dp).clip(RoundedCornerShape(18.dp)).background(palette.softFill),
                contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.AttachFile, null, tint = palette.primarySolid, modifier = Modifier.size(34.dp))
            }
```

### With

```kotlin
            AttachmentThumb(
                vm = vm,
                attachment = a,
                modifier = Modifier.size(84.dp).clip(RoundedCornerShape(18.dp))
                    .background(palette.softFill),
            ) {
                Icon(Icons.Rounded.AttachFile, null, tint = palette.primarySolid, modifier = Modifier.size(34.dp))
            }
```

## Notes

- `a` (`val a = entry?.attachment`) is smart-cast non-null after the early-return guard two screens up (`if (a == null || deleted) { …; return@Column }`), so it can be passed directly.
- The `Save to this device` / `Open in another app` / `Remove from this message` buttons and the delete two-step are untouched.
- While an image is being fetched (first view of an undownloaded attachment) the 84dp hero shows the old paperclip icon; it is replaced by the image as soon as the fetch through `attachmentPreviewFile` completes — no layout jump, same 84dp box.
- Drift check: `grep -n "no image loader" ui/screens/mail/MailSecondaryScreens.kt` finds the comment; `grep -n "Modifier.size(84.dp)"` finds the hero.

## Verify

Build, open a message attachment that is a photo: the hero area draws the image within a second on Wi-Fi, and instantly on re-open (disk cache). A PDF attachment still shows the icon, and Save/Open behave exactly as before.
