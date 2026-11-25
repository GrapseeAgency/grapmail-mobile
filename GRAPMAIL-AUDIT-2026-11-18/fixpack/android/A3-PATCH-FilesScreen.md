# A3 — Files screen: real thumbnails in the rows

**File:** `android/app/src/main/java/com/grapsee/grapmail/ui/screens/mail/MailSecondaryScreens.kt`
**Screen:** `FilesScreen` (unit 78; declared at line 420 in the 2026-11-18 snapshot). Depends on: A1 + A2.

## Replace this exact block (lines 441–445)

```kotlin
                            Box(Modifier.size(38.dp).clip(RoundedCornerShape(10.dp))
                                .background(palette.softFill), contentAlignment = Alignment.Center) {
                                Icon(Icons.Rounded.AttachFile, null, tint = palette.primarySolid,
                                    modifier = Modifier.size(18.dp))
                            }
```

## With

```kotlin
                            AttachmentThumb(
                                vm = vm,
                                attachment = entry.attachment,
                                modifier = Modifier.size(38.dp).clip(RoundedCornerShape(10.dp))
                                    .background(palette.softFill),
                            ) {
                                Icon(Icons.Rounded.AttachFile, null, tint = palette.primarySolid,
                                    modifier = Modifier.size(18.dp))
                            }
```

## Notes

- No import changes: `AttachmentThumb` (fixpack A2) lives in the same package, and the trailing lambda is the `fallback` slot.
- Behavior: image attachments get a 38dp rounded thumbnail; every other type — and any failed decode — keeps the exact icon block that was there before.
- Indentation matches the source block (28 spaces for the call line; this file is hand-formatted — keep it).
- If the file has drifted since this snapshot, locate the block with
  `grep -n "Modifier.size(38.dp)" ui/screens/mail/MailSecondaryScreens.kt`
  (it is the only 38dp box in the screen).

## Verify

Build (`assembleDebug`), open the Files tab: image rows show thumbnails, PDF/ZIP rows still show the paperclip, and a never-downloaded image shows the paperclip until Coil has fetched and cached it (then the thumbnail appears on the next pass).
