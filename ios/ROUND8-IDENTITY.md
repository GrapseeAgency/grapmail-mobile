# Round 8 — Workstream A (GrapMail visual identity), iOS notes

Source of truth: `docs/design/round8-spec.md` §1 and §7.2. This file records what the iOS
build of Workstream A did and what it left for others. Marketing version stays `0.0.1`.

## Files

| File | What changed |
|---|---|
| `GrapMail/Theme.swift` | `GM.*` values moved to §1.12 (cobalt primary, new tertiary text, AA semantic inks, 10-hue avatar ramp). Every pre-Round-8 name still compiles; new names added (`primary`, `onPrimary`, `primaryPressed`, `primaryGradientTop/Bottom`, `primaryText`, `primaryContainer`, `onPrimaryContainer`, `primaryContainerStrong`, `focusRing`, `brandViolet`, `surface`, `surfaceRaised`, `selectedRow`, `borderSoft`, `scrim`, `successFill`, `successContainer`, `errorFill`, `errorContainer`, `warningContainer`, `info`, `ownMessageFill`). `Color.signatureGradient` (+ `signatureAngularGradient` for rings). `GMShape.thumb/dialog/sheet/pill/fab`. `GMElevation` + `.gmElevation(_:)` (light shadows, none in dark). |
| `NotificationShared/AvatarIdentity.swift` (new) | Pure-Foundation contract: palette hex tables, `normalize`, FNV-1a 32, `paletteIndex`, `initials`, `maskAddress`. Lives here because `NotificationShared/` is compiled into both the app and the notification extension (no `project.yml` change). |
| `GrapMail/Avatar.swift` (new) | `GMAvatar` (index, colour, gradient fill, initials, letter size, `maskAddress`, spoken masked-address label), `GMAvatarView` (photo → BIMI logo on plate → initials disc; 150 ms crossfade, none under Reduce Motion; decorative unless `spokenName`), `MailUI.tint` kept as a forwarder. |
| `GrapMail/MailComponents.swift` | `MailUI.tint` moved out; `SenderAvatar` now draws `GMAvatarView`; text-on-surface cobalt → `GM.primaryText`; multi-select row → `GM.selectedRow`; compose FAB fill → signature gradient + level-2-brand elevation. Layout unchanged. |
| `GrapMail/AccountComponents.swift` | Text/link/icon cobalt → `GM.primaryText`; focused code cell → `GM.focusRing`; chips → `success/warning` on their containers. Layout unchanged. (The account avatar itself is `AccountAvatar` in `MailChrome.swift`, which delegates to `SenderAvatar`, so it picks up the new disc without an edit there.) |
| `GrapMail/SenderPhotos.swift` | Remembers whether a sender's picture is a brand logo (`isBrandLogo`); BIMI SVGs render transparent and scaled to fill, so the view draws the theme plate (`#FFFFFF` / `#F4F5F7`) and the 1 pt `border`. |
| `GrapMail/Motion.swift` | `GMMotion.extraLong` (0.45 s), `GMMotion.celebration` (1.2 s), `reducedFade`, `gatedOrFade`. `gated` unchanged. |
| `NotificationService/NotificationService.swift` | The banner's sender disc uses the shared hue + initials instead of `systemBlue`. |
| `GrapMail/en.lproj/Identity.strings` (new) | The two masked-address VoiceOver strings. |
| `GrapMailTests/AvatarIdentityTests.swift` (new) | Spec FNV vectors, normalisation, palette ↔ theme agreement, AA for white on every hue, initials, `maskAddress`; reads `docs/design/avatar-hash-vectors.json` when present (skips otherwise). |

## Not on iOS

* "Match wallpaper" (§1.11) is Android-only; nothing built.
* The multi-select avatar flip (§1.6.4) is not drawn: the row keeps its existing checkbox
  column (`MessageRow.selectBox`), and moving selection onto the disc would change the row
  layout owned by `MailboxView.swift`.

## App icon direction (§1.14) — documentation only, no artwork touched

* Keep the envelope-and-letter mark (`design/grapmail-ios-approved-1024.png`).
* Background → signature gradient: top-left `#48A0FF` → centre `#1F5EFF` → bottom-right `#4B3FD9`.
* Letter: white → `#E6EDFF`. Envelope body: `#F4F7FF` → `#C9D6FF`. Drop the semi-transparent
  back flap at ≤ 48 px renders.
* iOS 18 variants: dark = cobalt envelope on `#0A0A0C`; tinted = system greyscale of the mark.
* The founder signs off before anyone edits `Assets.xcassets/AppIcon.appiconset`.

## Follow-ups for other owners

* `scripts/mail-honesty.py` §18 still pins the old 8-entry Google ramp (`AVATAR_LIGHT/DARK`,
  "eight light/dark pairs"). It must move to the 10-entry table in §1.6.1 (owner: Android
  Workstream A per §7.2). Theme.swift keeps the `color(light: 0x…, dark: 0x…)` literal form the
  pin parses.
* `docs/design/avatar-hash-vectors.json` is written by the web test; `AvatarIdentityTests`
  starts asserting it as soon as it lands.
* Screens outside Workstream A (`Components.swift`, auth/security views, reader) still draw
  link/text cobalt with `GM.primarySolid`; in dark that is #2F66FF text (< 4.5:1 on cards).
  Swap those to `GM.primaryText` when their owners next touch them.
* `MailRowPreview.swift` and `AppIntegration.swift` still call `MailUI.tint` (now the new hue)
  with their own flat disc; they can switch to `GMAvatarView(…, loadsPhoto: false)` /
  `GMAvatar` later.
