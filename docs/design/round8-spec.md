# GrapMail — Round 8 shared design spec

**Status:** source of truth for the Round 8 builders (Android, iOS, web). Docs only. No app code was changed while writing this.
**Date:** 2026-11-24
**Minimums:** iOS 16.1 (`ios/project.yml` `deploymentTarget: "16.1"`), Android minSdk 26 (Compose BOM 2024.09.03), web on Next 15 / Tailwind 4.
**Companion file:** `docs/design/security-overview.schema.json` (JSON Schema for §6).

What was read first: `android/.../ui/theme/Color.kt`, `Theme.kt`, `ui/components/Adaptive.kt`, `Motion.kt`, `Mail.kt` (`rampIndex`), `logic/GMHaptics.kt`, `ui/components/FeatureTip.kt`; `ios/GrapMail/Theme.swift`, `ScaledFont.swift`, `Motion.swift`, `Haptics.swift`, `FeatureTips.swift`, `MailComponents.swift` (`MailUI.tint`), `SessionStore.swift`, `RootView.swift`; web `src/styles/grapmail.css`, `src/app/(mail)/mail/page.tsx` (`AVATAR_COLOURS`, `colourFor`); `design/DESIGN_TOKENS.md`, the app icon PNGs, `mailboxUI.png`, `assets/ill_*` art; backend `routes/auth-devices.ts`, `auth-passkeys.ts`, `auth.ts`, `auth-account-profile.ts`, `lib/security-events.ts`, `routes/grapmail-avatars.ts`, migration `0040`.

**How to read this document.** "MUST" is required for the three platforms to match. Hex values are sRGB. Sizes are dp on Android, pt on iOS and CSS px on web (all 1:1). The contrast ratios given were worked out with the WCAG 2.x relative-luminance formula. Before merging, a builder MUST check them again with a tool (§1.12 lists the pairs).

---

## Contents

1. [GrapMail visual identity](#1-grapmail-visual-identity)
2. [Personal welcome-back sign-in](#2-personal-welcome-back-sign-in)
3. [Device & Security Center](#3-device--security-center)
4. [Account safety score + guided setup](#4-account-safety-score--guided-setup)
5. [Reader & conversation polish](#5-reader--conversation-polish)
6. [API contract for §3–4](#6-api-contract-for-34)
7. [Implementation split](#7-implementation-split)

---

## 1. GrapMail visual identity

### 1.1 The problem, and the direction

Today the three clients look like "a very good Gmail":

* The web mail tokens (`grapmail.css`) are Google's Material 3 greys and blues, value for value. Examples: `--gm-bg #f6f8fc`, `--gm-text #202124`, `--gm-text-secondary #5f6368`, `--gm-border #dadce0`, `--gm-accent #1a73e8`, `--gm-accent-strong #0b57d0`, dark `#8ab4f8`.
* The avatar ramp on all three platforms is Google's palette (`#1A73E8`, `#0B8043`, `#A142F4`, `#E37400`, …). The web picks a slot with a third hash, different from the mobile one.
* Material You dynamic colour is **on by default** on Android 12+ (`GrapMailTheme(dynamicColor = true)`). On most phones the brand blue never appears.

GrapMail already has its own look in the founder art. The app icon is an electric cobalt field: sky `#48A0FF` at the top-left, deep cobalt `#0055FF` at the bottom-right. On that field sits a frosted ice-white envelope. The illustrations (`assets/ill-welcome*.png`, `ill_sec_*`, `ill-created-*`) use the same glossy cobalt, with periwinkle/violet shadows (`GM.violet #9E8CFF` came from them), mint check badges and soft lavender clouds. The identity below is **derived from those sources**:

| Source | Colour sampled / observed | Becomes |
|---|---|---|
| App icon field, bottom-right | deep cobalt ≈ `#0055FF` | **Cobalt** primary family (`#1F5EFF`) |
| App icon field, top-left | sky ≈ `#48A0FF` | gradient start `#3D8BFF` |
| Illustration envelope shade, sparkles | periwinkle / violet ≈ `#9E8CFF`, indigo shadow | **Envelope violet** secondary (`#6A4DF4` / `#B4A6FF`) and gradient end `#5B47E0` |
| Illustration check badges | mint green | **Mint** success (`#0E9F6E` fill, `#08734F` text) |
| Founder-measured neutrals (`DESIGN_TOKENS.md`) | `#FBFCFE`, `#0F172A`, `#5B6B84`, `#0A0A0C`, `#141416` | kept, and **now also used on web** |

Three things set GrapMail apart from Gmail:
1. A purer, more electric **cobalt** in place of Google's muted `#1A73E8`.
2. The **signature gradient** (sky → cobalt → violet) on a few brand moments: compose FAB, safety ring, welcome-back avatar ring, app icon, celebration.
3. GrapMail's **own avatar palette and hash** (§1.6).

### 1.2 Brand accent (primary family)

| Token | Light | Dark | Use | Contrast check |
|---|---|---|---|---|
| `primary` | `#1F5EFF` | `#2F66FF` | Filled buttons, links (light), active tab, selected nav item fill, archive swipe | White on it: 5.1:1 light, 4.7:1 dark |
| `onPrimary` | `#FFFFFF` | `#FFFFFF` | Text and icons on `primary` | — |
| `primaryPressed` | `#1446E0` | `#2557EA` | Pressed / hover state of filled primary | White on it: 7.0 / 5.8 |
| `primaryGradientTop` | `#2465FF` | `#2F66FF` | Top of the vertical button gradient (replaces `primaryTop`) | White on it: 4.8 / 4.7 |
| `primaryGradientBottom` | `#1A4FF0` | `#2456E8` | Bottom of the button gradient (replaces `primaryBottom`) | White on it: 6.1 / 5.9 |
| `primaryText` | `#1F5EFF` | `#8EA8FF` | Links, text buttons, selected icons, progress, switches **on dark** | On bg: 5.0 light; 8.6 on `#141416` dark |
| `primaryContainer` | `#E6EDFF` | `#1A2550` | Selected chip, info banner, open row, "This device" badge | — |
| `onPrimaryContainer` | `#0B2A8F` | `#DCE4FF` | Text on `primaryContainer` | 10.3 / 11.6 |
| `primaryContainerStrong` | `#D3DEFF` | `#26326A` | Hover on container, selected + hovered | — |
| `buttonDisabled` | `#C7D5FF` | `#1D2B5C` | Disabled filled button (label stays `onPrimary` at 70 % alpha; disabled controls are exempt from WCAG) | — |
| `focusRing` | `#1F5EFF` | `#8EA8FF` | 2 dp ring, 2 dp outside the control | ≥3:1 against both canvases |

Rule for dark mode: **custom GrapMail filled buttons** (the ones drawn from `Palette` / `GM.*`) MUST use `primary #2F66FF` with white text. **Material components** take `MaterialTheme.colorScheme.primary` (switch track, `TextButton`, `CircularProgressIndicator`, `Checkbox`, `RadioButton`). In dark, that value MUST be `primaryText #8EA8FF`, with `colorScheme.onPrimary = #0A1A52` (7.2:1). This follows M3's light-on-dark convention for those components and still leaves the brand button cobalt.

### 1.3 Secondary accent and the signature gradient

| Token | Light | Dark | Use |
|---|---|---|---|
| `brandViolet` | `#6A4DF4` (white on it 5.3:1) | `#B4A6FF` (9:1 on `#141416`) | Sparingly: "Sent" tab tint, safety-score celebration, illustration-adjacent chips, the "You" accent in conversations is **not** violet (it is `primary`) |
| `signatureGradient` | linear 135°: `#3D8BFF` 0 % → `#1F5EFF` 55 % → `#5B47E0` 100 % | same stops | Compose FAB fill, safety ring progress (when level = strong), welcome-back avatar ring, celebration burst, app icon background. **Never** behind body text. A white glyph on it is ≥3.3:1 at the lightest stop (OK for icons ≥ 24 dp). |

Existing `GM.violet = 0x9E8CFF` (one value for both themes) becomes `brandViolet`, which has a light and a dark value. Android `Palette.tabTintSent #AC63FE` stays as it is this round.

### 1.4 Neutrals (unchanged on mobile except tertiary; web adopts them)

| Token | Light | Dark | Note |
|---|---|---|---|
| `bg` | `#FBFCFE` | `#0A0A0C` | app canvas |
| `bgTinted` | `#F7F9FC` | `#0D0D0F` | sign-in canvas |
| `surface` (`card`) | `#FFFFFF` | `#141416` | cards, reader pane, sheets |
| `surfaceRaised` (`cardAlt`) | `#F5F9FE` | `#1C1C20` | inset panels, collapsed message cards (dark) |
| `softFill` | `#EFF5FD` | `#232327` | hover, chips, pills, "show quoted text" |
| `selectedRow` **new** | `#EEF2FF` | `#1E2440` | multi-select row, hovered row (web) |
| `inputFill` | `#FFFFFF` | `#1A1A1E` | fields |
| `border` | `#E1E7F2` | `#2C2C31` | hairlines, field strokes |
| `borderSoft` | `#D9E6F9` | `#232327` | section dividers (dark value changes from `#2C2C31`) |
| `textPrimary` | `#0F172A` | `#EAEAEA` | 17.9:1 / 15.1:1 |
| `textSecondary` | `#5B6B84` | `#9E9EA5` | 5.4:1 / 6.9:1 |
| `textTertiary` | **`#657489`** (was `#94A3B8`, 2.6:1 — fails) | **`#808088`** (was `#67676D`, 3.5:1 — fails) | now 4.6:1 on `bg` / 4.7:1 on `card`. Timestamps, counters, captions must be readable. |
| `scrim` | `#0F172A` @ 40 % | `#000000` @ 60 % | behind sheets/dialogs |

### 1.5 Semantic colours

| Token | Light | Dark | Use |
|---|---|---|---|
| `success` (text/ink) | `#08734F` (was `success #10B981` + `successInk #0B8F64`, both < 4.5) | `#34D399` | success text, "Trusted" label text |
| `successFill` **new** | `#0E9F6E` | `#34D399` | check discs, done icons (non-text, ≥3:1) |
| `successContainer` | `#DCF8F0` | `#0A2A23` | success chip bg ("Trusted", "Done") |
| `error` (text, field stroke) | `#D92D2D` (was `#EF4444`, 3.8:1) | `#F87171` | error text and icons |
| `errorFill` **new** | `#D92D2D` | `#D92D2D` | destructive filled button, unread count badge, trash swipe, with white text (4.8:1) in **both** themes |
| `errorContainer` **new** | `#FDECEC` | `#3B1A1A` | error banners, `passwordChangeRequired` banner |
| `warning` (text) | `#B45309` (was `warnInk #C2620A`, 4.2:1) | `#F5A623` | "Needs attention" level word, warn chips |
| `warningContainer` | `#FDF0DE` | `#2B1F0B` | warn chips/banners |
| `star` | `#F5A623` | `#FDD663` | starred glyph. Its state is carried by filled vs outline shape, so colour contrast is not the only signal. |
| `info` | = `primaryText` | = `primaryText` | there is no separate info colour |

### 1.6 Avatar system

#### 1.6.1 Palette: 10 GrapMail hues

Every disc carries **white initials**, and every disc passes **WCAG AA (≥4.5:1) for white text in both themes**. The dark values are each light value × 0.9 per channel. That cuts glare on the near-black canvas and makes white contrast slightly higher. No entry is a Google value.

| Index | Name | Light | White-on contrast | Dark | White-on contrast |
|---|---|---|---|---|---|
| 0 | Cobalt | `#2456E8` | 5.9 | `#204DD1` | ≥ 6.9 |
| 1 | Indigo | `#5B47E0` | 6.1 | `#5240CA` | ≥ 7.1 |
| 2 | Grape | `#8E3FD1` | 5.6 | `#8039BC` | ≥ 6.5 |
| 3 | Berry | `#B5307A` | 5.7 | `#A32B6E` | ≥ 6.7 |
| 4 | Coral | `#C93A3A` | 5.1 | `#B53434` | ≥ 6.0 |
| 5 | Ember | `#B5520F` | 5.0 | `#A34A0E` | ≥ 5.9 |
| 6 | Fern | `#3B7D23` | 5.1 | `#357120` | ≥ 6.0 |
| 7 | Lagoon | `#0E7C7B` | 5.0 | `#0D706F` | ≥ 5.9 |
| 8 | Ocean | `#0B6FA8` | 5.5 | `#0A6497` | ≥ 6.4 |
| 9 | Slate | `#52607A` | 6.3 | `#4A566E` | ≥ 7.3 |

The ordering is part of the contract. Index N MUST be the same colour on all three platforms.

#### 1.6.2 Deterministic hash (identical on all platforms)

```
key   = normalize(address) or normalize(displayName) or "?"
hash  = FNV-1a 32-bit over the UTF-8 bytes of key
index = hash mod 10            // hash treated as UNSIGNED 32-bit
```

`normalize(s)`:
1. If `s` contains `<…>` (an RFC 5322 name-addr), take the part inside the angle brackets.
2. Trim ASCII whitespace.
3. Lowercase **ASCII `A–Z` only** (map to `a–z`). Do **not** use locale lowercasing. The app ships `tr` strings, and Turkish `İ`/`I` lowercasing would put the same sender on a different colour on a Turkish-locale device. Non-ASCII bytes pass through unchanged.
4. Do not strip `+tags` or dots. `a.b+news@x.com` is a different key from `ab@x.com` on purpose.

FNV-1a 32-bit: `h = 0x811C9DC5; for each byte b: h = h XOR b; h = (h × 0x01000193) mod 2³²`.

| Platform | Implementation note |
|---|---|
| Kotlin | `var h = 0x811C9DC5.toInt(); for (b in key.toByteArray(Charsets.UTF_8)) { h = h xor (b.toInt() and 0xFF); h *= 0x01000193 }; val index = (h.toLong() and 0xFFFFFFFFL) % 10` |
| Swift | `var h: UInt32 = 0x811C9DC5; for b in key.utf8 { h ^= UInt32(b); h = h &* 0x01000193 }; let index = Int(h % 10)` |
| TypeScript | `let h = 0x811c9dc5; for (const b of new TextEncoder().encode(key)) { h ^= b; h = Math.imul(h, 0x01000193) >>> 0 } ; const index = h % 10` |

Required unit-test vectors (the published FNV-1a 32 vectors; every platform MUST assert them):

| key | FNV-1a 32 | index (mod 10) |
|---|---|---|
| `""` | `0x811C9DC5` | 1 |
| `"a"` | `0xE40C292C` | 0 |
| `"b"` | `0xE70C2DE5` | 7 |
| `"c"` | `0xE60C2C52` | 8 |
| `"foobar"` | `0xBF9CF968` | 0 |

The web builder MUST also commit `docs/design/avatar-hash-vectors.json`: 20 realistic addresses (mixed case, `+tags`, Unicode local part, one `Name <addr>` form) with their hash and index. Its unit test generates the file; the Android and iOS tests read the same file, so all three are proven to agree.

This **replaces** Android `Mail.kt` `rampIndex` (31-multiplier, `and 0x3FFFFFFF`), iOS `MailUI.tint` (same) and web `colourFor` (31-multiplier `% 100000`). Old and new colours will differ for most senders. That is expected and ships once.

#### 1.6.3 Initials

* Use the display name if present, otherwise the address local part.
* Split on whitespace, `.`, `_`, `-`. Take the first **grapheme** of the first word, uppercased with the platform's default locale. If the disc is ≥ 56 dp and there are ≥ 2 words, add the first grapheme of the last word.
* Skip leading non-letter/digit characters (quotes, emoji). If none is left, show the person glyph (`person` / `person.fill` / lucide `User`) in white at 50 % of the disc size.
* Font: SemiBold, size = 0.42 × disc diameter for 1 letter, 0.36 × for 2 letters. Does **not** scale with Dynamic Type or font scale, because the disc does not either. Rows still grow at large text sizes.

#### 1.6.4 Shape, gradient, ring

| Property | Spec |
|---|---|
| Shape | Circle |
| Fill | Vertical gradient from the hue lightened 8 % (mix with white) at the top to the hue at the bottom. This is a subtle nod to the glossy illustration style; contrast is measured against the bottom (darker) stop. |
| Ring (dark theme only) | 1 dp inner hairline `#FFFFFF` @ 10 %, so discs separate from `#141416` |
| Selection ring | Multi-select: the disc flips (rotateY 180°, 220 ms Standard) to a `primary` disc with a white check. Reduce motion: crossfade 150 ms. |
| Sizes | list row 40 · conversation collapsed card 32 · conversation expanded header 40 · account switcher 32 · account chooser 48 · welcome-back hero 88 · compose recipient chip 24 · device card icon disc 40 (device glyph, not initials) |

#### 1.6.5 Photo and brand logo (BIMI) precedence

The backend already resolves `POST /grapmail/avatars/resolve` with `source: 'grapmail' | 'bimi' | 'fallback'` (`routes/grapmail-avatars.ts`).

1. `grapmail`: the sender's own GrapMail profile photo. Circle-cropped, `centerCrop`.
2. `bimi`: a verified brand logo. It is the **primary avatar** whenever present. Draw it inside the circle on a plate (`#FFFFFF` light / `#F4F5F7` dark) with a 1 dp `border` stroke, scaled to **fill** the circle. BIMI SVG Tiny PS logos are square and designed for circular cropping. Do not add padding and do not add a verified badge this round. The backend does not return a VMC flag yet, so a badge would claim something unproven.
3. `fallback`: the initials disc (§1.6.1–1.6.4).

While an image is loading, the initials disc shows at once (no shimmer) and the image crossfades in over 150 ms. Reduce motion: no crossfade; it swaps when decoded. If an image fails, the disc stays. A failed image is not retried more than once per session.

Accessibility: an avatar inside a row is decorative (`contentDescription = null`, `accessibilityHidden(true)`, `alt=""`) because the row already announces the sender. A standalone avatar (welcome-back, account chooser) is labelled "{display name}".

### 1.7 Icon style

| Platform | Set | Spec |
|---|---|---|
| Android | Material Symbols **Rounded** (`Icons.Rounded.*`; Symbols font for anything missing) | 24 dp, weight 400, outline by default; **filled** for the selected nav/tab state |
| iOS | SF Symbols | `.font(.system(size: 20, weight: .medium))` in toolbars, `.symbolVariant(.fill)` for selected; `.imageScale(.medium)` |
| Web | `lucide-react` (already a dependency) | `size={20}`, `strokeWidth={1.75}`; selected = the filled path where lucide has one, otherwise `primary` colour + `primaryContainer` pill |

Spot illustrations (empty and success states) MUST reuse the existing glossy art in `assets/` (`ill_sec_*`, `ill_account_*`, `ill-*`), always the theme-paired crop (`*-dark.png` in dark). No new illustration style.

### 1.8 Corner radii

| Token | Value | Existing name |
|---|---|---|
| `radiusSmall` | 10 | iOS `GMShape.small` |
| `radiusInput` | 14 | `GMShape.input` |
| `radiusButton` | 16 | `GMShape.button` |
| `radiusCard` | 20 | `GMShape.card` |
| `radiusThumb` **new** | 12 | attachment tiles, image grid |
| `radiusDialog` **new** | 24 | alert dialogs |
| `radiusSheet` **new** | 28 (top corners only) | bottom sheets |
| `radiusPill` | 999 | chips, badges, "show quoted text" |
| FAB | circle, 56 | — |

Android currently sets `MaterialTheme.shapes.extraSmall = 13.dp`. Change it to 10.dp (`radiusSmall`) and set `small 14`, `medium 20`, `large 24`, `extraLarge 28`. Web adds `--gm-radius-sm/input/button/card/thumb/dialog/sheet` vars.

### 1.9 Elevation

Light theme uses soft, cool shadows. Dark theme uses **no shadows**: it lifts surfaces with lighter fills instead.

| Level | Use | Light | Dark |
|---|---|---|---|
| 0 | list rows, flat cards | none, 1 dp `border` | none, 1 dp `border` |
| 1 | cards, message cards, device cards | `0 1 2 rgba(15,23,42,.06)`, `0 1 1 rgba(15,23,42,.04)` | `surface #141416` |
| 2 | menus, popovers, quick-reply bar | `0 8 24 rgba(15,23,42,.12)` | `surfaceRaised #1C1C20` + 1 dp `border` |
| 2-brand | compose FAB | `0 6 16 rgba(31,94,255,.28)` | none, signature gradient |
| 3 | sheets, dialogs | `0 -4 24 rgba(15,23,42,.16)` | `#1C1C20` + scrim |

Android: use `Modifier.shadow(elevation, shape, ambientColor, spotColor)` with the tinted colours rather than `tonalElevation` (`surfaceTint` is already transparent).

### 1.10 Motion

The existing tokens stay (Android `Motion.kt` `GMMotion`, iOS `Motion.swift` `GMMotion`):

| Token | Value |
|---|---|
| `short` | 150 ms |
| `medium` | 220 ms |
| `long` | 300 ms |
| **`extraLong` (new)** | 450 ms (sheet open, score-ring fill) |
| **`celebration` (new)** | 1200 ms total (confetti drift) |
| `Standard` | cubic-bezier(0.2, 0, 0, 1) |
| `EmphasizedDecelerate` | cubic-bezier(0.05, 0.7, 0.1, 1) — things entering |
| `EmphasizedAccelerate` | cubic-bezier(0.3, 0, 0.8, 0.15) — things leaving |
| `settle` | spring response 0.16 s, damping 0.6 |

Web: `--gm-ease-standard`, `--gm-ease-enter`, `--gm-ease-exit`, `--gm-dur-short/medium/long/xlong` CSS vars, and `framer-motion` transitions read the same numbers.

**Reduce motion (all platforms, MUST).** Android `rememberMotionReduced()`, iOS `@Environment(\.accessibilityReduceMotion)` + `GMMotion.gated(...)`, web `@media (prefers-reduced-motion: reduce)` / framer `useReducedMotion()`. When it is on:
* every translate, scale, rotate, shared-element or particle animation becomes a 150 ms opacity crossfade, or an instant change;
* scroll-linked header shrink snaps (see §5.4);
* progress rings render at their final value;
* no confetti.

Haptics are not motion and still play, subject to the system haptics setting.

### 1.11 "Match wallpaper" (Android Material You, opt-in)

* `GrapMailTheme(dynamicColor = …)` defaults to **false**. The GrapMail palette is the default on every device.
* New setting in **Settings → Appearance**. Only shown on API ≥ 31; below that it is hidden, not disabled.
  * Title: **"Match wallpaper"**
  * Subtitle: **"Use your wallpaper's colours for buttons and highlights. Avatars, status colours and text stay GrapMail's."**
  * Switch, default off. Persisted in DataStore key `gm.appearance.matchWallpaper` (Boolean) next to the existing display prefs (`data/MailDisplayPrefs.kt`).
* When on, the existing override block in `Theme.kt` applies, now mapped onto the new tokens: `primary`, `primaryGradientTop/Bottom`, `primaryPressed`, `primaryText`, `primaryContainer`, `onPrimaryContainer`, `buttonDisabled`, `focusRing`, `swipeArchive` ← dynamic scheme. Avatar hues, semantic colours, neutrals and the signature gradient never change.
* iOS and web have no equivalent; nothing to build there.

### 1.12 Token table (source of truth → platform names)

"Change" means the value or the name moves in Round 8. "New" means the token does not exist on that platform yet.

| Role | Light | Dark | Android (now) | iOS `GM.*` (now) | Web (now) | Change |
|---|---|---|---|---|---|---|
| primary (custom filled) | `#1F5EFF` | `#2F66FF` | `Palette.primarySolid` (`#1A6DF0`, dark same); `colorScheme.primary` in light | `GM.primarySolid` (`0x1A6DF0`/`0x1F6DFD`) | `--gm-accent-strong` (`#0b57d0` both) | **value** |
| primary gradient top | `#2465FF` | `#2F66FF` | `primaryTop` (`#206BFD`) | `GM.primaryTop` (`0x206BFD`/`0x1F6DFD`) | — | **value**, web **new** `--gm-accent-grad-top` |
| primary gradient bottom | `#1A4FF0` | `#2456E8` | `primaryBottom` (`#1560F5`) | `GM.primaryBottom` (`0x1560F5`) | — | **value**, web **new** `--gm-accent-grad-bottom` |
| primaryPressed | `#1446E0` | `#2557EA` | — | — | — | **new** `primaryPressed` / `GM.primaryPressed` / `--gm-accent-hover` |
| onPrimary | `#FFFFFF` | `#FFFFFF` | `onPrimary` | (literal `.white`) | `--gm-on-accent` | iOS **new** `GM.onPrimary` |
| primaryText | `#1F5EFF` | `#8EA8FF` | — (dark used `primarySolid`); `colorScheme.primary` in dark | — | `--gm-accent` (`#1a73e8`/`#8ab4f8`) | **new** mobile `primaryText` / `GM.primaryText`; web **value** |
| M3 onPrimary (dark only) | — | `#0A1A52` | `colorScheme.onPrimary` | n/a | n/a | **value** (dark) |
| primaryContainer | `#E6EDFF` | `#1A2550` | `Palette.infoFill` (`#E3ECFB`/`#232327`) → `colorScheme.primaryContainer` | `GM.infoFill` | `--gm-accent-soft` (`#e8f0fe`/`#2d3546`), `--gm-selected-on` | **value**; add alias `primaryContainer` |
| onPrimaryContainer | `#0B2A8F` | `#DCE4FF` | `colorScheme.onPrimaryContainer` (= textPrimary) | — | — | **new** `GM.onPrimaryContainer`, `--gm-on-accent-soft` |
| primaryContainerStrong | `#D3DEFF` | `#26326A` | — | — | `--gm-accent-soft-2` (`#d3e3fd`/`#39476b`) | **value**, mobile **new** |
| buttonDisabled | `#C7D5FF` | `#1D2B5C` | `buttonDisabled` (`#C4DBFD`/`#1E3A66`) | `GM.buttonDisabled` | — | **value**, web **new** `--gm-accent-disabled` |
| focusRing / cursor | `#1F5EFF` | `#8EA8FF` | `Palette.cursor` (dark = textPrimaryDark — **keep** for caret) | — | — | **new** `focusRing` / `GM.focusRing` / `--gm-focus` |
| brandViolet | `#6A4DF4` | `#B4A6FF` | — | `GM.violet` (`0x9E8CFF` both) | — | **new** Android; iOS **value + dark** |
| signatureGradient | 135° `#3D8BFF→#1F5EFF→#5B47E0` | same | — | `Color.brandGradient` (vertical primaryTop→Bottom) — keep for buttons | — | **new** `GMBrush.signature` / `Color.signatureGradient` / `--gm-signature` |
| bg | `#FBFCFE` | `#0A0A0C` | `bg` / `bgDark` | `GM.bg` | `--gm-bg` (`#f6f8fc`/`#1b1b1f`) | web **value** |
| bgTinted | `#F7F9FC` | `#0D0D0F` | `bgTinted` (dark mapped to `bgDark`) | `GM.bgTinted` | — | Android dark **value** `#0D0D0F` |
| surface | `#FFFFFF` | `#141416` | `card` / `cardDark` | `GM.card` | `--gm-surface` (`#fff`/`#232427`) | web dark **value** |
| surfaceRaised | `#F5F9FE` | `#1C1C20` | `cardAlt` | `GM.cardAlt` | `--gm-surface-alt` (`#f1f3f4`/`#2b2c30`) | web **value** |
| softFill / hover | `#EFF5FD` | `#232327` | `softFill` | `GM.softFill` | `--gm-hover` (`#e8eaed`/`#303134`) | web **value** |
| selectedRow | `#EEF2FF` | `#1E2440` | — | — | `--gm-selected` (`#f2f6fc`/`#2a2b2f`) | **new** mobile; web **value** |
| inputFill | `#FFFFFF` | `#1A1A1E` | `inputFill` | `GM.inputFill` | — | — |
| border | `#E1E7F2` | `#2C2C31` | `border` | `GM.border` | `--gm-border` (`#dadce0`/`#3c4043`), `--gm-border-strong` | web **value** |
| borderSoft | `#D9E6F9` | `#232327` | `borderSoft` (dark → `borderDark`) | — | `--gm-border-soft` (`#e8eaed`/`#2f3033`) | dark **value**; iOS **new** |
| textPrimary | `#0F172A` | `#EAEAEA` | `textPrimary` | `GM.textPrimary` | `--gm-text` (`#202124`/`#e3e3e3`) | web **value** |
| textSecondary | `#5B6B84` | `#9E9EA5` | `textSecondary` | `GM.textSecondary` | `--gm-text-secondary` (`#5f6368`/`#9aa0a6`) | web **value** |
| textTertiary | `#657489` | `#808088` | `textTertiary` (`#94A3B8`/`#67676D`) | `GM.textTertiary` | `--gm-text-tertiary` (`#80868b`) | **value** all |
| success (text) | `#08734F` | `#34D399` | `success` (`#10B981`), `successInk` (`#0B8F64`) | `GM.success`, `GM.successInk` | `--gm-success` (`#146c2e`/`#6dd58c`) | **value**; `successInk` becomes an alias of `success` |
| successFill | `#0E9F6E` | `#34D399` | — | — | — | **new** |
| successContainer | `#DCF8F0` | `#0A2A23` | `successChipBg` (`#DCF8F4`) | `GM.successChipBg` | `--gm-success-bg` (`#e6f4ea`/`#1f3b28`) | **value** (light tweak), web **value** |
| error (text) | `#D92D2D` | `#F87171` | `error` (`#EF4444`) | `GM.error` | `--gm-error` (`#b3261e`/`#f2857d`) | **value** |
| errorFill | `#D92D2D` | `#D92D2D` | `unreadBadge` (`#BC2828`), `swipeTrash` (`#DC2626`/`#F87171`) | `GM.swipeTrash` | `--gm-error-strong` (`#c5221f`/`#f2b8b5`), `--gm-unread` (`#f23f2d`) | **new** token; `unreadBadge`, `swipeTrash`, `--gm-unread`, `--gm-error-strong` all → `errorFill` |
| errorContainer | `#FDECEC` | `#3B1A1A` | — | — | `--gm-error-bg` (`#fce8e6`/`#3b1f1c`) | **new** mobile; web **value** |
| warning (text) | `#B45309` | `#F5A623` | `warnInk` (`#C2620A`) | `GM.warnInk`; `GM.warning` (`0xF5A623` single) | `--gm-warn` (`#e37400`/`#fbbc04`) | **value**; `GM.warning` gets a dark pair |
| warningContainer | `#FDF0DE` | `#2B1F0B` | `warnChipBg` | `GM.warnChipBg` | `--gm-warn-bg` (`#fef7e0`/`#3d3320`) | web **value** |
| star | `#F5A623` | `#FDD663` | `star` | `GM.star` | `--gm-star` (`#f4b400`/`#fdd663`) | web light **value** |
| swipeArchive | `#1F5EFF` | `#2F66FF` | `swipeArchive` (`#1A6DF0`/`#3B82F6`) | `GM.swipeArchive` | — | **value** |
| avatar ramp (10) | §1.6.1 light | §1.6.1 dark | `GrapMailAvatarRampLight/Dark` (8, Google) | `GM.avatarRamp` (8) | `--gm-avatar-1…8` + `AVATAR_COLOURS` | **replace**; web → `--gm-avatar-0…9`, and `AVATAR_COLOURS` stops borrowing accent/error/warn vars |
| ownMessageFill (§5) | `#F3F6FF` | `#161A26` | — | — | — | **new** |

Contrast pairs to verify with a tool before merge: every "White-on" entry in §1.2 and §1.6.1; `primaryText` on `bg` and `surface` (both themes); `onPrimaryContainer` on `primaryContainer`; `textTertiary` on `bg` and `surface`; `success`, `error`, `warning` on `surface` and on their containers.

`scripts/mail-honesty.py` pins the avatar ramp against the web list. Update it to the 10-entry table in the same change.

### 1.13 Type (reference; unchanged except the reader roles in §5.1)

H1 30/36 bold −0.4 · H2 22/28 bold −0.3 · Title 17/23 semibold · Body 15/22 · BodyMedium 15/22 medium · Caption 13/18 · CaptionMedium 13/18 medium · Micro 11/14 bold +1.2 tracking. iOS MUST go through `ScaledFont.swift` (`.gmFont(.token)`, `Font.gm(_:scaledFor:)`, `GMAdaptiveStack`, `.gmMaxWidth`). Android uses the `TextStyle` vals in `Theme.kt` (sp), so text follows font scale.

### 1.14 App icon direction (no new artwork this round)

* Keep the envelope-and-letter mark. It is recognisable and already approved (`design/grapmail-ios-approved-1024.png`, `grapmail-android-approved-512.png`).
* Retune the background to the signature gradient: top-left `#48A0FF` → centre `#1F5EFF` → bottom-right `#4B3FD9`. The faint violet corner ties the icon to the illustrations and sets it apart from every blue Google tile.
* Letter: white → `#E6EDFF`. Envelope body: `#F4F7FF` → `#C9D6FF`. Drop the semi-transparent back flap at ≤ 48 px renders (it muddies small launcher sizes).
* Android adaptive icon: gradient background layer; envelope foreground inside the 66 dp safe zone; **monochrome** layer from `design/mono-clean-432.png` for themed icons (Android 13+).
* iOS 18 variants: dark = cobalt envelope on `#0A0A0C`; tinted = system greyscale of the mark. Notification small icon (Android): 24 dp monochrome envelope outline.
* The founder signs off on these before anyone touches `mipmap-*` / `AppIcon.appiconset`.

---

## 2. Personal welcome-back sign-in

### 2.1 When it appears

On a cold start or a route to sign-in, the client shows the **welcome-back** screen when **all** of these hold:
1. there is **no** usable session for that account, and
2. the device has ≥ 1 **remembered account** (§2.4), and
3. the user did not tap "Use another account" in this launch (web: no `?fresh=1`).

Entry reasons, kept on the remembered record as `lastSignOutReason`:

| Reason | Trigger | Banner under the address (Caption, `textSecondary`) |
|---|---|---|
| `expired` | refresh rejected (`/auth/refresh` 401, or a session-revoked code) | "For your security, please sign in again." |
| `signed_out` | user tapped Sign out and left "Remove from this device" unticked | — (none) |
| `revoked` | session ended by another device, "This wasn't me", or "Sign out everywhere" | "You were signed out from another device." |

If two or more remembered accounts have no session, show the **account chooser** (§2.6) first. With exactly one, go straight to welcome-back.

Accounts that **are** signed in stay in the existing account switcher (`AccountSwitcher.swift` / Android account sheet) and never appear on these screens.

### 2.2 Welcome-back layout

**Phone portrait (Compact width, height ≥ 480):** a single centred column, `gmMaxWidth(420)`, gutter 24, scrollable. Top to bottom:

| # | Element | Spec |
|---|---|---|
| 1 | Wordmark | GrapMail logo + wordmark, height 28, top inset 24 + safe area (`GrapMailLogo.kt` / existing iOS logo view) |
| 2 | Spacer | 40 |
| 3 | Avatar | 88 circle (photo → initials disc, §1.6). 3 dp gap, then a 3 dp **signature-gradient ring** |
| 4 | Spacer | 16 |
| 5 | Display name | H2 22/28, `textPrimary`, centred, max 2 lines |
| 6 | Masked address | Body 15/22, `textSecondary`, centred, 1 line, middle-truncated |
| 7 | Reason banner | Caption, only for `expired` / `revoked` (table above), top margin 8 |
| 8 | Spacer | 32 |
| 9 | Primary button | Full width, height 54, radius 16, button gradient. Label **"Continue as {firstName}"** |
| 10 | Password field (only in the fallback state, §2.3 step 3) | Inserted between 8 and 9: label "Password", height 54, radius 14, show/hide eye, error text below |
| 11 | Spacer | 12 |
| 12 | "Use another account" | Text button, `primaryText`, BodyMedium, height 48, centred |
| 13 | Spacer | flexible (min 24) |
| 14 | "Remove from this device" | Text button, `textSecondary`, Caption-medium with a `person.crop.circle.badge.minus` / `PersonRemove` / lucide `UserMinus` 18 icon, height 44, bottom inset 16 + safe area |
| 15 | Forgot password (fallback state only) | Text button "Forgot password?" right-aligned under the field, routes to the existing forgot-password flow with the address prefilled |

**Tablet / Medium+ width (`gmWidthClass() != Compact`, iPad regular, web ≥ 768 px):** two panes. On the left, at 50 %, the welcome illustration (`ill-welcome.png` / `-dark`, centred, max 420). On the right, the same column inside a `surface` card (radius 20, elevation 1, padding 32, width 440), vertically centred.

**Landscape phone / short window (`gmIsShortWindow()`, height < 480, iOS verticalSizeClass compact):** a horizontal row inside a scroll view. On the left: a 72 avatar with ring, name and address stacked under it, in a column 40 % wide. On the right: banner, field (if shown), primary button, "Use another account". "Remove from this device" sits at the bottom of the right column. No wordmark.

**First name rule:** use `firstName` from the profile if set. Otherwise the first word of `displayName`. If neither exists, or the result is longer than 16 graphemes, the button says **"Continue"**.

**Masked address rule** (`maskAddress`, shared by all platforms): split at the **last** `@`. Let n be the grapheme count of the local part.
* n ≤ 2 → first grapheme + `•••`
* 3 ≤ n ≤ 4 → first + `•••` + last
* n ≥ 5 → first two + `•••` + last

The domain is always shown in full. The bullet is U+2022, three of them, no spaces. Example: `olivier@grapmail.com` → `ol•••r@grapmail.com`.
Accessibility label: "Address beginning {first part, spelled}, ending {last char} at {domain}". The full address is not spoken aloud on a screen whose point is privacy.

### 2.3 "Continue as" — the sign-in ladder

On tap, in this order. Every rung that cannot run is skipped silently. A **cancel** at any platform sheet goes to rung 3 and is not shown as an error.

| Rung | Condition | Android | iOS (16.1+) | Web |
|---|---|---|---|---|
| 1. Passkey | `hasPasskey == true` and the platform supports passkeys | `CredentialManager.getCredential` with `GetPublicKeyCredentialOption(requestJson)` from `POST /grapsee/auth/passkeys/authenticate/options`, `preferImmediatelyAvailableCredentials = true` (existing `security/PasskeyManager.kt`) | `ASAuthorizationPlatformPublicKeyCredentialProvider` assertion request, `performRequests(options: .preferImmediatelyAvailableCredentials)` (existing `PasskeyManager.swift`) | `navigator.credentials.get({ publicKey, mediation: 'optional' })` |
| 2. Saved password | rung 1 skipped, failed or not applicable | `GetPasswordOption()` in the same `GetCredentialRequest` as rung 1 when both apply. If the returned `PasswordCredential.id` (lowercased) ≠ the address, discard it. | `ASAuthorizationPasswordProvider().createRequest()`. Same id check against `ASPasswordCredential.user`. | `navigator.credentials.get({ password: true, mediation: 'optional' })` where supported (Chromium); otherwise skip |
| 3. Password field | rungs 1–2 gave nothing | Show the field (row 10). Focus it, keyboard up. A hidden username field holds the address: Compose `ContentType.Username` + `semantics { contentDataType }` / Autofill hints; iOS `.textContentType(.username)` on a hidden 1-pt `TextField`; web `<input type="email" autocomplete="username" value=address hidden>` + `<input autocomplete="current-password">` so the platform keychain can still fill | | |

Passkey options are discoverable-only (the server sends no `allowCredentials`). If the assertion signs in a **different** account than the one chosen, accept it: install that session, update the remembered list, and show a toast/snackbar "Signed in as {address}".

After the credential: call the existing sign-in route (`/grapsee/auth/login` or `/passkeys/authenticate/verify`). Two-step accounts continue into the existing 2FA screen. Error mapping: wrong password → field error "That password isn't right." (keep the field, clear the value). Locked/blocked account → remove the remembered record and route to the standard sign-in with the server message. Offline → inline banner "You're offline. Connect to sign in." with the button disabled.

The button's busy state: label → "Signing in…". The avatar ring rotates as an indeterminate spinner (1.2 s per turn, linear). Reduce motion: the ring stays still; only the label changes. Success: `GMHaptics.success()`, then a 220 ms crossfade into the mailbox.

### 2.4 What is stored locally (never the password)

A **remembered account** record, version 1:

| Field | Type | Notes |
|---|---|---|
| `v` | int | `1` |
| `address` | string | canonical, lowercased (§1.6.2 rules) |
| `displayName` | string | from profile |
| `firstName` | string | from profile, may be empty |
| `avatarUrl` | string? | server URL with revision (`avatarRevision`) — only for refreshing the cache while signed in |
| `avatarCacheRef` | string? | local file name / data URL (below) |
| `lastUsedAt` | ISO 8601 | updated on every successful sign-in and every foreground of that account |
| `hasPasskey` | bool | a **hint only**: set from `GET /grapsee/auth/passkeys` (count ≥ 1) after sign-in and whenever the Passkeys screen changes the list |
| `lastSignOutReason` | `expired` \| `signed_out` \| `revoked` \| null | §2.1 |

Never stored: password, tokens (those stay in the existing session store), phone number, recovery email.

| Platform | Where | Notes |
|---|---|---|
| iOS | Keychain generic password, service `com.grapsee.grapmail`, account key **`grapmail.remembered-accounts`**, JSON array; `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`; `kSecAttrSynchronizable = false`. Avatar: `Application Support/RememberedAvatars/<sha256(address)>.jpg`, 176×176 JPEG q0.8, `isExcludedFromBackup = true`. | **Reinstall rule:** Keychain survives app deletion. On first launch with no `UserDefaults` flag `gm.install.marker`, wipe `grapmail.remembered-accounts` and then set the flag, so a deleted app does not "remember" anyone. |
| Android | Preferences DataStore **`grapmail_remembered`**, key `accounts` (JSON array). Avatar: `noBackupFilesDir/remembered_avatars/<sha256>.jpg`, same size. | `android:allowBackup="false"` already, so nothing leaves the device. |
| Web | `localStorage["gm.remembered.v1"]` (JSON array). Avatar: a downscaled **data URL** (96×96 JPEG q0.8, ≤ 24 KB) in `avatarCacheRef`, because the avatar endpoint needs a session. | Per origin. Cleared by "Remove from this device", sign-out with removal, and the browser's site-data clearing. |

Limits: at most **5** records. Order by `lastUsedAt` desc; evict the oldest beyond 5. A record unused for **90 days** is pruned at launch.

A record is **written or updated** on every successful sign-in, on profile name/photo changes while signed in, and on passkey list changes.

A record is **cleared** by any of:
1. "Remove from this device" (§2.5).
2. The sign-out sheet with "Also remove from this device" ticked.
3. Account deletion.
4. A sign-in attempt answering account deleted / blocked (`BLOCKED_ACCOUNT_STATUSES`).
5. The 90-day prune.
6. Clearing the app's data.

The sign-out confirmation sheet (all platforms) gains a checkbox row:
* "**Also remove from this device**", default off.
* Helper: "You'll need to type your address next time."

### 2.5 "Remove from this device"

A confirm dialog (radius 24):
* Title: **"Remove {firstName or address} from this device?"**
* Body: "Your account and mail aren't affected. You'll need your address and password to sign in here again."
* Buttons: **"Remove"** (`error` text) · **"Cancel"**.

On confirm: delete the record and the cached avatar file, then show a snackbar "Removed from this device". If no remembered accounts are left, go to the standard sign-in. If other records remain, go to the account chooser.

### 2.6 Account chooser (≥ 2 remembered accounts)

* Title H2 **"Choose an account"**, subtitle Body `textSecondary` **"Signed out on this device"**.
* List of rows, each 72 high: avatar 48 · name (Title 17, 1 line) · masked address (Caption, `textSecondary`) · trailing overflow `⋮` (44 touch target) with one item, "Remove from this device". The whole row is the tap target; its accessibility label is "Continue as {name}, {masked address}".
* Ordered by `lastUsedAt` desc.
* Footer row: plus-in-circle icon + **"Use another account"**.
* Layout: phone = full-width list in a `surface` card, radius 20. Tablet = same card at width 440 next to the illustration pane (as §2.2). Landscape phone = list only, no illustration.
* Tapping a row pushes welcome-back for that account, and the avatar moves between the screens as a shared element (below).

### 2.7 Transitions

| Moment | Motion | Reduce motion |
|---|---|---|
| Splash → welcome-back | avatar scale 0.92 → 1 + fade, 300 ms EmphasizedDecelerate; name, address and button fade + rise 8, staggered 40 ms | 150 ms crossfade |
| Chooser → welcome-back | shared-element avatar 48 → 88, `long` 300 ms Standard. Android: Compose `SharedTransitionLayout` + `sharedElement` (BOM 2024.09 has it). iOS 16: `matchedGeometryEffect` inside one container that swaps chooser/welcome content (not a NavigationStack push). Web: framer-motion `layoutId`. | crossfade |
| Password field reveal | height expands, field fades in, 220 ms Standard; focus after the animation ends | instant + focus |
| Wrong password | field shakes (`GMShakeEffect` / Android equivalent), `GMHaptics.failure()` | no shake, haptic only |

---

## 3. Device & Security Center

One screen that **replaces** the separate Trusted Devices / Manage Sessions / Activity entry points as the default destination from Security (those screens stay reachable for deep links). Data comes from `GET /grapsee/auth/security/overview` (§6).

### 3.1 Structure (phone)

1. Top bar: **"Devices & security"** (title), back.
2. **Safety score header** (§4.3).
3. Banner slot: `passwordChangeRequired` banner (§3.6) or stale/offline banner (§3.7).
4. Section label (Micro, uppercase): **"YOUR DEVICES · {n}"**.
5. Device cards (§3.2): current device first, then by `lastActiveAt` desc. If there are more than 5, show the first 5 and a "Show all {n} devices" text button.
6. Section label: **"RECENT SECURITY ACTIVITY"**.
7. Timeline (§3.3) grouped by day, newest first, 30 days. Then "Show older activity" (cursor paging) until `eventsNextCursor == null`.
8. Footer note (Caption, `textSecondary`, shield 18): "Locations are approximate and based on your network."

Tablet / web ≥ 1024 px: two columns. Devices on the left (min 360, max 480), timeline on the right. The score header spans both columns.

### 3.2 Device card

`surface`, radius 20, padding 16, elevation 1, gap 12 between cards.

| Slot | Spec |
|---|---|
| Leading icon disc | 40 circle, `softFill`, glyph 22 `textSecondary`. `formFactor`: phone → `smartphone` / `iphone` / lucide `Smartphone`; tablet → `tablet` / `ipad` / `Tablet`; desktop + kind browser → `laptop` / `laptopcomputer` / `Laptop`; unknown → `devices` / `questionmark.circle` / `MonitorSmartphone`. |
| Title | Title 17 semibold, 1 line, ellipsis: `label` (e.g. "Pixel 8", "iPhone 15", "Chrome on Windows") |
| Subtitle | Caption `textSecondary`: app device → "GrapMail for Android" / "GrapMail for iOS"; browser → "{browser} · {os}" when both are known |
| Badges row | 8 gap, wraps. **"This device"** (app) / **"This browser"** (web) when `current`: `primaryContainer` pill, `onPrimaryContainer` CaptionMedium, 24 high, 10 horizontal padding. **"Trusted"** when `trusted`: `successContainer` pill, `success` text, `checkmark.shield` / `VerifiedUser` / `ShieldCheck` 14 icon. |
| Meta line 1 | Caption `textSecondary`, location pin 14: `location.label` (e.g. "Dhaka, Bangladesh"); if null: "Location unavailable". **Text only — never a map, never coordinates.** |
| Meta line 2 | Caption `textSecondary`: last-active relative time (table below) · "First seen {date}" (medium date, e.g. "12 Mar 2026") |
| Trailing | `⋮` menu (44 target). Not current → "This wasn't me" (`error` text, `exclamationmark.shield`) and "Remove device". Current → "Sign out" only (existing sign-out flow). |

Last-active wording (Android `DateUtils.getRelativeTimeSpanString`, iOS `RelativeDateTimeFormatter(.named)`, web `Intl.RelativeTimeFormat`, localized; the English below is the reference):

| Age | Text |
|---|---|
| current device, or < 2 min | "Active now" |
| < 60 min | "Active 5 min ago" |
| < 24 h | "Active 3 h ago" |
| < 7 days | "Active 2 days ago" |
| otherwise | "Last active 3 Nov 2026" |

Device card accessibility: the card is one element whose label is "{label}, {subtitle}, {badges}, {location}, {last active}". The menu is a separate element, "More options for {label}". Android also exposes the menu actions as `customActions`; iOS as `.accessibilityAction(named:)`.

### 3.3 Timeline events

Row: leading 32 icon disc (colour by risk) · title BodyMedium 15 · detail Caption `textSecondary` ("{deviceLabel} · {location.label} · {time}") · optional trailing status chip. A 2 dp vertical connector line in `border` joins the discs. Day headers: Caption-medium `textSecondary` "Today", "Yesterday", or the medium date "Mon, 18 Nov".

| `type` (§6) | Title (English) | Icon | Default disc |
|---|---|---|---|
| `signin` | "Signed in" | arrow into door | `softFill` |
| `signin_new_device` | "New device signed in" | device + plus | `primaryContainer` |
| `signin_suspicious` | "Unusual sign-in attempt" | warning shield | `warningContainer` |
| `signin_blocked` | "Sign-in blocked" | hand / no-entry | `errorContainer` |
| `password_changed` | "Password changed" | key | `softFill` |
| `passkey_added` | "Passkey added" | person + key | `successContainer` |
| `passkey_removed` | "Passkey removed" | person + key, slash | `softFill` |
| `two_step_enabled` | "2-step verification turned on" | shield check | `successContainer` |
| `two_step_disabled` | "2-step verification turned off" | shield slash | `warningContainer` |
| `recovery_requested` | "Account recovery requested" | lifebuoy | `warningContainer` |
| `recovery_used` | "Recovery used to sign in" | lifebuoy | `warningContainer` |
| `recovery_codes_generated` | "New recovery codes created" | list + key | `softFill` |
| `device_added` | "Device added as trusted" | device + shield | `softFill` |
| `device_removed` | "Device removed" | device + minus | `softFill` |
| `session_signed_out` | "Signed out remotely" | arrow out of door | `softFill` |
| `not_me_reported` | "You reported activity as not you" | flag | `errorContainer` |
| `account_created` | "Account created" | sparkle envelope | `primaryContainer` |
| *anything unknown* | "Security activity" | shield | `softFill` |

`risk == "high"` always uses `errorContainer`. `risk == "medium"` uses `warningContainer`, unless the table already assigns error.

Trailing chip:
* `acknowledged == false && action in {ALERT, STEP_UP, BLOCK}` → **"Review"** (`warningContainer`/`warning`).
* `reported == true` → **"Reported"** (`errorContainer`/`error`).

Tapping a row opens the **event sheet**:
* Title = the event title.
* Rows: When (full date + time), Device, Location, Approximate network (only if the server sends `ipMasked`).
* If `notMeAllowed`: **"This was me"** (secondary button; calls the existing `POST /grapsee/auth/security/events/{id}/ack`) and **"This wasn't me"** (`errorFill` button).
* Otherwise a single "Close" button.

### 3.4 "This wasn't me"

Available on any device card or event with `notMeAllowed == true`. It is never available on the current device; the server refuses that too (§6.3).

**Confirm sheet** (bottom sheet, radius 28, max width 560, `ill_sec_alert` art 120 tall at the top):
* Title: **"Secure your account?"**
* Body: "We'll sign out **{device label}** right away, then sign out every other device and browser except this one. You'll choose a new password next."
* Checklist, each line with a check icon in `textSecondary`:
  - "Sign out {device label} and remove its trust"
  - "Sign out all other sessions"
  - "Ask you to set a new password"
* Primary: **"Secure my account"** — `errorFill`, white text, full width, 54.
* Secondary: **"Cancel"** — text button.
* For an event with no device: replace the first bullet with "Sign out whoever did this".

**On "Secure my account":**
1. The button shows "Securing…" and the sheet cannot be dismissed while the request runs.
2. Call `POST /grapsee/auth/security/not-me` with `{deviceId}` or `{eventId}`.
3. On `403 STEP_UP_REQUIRED`: show the password sheet. Title "Confirm it's you", field "Password", button "Continue". Call `POST /grapsee/auth/account/verify-password {password, purpose: "secure-account"}`, then retry with `proof`.
4. On 200: `GMHaptics.success()`. The sheet closes and the **"Account secured"** screen is pushed (reuse the layout of `SessionRevokedSuspiciousView` / Android `SEC_REVOKED_ALERT`, art `ill_sec_revoked_suspicious`):
   * Title: **"Account secured"**
   * Body: "{device label} and {n} other sessions were signed out. Set a new password now to finish."
   * If `result.alreadyReported`, the body reads "You already secured your account from this report. Set a new password if you haven't yet."
   * Primary: **"Change password"** → the existing change-password screen (`SEC_PASSWORD` / `.secPassword` / web security page) in **required mode**: its title stays "Change password", the back button asks "Leave without changing your password? Your account stays at risk until you do." with "Stay" and "Leave".
5. After the password is changed: a **"Review your recovery options"** screen.
   * One row each for recovery email, phone, recovery codes and passkeys, showing their current status from the overview (Done/Not set).
   * Rows deep-link exactly like the §4.2 checklist actions.
   * Button: **"Done"** → back to the Security Center, which refetches.
6. Errors:
   * 404 → "That device is already gone." and refetch.
   * 409 `CANNOT_TARGET_CURRENT` → "That's the device you're using. Use Sign out instead."
   * 429 → "Too many attempts. Try again in a few minutes."
   * network → "Couldn't reach GrapMail. Nothing was changed." with "Try again".
   * Every error plays `GMHaptics.failure()`.

### 3.5 "Remove device" (normal cleanup)

Dialog:
* Title: **"Remove {label}?"**
* Body: "It will be signed out and will need your password to sign in again."
* Buttons: **"Remove"** (`error` text) · **"Cancel"**.

The client calls the existing endpoint named by `device.removal` (§6.2): `POST /grapsee/auth/devices/{id}/revoke` for `trusted_device`, or `DELETE /grapsee/auth/account/sessions?id={id}` for `session`. On success the card collapses (height → 0 + fade, 220 ms; reduce motion: removed instantly) and a snackbar says "{label} removed". There is no password change and no forced sign-out of others.

### 3.6 Password-change-required banner

When `account.passwordChangeRequired == true`, a full-width `errorContainer` banner (radius 16, padding 16) appears at the top of the Security Center **and** at the top of the mailbox list:
* Title: "Finish securing your account"
* Body: "Set a new password to finish."
* Button: "Change password"

It cannot be dismissed. It disappears when the server flag clears (refetch after the password change).

### 3.7 Loading, empty, error, offline

| State | Spec |
|---|---|
| Loading (no cache) | Score header skeleton (96 ring outline + 2 text bars), 2 device-card skeletons, 4 timeline-row skeletons, all in `softFill`. Shimmer: a 1200 ms linear sweep. Reduce motion: static blocks. Accessibility: one live-region "Loading your security info". |
| Loading (with cache) | Render the cache (`SecuritySnapshot` / `SecurityProfileCache.kt`) at once, with a thin indeterminate progress bar under the top bar |
| Empty events | `ill_sec_activity` art 160 + "No security activity in the last 30 days" (Title) + "Sign-ins, password changes and new devices will show up here." (Caption) |
| Devices | Never empty: the current device is always present. If the server omits it, the client renders a local "This device" card from its own model name and the time of the last refresh. |
| Error (no cache) | `ill_sec_sessions` art + "Couldn't load your security info" + "Check your connection and try again." + button "Try again" |
| Error / offline (with cache) | `warningContainer` banner: "You're offline. Showing what we last saw at {time}." (offline) or "Couldn't refresh. Showing what we last saw at {time}." (error), plus "Retry". All destructive actions (this wasn't me, remove) are disabled, with the reason "Connect to the internet to do this." |

Refetch on: screen appear, pull-to-refresh, return from any child screen, app foreground while visible.

---

## 4. Account safety score + guided setup

### 4.1 The score

* The server computes the score, `0–100`, and returns it in the overview (§6). **Clients never compute it**, except to render the last cached value offline.
* `value = round(100 × earned / possible)`, where `possible` sums the points of the items that are not `not_applicable` and `earned` sums the points of the `done` items. If `possible == 0`, then `value = 100`. Round half up.
* Levels and their words:

| Range | `level` | Word (UI) | Ring colour |
|---|---|---|---|
| 0–59 | `needs_attention` | **"Needs attention"** | `warning` |
| 60–84 | `good` | **"Good"** | `primaryText` |
| 85–100 | `strong` | **"Strong"** | `success`; signature gradient when value = 100 |

### 4.2 Weighted checklist (points sum to 100)

Based only on what the backend supports today: `twoFactorEnabled`, `phoneVerified`, `recoveryEmailVerified`, `passwordChangedAt` (profile); the passkey list (`auth-passkeys.ts`); `hasUsableRecoveryCodes`, `trusted_devices`, `security_events.acknowledged_at` (`auth-devices.ts`, `security-events.ts`); breach screening (`isPasswordBreached`).

| # | `id` | Points | Done when (server) | Not applicable when | Title (todo / done) | Why it matters (one line) | Action → existing screen |
|---|---|---|---|---|---|---|---|
| 1 | `two_step` | **25** | profile `twoFactorEnabled` | never | "Turn on 2-step verification" / "2-step verification is on" | "Someone with your password still can't get in without a code." | `security.two_step` → Android TwoFactorEnableScreen route (`Routes.SECURITY` band, `security/2fa`); iOS `.secEnable`; web `/mail/account/security#two-step` |
| 2 | `passkey` | **20** | ≥ 1 row in `passkey_credentials` | never | "Add a passkey" / "Passkey added" | "Sign in with your face, fingerprint or screen lock — there's nothing to phish." | `security.passkeys` → `Routes.SEC_PASSKEYS`; `.secPasskeys`; web `/mail/account/sign-in#passkeys` |
| 3 | `recovery_codes` | **15** | `hasUsableRecoveryCodes(user)` | never | "Save recovery codes" / "Recovery codes saved" | "Get back in even if you lose every device." | `security.recovery_codes` → `Routes.SEC_RECOVERY_CODES`; `.secRecoveryCodes`; web `/mail/account/security#recovery-codes` |
| 4 | `recovery_email` | **10** | `recoveryEmailVerified` | never | "Add a recovery email" / "Recovery email verified" | "We can reach you there if you're ever locked out." | `account.recovery_email` → `Routes.RECOVERY_EMAIL`; `.recoveryEmail`; web `/mail/account/security#recovery-email` |
| 5 | `second_device` | **10** | ≥ 2 trusted devices with `revoked_at IS NULL` | never | "Sign in on a second device" / "2 trusted devices" | "A second phone or tablet can vouch for you if you lose this one." | `security.devices` → Security Center devices section + how-to sheet: "Install GrapMail on another phone or tablet and sign in. It becomes trusted automatically." |
| 6 | `password_health` | **10** | password exists **and** the latest breach check is not `breached` (unknown counts as done until the first check runs) | account has no password (SSO / passkey-only) | "Change your password" / "Password not found in known breaches" | "This password appears in a public data breach." (todo) | `security.password` → `Routes.SEC_PASSWORD`; `.secPassword`; web `/mail/account/security#password` |
| 7 | `recovery_phone` | **5** | `phoneVerified` | never | "Verify a phone number" / "Phone verified" | "A second way to get codes if email isn't available." | `account.phone` → `Routes.PHONE_VERIFY`; `.phoneVerify`; web `/mail/account/phone` |
| 8 | `alerts_reviewed` | **5** | no event in the last 30 days with `action ∈ {ALERT, STEP_UP, BLOCK}` and `acknowledged_at IS NULL` | never | "Review {n} security alerts" / "No alerts to review" | "Confirm recent sign-ins were really you." | `security.activity` → Security Center timeline, scrolled to the first "Review" row |

Backend note for `password_health`: breach screening runs at password set (`auth.ts` `BREACHED_PASSWORD`). The overview builder adds an async k-anonymity check (`isPasswordBreached`) after each successful password sign-in and stores `password_breached_at` (nullable). "Not reused" is described honestly as "not found in known breaches". GrapMail cannot see reuse on other sites.

Checklist rows on the client:
* 64 min height.
* Leading 24 status icon: `successFill` check-circle when done; `border` outline circle when todo.
* Title BodyMedium; why-line Caption `textSecondary` (todo only).
* Trailing: todo → "+{points}" pill (`primaryContainer`, CaptionMedium); done → nothing.
* Order: todo items first by points desc, then done items in table order.
* `not_applicable` rows are hidden.
* The whole row taps through to the action; done rows also tap through, for management.
* Accessibility label: "{title}. {why}. Worth {points} points." Done rows: "{done title}. Done."

### 4.3 Where it appears

**A. Security header** (top of the Security Center §3.1, and the top of Account Security: `AccountSecurityView` / `AccountSecurityScreen` / web security page)
* `surface` card, radius 20, padding 20.
* Row layout: ring 96 on the left (stroke 10, round caps, track `stepInactive`, progress in the level colour; the centre shows the number, 28 bold `textPrimary`, with "of 100" in Caption `textSecondary` below). On the right: "Account safety" Title, then the level word in its level colour as CaptionMedium, then the next-step line in Caption `textSecondary`: "{k} steps to Strong" / "You've done everything" / "Start with: {top todo title}".
* Below: the checklist, collapsed to the top 3 todo items with "See all {n} steps".
* The ring animates from its previous value to the new value over `extraLong` 450 ms EmphasizedDecelerate. Reduce motion: shows the final value.
* Accessibility: the ring is one element, "Account safety {value} out of 100, {level word}". It is `progressbar` on web, `accessibilityValue` on iOS, and `ProgressBarRangeInfo` semantics on Android.

**B. Mailbox nudge card** (one card at the top of the mailbox list, below the greeting, in the same slot type as `InboxBriefCard` / `MorningCard`)
* Show when **all** hold:
  1. `score.value < 60`;
  2. the overview cache is ≤ 24 h old, or a background fetch (at most once a day) succeeded;
  3. no `passwordChangeRequired` banner is showing (§3.6 wins);
  4. no unreviewed security-alert banner is showing;
  5. the card has not been **shown** to this account in the last **7 days** — stored locally as `gm.safetyCard.lastShownAt.<fnv1a(address)>`;
  6. the account is older than 24 h;
  7. the user is not in multi-select, search, or offline.
* Design: `primaryContainer` bg, radius 20, padding 16. Mini ring 40 (stroke 5) on the left. Title BodyMedium "Make your account harder to break into". Body Caption: "Your safety score is {value}. Next: {top todo title} — it takes about a minute." Primary text button "Improve" opens the Security Center. Close `×` button, 44 target, label "Dismiss".
* Being shown once sets `lastShownAt`, whether or not it is dismissed. Dismissing hides it for the rest of the session. It slides out by height collapse (220 ms); reduce motion: instant.

### 4.4 Celebration at 100

Plays **once per account**. Local flag `gm.safety.celebrated.<fnv1a(address)>`. It is cleared if the score later drops below 100, so it can play again after the user re-earns it.

* Trigger: the header renders `value == 100` after a refetch in which the previous cached value was < 100.
* Sequence:
  1. the ring fills to 100 with the signature gradient (450 ms);
  2. the centre number crossfades to a check glyph, scale 0.6 → 1 with the `settle` spring;
  3. 14 confetti particles in `#3D8BFF`, `#1F5EFF`, `#5B47E0`, `#0E9F6E`, `#F5A623` drift up and out from the ring over 1200 ms, then fade;
  4. `GMHaptics.success()`;
  5. the level word reads "Strong", and the next-step line reads "Your account is fully protected".
* Live region / announcement: "Account safety 100 out of 100. Fully protected."
* Reduce motion: no particles and no scale. The ring is shown full and the check crossfades in 150 ms. Haptic and announcement are unchanged.
* The art for the done state, under the header on first view: `ill_sec_hub_secure` (light/dark pair).

### 4.5 Reuse

* Android `FeatureTip.kt` / `FeatureTipsStore` and iOS `FeatureTips.swift` (TipKit, **iOS 17+ only**: the code already gates on `#available(iOS 17.0, *)`, so iOS 16 simply gets no tip). Add tip id `safetyScore`: "Your account safety score", "Tap a step to make your account harder to break into."
  * Show it once, anchored to the ring, on the first Security Center visit.
  * Never on the same session as another tip.

---

## 5. Reader & conversation polish

### 5.1 Reader typography

| Role | Size / line height | Weight | Colour |
|---|---|---|---|
| Subject, expanded header | 22 / 28 (H2) | bold, −0.3 | `textPrimary`, up to 3 lines |
| Subject, collapsed toolbar | 17 / 22 | semibold | `textPrimary`, 1 line, ellipsis |
| Sender name | 15 / 20 | semibold | `textPrimary` |
| Sender address / "to me" line | 13 / 18 | regular | `textSecondary` |
| Timestamp | 13 / 18 | regular | `textTertiary` |
| **Reader body (plain text and HTML base)** | **16 / 25** (≈ 1.55) | regular | `textPrimary` |
| Quoted / small print | 14 / 21 | regular | `textSecondary` |
| `pre` / `code` | 14 / 20 monospace | regular | `textPrimary`, horizontal scroll inside the block only |
| Collapsed-card snippet | 14 / 20 | regular | `textSecondary`, 1 line |

* **Measure:** the text column is capped at **680** wide and centred when the reader pane is wider than 728. Use `GMLayout.Readable` (720) − 2 × 20 padding. On Android that is `Modifier.gmMaxWidth()` (`Adaptive.kt`); on iOS `.gmMaxWidth()` (`ScaledFont.swift`); on web, `max-width: 680px; margin-inline: auto` on the message body wrapper.
* **HTML newsletters** with fixed-width tables (typically 600 px) are centred at their own width, never stretched. If wider than the pane, use the existing fit-to-width scaling. Never horizontally scroll the whole page.
* **Text size:** the base size follows system text size. iOS: inject `font-size: {UIFontMetrics(.body).scaledValue(for: 16)}px` into the HTML base style in `MailHtmlBody.swift`. Android: `WebSettings.textZoom = (fontScale × 100).toInt()` in `MailHtmlBody.kt`. Web: `rem`-based.
* The injected base CSS (both `MailHtmlBody`s) gains: `body{font-size:16px;line-height:1.55;word-wrap:break-word} img{max-width:100%;height:auto} pre{overflow-x:auto}`.
* The existing dark adaptation (`EmailDarkening.kt` / `EmailDarkening.swift`) is unchanged.

### 5.2 Quote collapsing — "··· Show quoted text"

**Detect** the **trailing** quoted block only. Interleaved inline replies stay visible.

* HTML: the last top-level element matching `blockquote[type=cite]`, `.gmail_quote`, `.yahoo_quoted`, `#appendonsend` + following siblings, `#divRplyFwdMsg` + following siblings (Outlook), `div.moz-cite-prefix` + next `blockquote`, or an "On {date}, {name} wrote:" paragraph immediately followed by a `blockquote`.
* Plain text: from the first line matching `^On .+ wrote:$` (also `^-----Original Message-----$`, `^From: .+$` followed by `^Sent: `) to the end, **if** every following non-blank line starts with `>`, or it is that header block. Also a trailing signature after a line that is exactly `-- ` is collapsed into the same pill.

**Do not collapse** when:
* the message's own (non-quoted) text is empty (pure forwards), or
* the message contains `---------- Forwarded message ---------`, or
* the quoted part is under 3 lines.

**Pill:** height 32, `radiusPill`, `softFill` bg. Label "**···  Show quoted text**" in CaptionMedium `textSecondary`. Leading margin aligns with the body text; top margin 12.
* Tap → expands in place (height animates over 220 ms Standard; reduce motion: instant). The label becomes "**Hide quoted text**".
* Accessibility: role button; `aria-expanded` / `.accessibilityValue("Collapsed"/"Expanded")` / Compose `stateDescription`.
* Expansion is remembered per message for the session only.

Implementation: collapse happens **inside** the HTML document. A small injected script wraps the detected nodes in `<div class="gm-quote" hidden>` and inserts the pill as a `<button>`; the WebView reports the new height through the existing height bridge. Do not re-render native views around it.

### 5.3 Conversation view

**Decision: stacked message cards for every thread type. No chat bubbles in Round 8.**

Why not chat bubbles, even for plain-text personal threads:
1. Classifying a thread as "personal plain text" is unreliable. Many personal mails are HTML with a signature, and a thread can switch mid-way.
2. Bubbles break quoted text, signatures, attachments and long paragraphs, which are what email is made of.
3. One visual grammar per screen is easier for screen-reader users and for muscle memory.
4. Gmail, Apple Mail and Outlook all keep cards, so bubbles would read as a chat app pretending to be mail.

Revisit later as an opt-in "Chat view" for 1:1 threads only if the analytics ask for it.

**Card anatomy:**
* `surface`, radius 20, elevation 1, horizontal margin 12 (phone) / 0 inside the 680 column (wide), gap 10.
* **Expanded card:** header row (avatar 40 · sender name · "to me" / "to {n} people" line · timestamp · `⋮` menu); body (§5.1, §5.2); attachments (§5.6); footer actions row (Reply · Reply all · Forward as 40-high text-icon buttons) on the **last** expanded card only. Other cards get the actions through `⋮`.
* **Collapsed card:** 64 high: avatar 32 · sender name BodyMedium (bold if unread) · snippet 1 line · timestamp trailing. The whole card is the tap target and expands it in place.
* **Which are expanded on open:** the latest message, plus every unread message. All others are collapsed.
* **Folding long threads:** when more than 3 consecutive collapsed cards sit between the first message and the expanded ones, they fold into one **"{n} earlier messages"** bar. The bar is 40 high, with a centred pill (`softFill`, CaptionMedium `textSecondary`) between two 1 dp `border` lines. Tap → the folded cards appear as collapsed cards (staggered fade 30 ms each; reduce motion: instant). The first message always stays visible as a collapsed card.
* **Own messages** (from the account's address or any alias in `profile.aliases`):
  * card bg `ownMessageFill` (`#F3F6FF` / `#161A26`);
  * a 3 dp leading accent bar in `primary`, inset 12 from top and bottom, radius 2;
  * the sender name reads **"You"**, with the address still in the secondary line;
  * no avatar on the collapsed card: a 32 circle in `primaryContainer` holds a `person` glyph in `onPrimaryContainer`.
* **Scroll on open:** scroll so the first unread card (or the latest one) sits 8 below the sticky header. No animation on first layout.
* **Expanding a card** keeps its top edge fixed on screen (scroll anchoring). Content below moves; content above never does.

### 5.4 Sticky subject header that shrinks on scroll

* **Expanded:** the subject H2 (up to 3 lines) plus a label chip row, inside the scroll content directly under the toolbar.
* **Collapsed:** the subject appears in the toolbar as a 17 semibold single line.
* **Transition:** scroll-linked over the first **64** of scroll. The in-content subject's opacity goes 1 → 0 and its scale 1 → 0.96. The toolbar title's opacity goes 0 → 1 between 40 and 64. It is interpolated by scroll offset, not time-animated.
  * Android: `TopAppBarDefaults.exitUntilCollapsedScrollBehavior()` with a `MediumTopAppBar`-style custom layout in `ThreadScreen.kt`.
  * iOS 16: a `GeometryReader` preference key for the content offset, with `.navigationBarTitleDisplayMode(.inline)` and a custom `.toolbar { ToolbarItem(placement: .principal) }`. Do not use `scrollPosition` (iOS 17 only).
  * Web: `IntersectionObserver` on the subject element with `position: sticky` for the toolbar.
* **Reduce motion:** no interpolation. Below 48 of scroll, show expanded; above it, show the toolbar title, with a 150 ms crossfade.
* The toolbar keeps a 1 dp bottom `border` that fades in with the title, so the content edge reads.
* Accessibility: the subject is a header trait (`.isHeader` / `heading()` / `<h1>`) in **both** states. Only one of them is exposed at a time; the hidden one is `accessibilityHidden`.

### 5.5 Smooth scrolling, no layout jumps

1. **Reserve image space.** Before the HTML loads, the injected script reads the `width`/`height` attributes of every `<img>` and sets `aspect-ratio: w / h`. If there are no attributes, the box stays at height 0 until decode. Remote images stay blocked per the existing setting.
2. **WebView height changes.** The body height posted by the bridge is debounced to one update per animation frame. Increases above the viewport top are compensated with a scroll-offset adjustment, so the reading position never moves.
   * Android: keep the `LazyColumn` item key stable and use `LazyListState.requestScrollToItem` with an offset.
   * iOS: preserve the offset using the first visible card's top anchor.
   * Web: CSS `overflow-anchor: auto` on the scroller, plus `contain: layout` on each card.
3. **Card skeleton while the body loads:** the card shows 3 text-bar placeholders (`softFill`, 12 high) whose height equals the cached body height if one is known, otherwise 120. The real body crossfades in (150 ms).
4. **Attachment tiles** have fixed sizes from the start (§5.6). Thumbnails fade in inside a box that never resizes.
5. **No item animations on first render.** Animations apply only to user-initiated changes (expand, collapse, fold).
6. The **quick-reply bar** (§5.7) is outside the scroll content. Its height changes adjust the scroll content's bottom padding, never the scroll offset.

### 5.6 Attachments

* **Image grid** (jpg, png, gif, webp, heic):
  * 2 columns on Compact; 4 columns at Medium+ (reader pane ≥ 600). Gap 6. Tiles 1:1, `radiusThumb` 12, `centerCrop`.
  * At most 4 tiles. The 4th tile shows a "+{n}" overlay (scrim `#0F172A` @ 50 %, white Title 17) when there are more.
  * Tap → full-screen viewer (pager): iOS `QuickLookPresenter.swift`; Android `Routes.MAIL_ATTACHMENT`; web lightbox.
* **File rows** (everything else, and images beyond the grid inside the viewer only), 56 high:
  * 40×40 file-type tile, `radiusSmall`, coloured per type, with the uppercase extension in 10 bold white (max 4 chars), or a glyph;
  * name BodyMedium, 1 line, **middle** ellipsis so the extension stays visible;
  * meta Caption `textSecondary`: "{TYPE} · {size}" (e.g. "PDF · 2.4 MB"). Sizes use the platform byte formatters: `Formatter.formatShortFileSize`, `ByteCountFormatter(.file)`, `Intl.NumberFormat` with the unit;
  * trailing download/save icon button (44 target).

| Type | Extensions | Tile colour |
|---|---|---|
| PDF | pdf | `#D92D2D` |
| Document | doc, docx, odt, rtf, txt, pages | `#1F5EFF` |
| Spreadsheet | xls, xlsx, csv, ods, numbers | `#08734F` |
| Presentation | ppt, pptx, odp, key | `#B45309` |
| Archive | zip, rar, 7z, gz, tar | `#52607A` |
| Audio | mp3, m4a, wav, ogg, flac | `#6A4DF4` |
| Video | mp4, mov, webm, mkv | `#B5307A` |
| Calendar | ics | `#0B6FA8` |
| Other | * | `#52607A` |

* **PDF first-page preview:** shown as a 1:1 tile in the grid row *above* the file rows, labelled with the file name in a bottom gradient. Fallback: the file row only.
  * Android: `android.graphics.pdf.PdfRenderer` (API 21+) on the downloaded file. Render page 0 at 2× tile size into an ARGB_8888 bitmap on `Dispatchers.IO`; cache in the Coil memory cache under key `pdfthumb:{attachmentId}`.
  * iOS: `QLThumbnailGenerator` (iOS 13+), `.thumbnail` representation at 2× tile size; PDFKit `PDFPage.thumbnail(of:for:)` as the fallback.
  * Web: `pdfjs-dist` (already in `package.json`), lazily imported. Render page 1 to a canvas once the tile enters the viewport (`IntersectionObserver`).
  * Only for files ≤ 10 MB that are already downloaded or auto-downloadable on Wi-Fi/unmetered. Password-protected or corrupt PDFs → fallback, silently.
* **Actions** (long-press / context menu on mobile, hover toolbar + right-click on web):
  * "Open": QuickLook / in-app viewer / new tab.
  * "Save":
    * iOS: share sheet → "Save to Files" (via `UIActivityViewController`) or `.fileExporter`;
    * Android: `ACTION_CREATE_DOCUMENT` (or MediaStore Downloads on API 29+);
    * web: `<a download>`.
  * "Share": system share sheet; web `navigator.share({ files })` where supported, otherwise hidden.
  * "Save all" link above the list when there are ≥ 2 attachments: Android `ACTION_OPEN_DOCUMENT_TREE`, then write each; iOS `.fileExporter` with multiple documents; web downloads in sequence.
  * The existing drag-out (`AttachmentDrag.swift`) keeps working.
* **Accessibility:** a tile's label is "{file name}, {type}, {size}"; the image grid announces "Image {i} of {n}, {file name}". Encrypted (E2E) attachments keep the existing lock affordance.

### 5.7 Quick-reply bar

* **Placement:** pinned to the bottom of the reader, above the home indicator / gesture bar, and moves with the keyboard (Android `imePadding()`, iOS keyboard safe area, web `position: sticky; bottom: 0` with `env(safe-area-inset-bottom)`). Elevation 2.
* **Collapsed (52 high):**
  * a field-like pill (`inputFill`, `border` 1, radius 999, height 40) with the placeholder **"Reply to {first name}…"**, or **"Reply all…"** when reply-all is selected;
  * a trailing expand icon button (44), label "Open full editor".
* **Focused:**
  * the field grows from 1 to 5 lines, then scrolls;
  * a row above the field shows a **"Reply all"** toggle chip (only when the original has > 1 other recipient; default follows the user's reply-default setting) and the recipients summary in Caption, e.g. "To Nusrat, Rafid +2";
  * trailing **Send** icon button (44, `primary` fill when text is non-empty; disabled otherwise), label "Send".
* **Expand** → opens the full compose (`Routes.MAIL_COMPOSE` with `reply` + `mode` / `.mailCompose(reply:mode:)` / web compose), carrying the typed text and the reply-all choice.
* **Send:** goes through the existing reply pipeline and Outbox (`Outbox.kt` / `Outbox.swift`), with the existing undo window. A snackbar "Sent" with "Undo" appears; `GMHaptics.success()` plays when the send commits.
* **Draft:** typed text autosaves locally per thread (500 ms debounce) and is restored on reopen. It is cleared on send or discard. Backing out with text shows no dialog; the draft is kept.
* **Hidden** in Spam and Trash, in "Scheduled" previews, and when the thread is E2E-encrypted but the device has no key (the existing E2E screens handle that state).
* **Accessibility:** the bar is a labelled region "Quick reply"; the send button announces "Send reply to {first name}".

---

## 6. API contract for §3–4

**This section is the source of truth.** The backend builder implements it, and all three clients decode exactly this. The JSON Schema is `docs/design/security-overview.schema.json`. Paths go through the gateway prefix the clients already use (`/grapsee/auth/...`, as `APIClient.swift` and `Api.kt` `$AUTH` do). The service route is `/security/overview` on the auth service, next to `/security/events` in `auth-devices.ts`.

Both endpoints:
* require `Authorization: Bearer <access token>` (`authMiddleware`);
* send `x-device-id` (the installation id) from mobile, which is how the server marks the current app device;
* respond with `Cache-Control: no-store`.

### 6.1 Common rules

* Timestamps are ISO 8601 UTC strings with `Z`.
* IDs are opaque strings. Clients never parse them.
* Enums are **open**: a client MUST render an unknown `type` / `id` / `platform` with the generic fallback (§3.3 "Security activity"; an unknown checklist id is hidden) rather than fail to decode. In Kotlin, decode into a `String` plus a mapping function; in Swift, a `RawRepresentable` with an `unknown` case; in TypeScript, `string` with a union for known values.
* All **user-facing copy is client-side and localized.** The server sends no titles. `device.label` and `location.label` are the only server-composed strings: proper names and places in English.
* Error envelope (matches the existing routes): `{ "success": false, "error": "<English message>", "code": "<CODE>", "message": "<English message>" }`.

### 6.2 `GET /grapsee/auth/security/overview`

Query parameters:

| Name | Type | Default | Notes |
|---|---|---|---|
| `eventsLimit` | int 1–100 | 30 | |
| `eventsCursor` | string | — | from the previous `eventsNextCursor` |
| `days` | int 1–180 | 30 | event window (retention is 180 d, `security_180d`) |

`200` response:

```json
{
  "success": true,
  "generatedAt": "2026-11-24T07:00:00Z",
  "account": {
    "address": "olivier@grapmail.com",
    "displayName": "Olivier Pricious",
    "hasPassword": true,
    "passwordChangeRequired": false
  },
  "score": {
    "version": 1,
    "value": 55,
    "level": "needs_attention",
    "earnedPoints": 55,
    "possiblePoints": 100
  },
  "checklist": [
    { "id": "two_step",        "state": "done",  "points": 25, "earned": 25, "action": "security.two_step" },
    { "id": "passkey",         "state": "todo",  "points": 20, "earned": 0,  "action": "security.passkeys", "meta": { "count": 0 } },
    { "id": "recovery_codes",  "state": "todo",  "points": 15, "earned": 0,  "action": "security.recovery_codes", "meta": { "remaining": 0 } },
    { "id": "recovery_email",  "state": "done",  "points": 10, "earned": 10, "action": "account.recovery_email" },
    { "id": "second_device",   "state": "done",  "points": 10, "earned": 10, "action": "security.devices", "meta": { "count": 2 } },
    { "id": "password_health", "state": "todo",  "points": 10, "earned": 0,  "action": "security.password", "meta": { "status": "breached", "lastChangedAt": "2025-03-01T10:00:00Z" } },
    { "id": "recovery_phone",  "state": "done",  "points": 5,  "earned": 5,  "action": "account.phone" },
    { "id": "alerts_reviewed", "state": "done",  "points": 5,  "earned": 5,  "action": "security.activity", "meta": { "count": 0 } }
  ],
  "devices": [
    {
      "id": "td_5b1c…",
      "kind": "app",
      "platform": "android",
      "formFactor": "phone",
      "label": "Pixel 8",
      "browser": null,
      "os": "Android 15",
      "current": true,
      "trusted": true,
      "attestation": "play_integrity",
      "location": { "label": "Dhaka, Bangladesh", "city": "Dhaka", "region": "Dhaka Division", "country": "BD" },
      "ipMasked": "103.12.45.x",
      "firstSeenAt": "2026-03-12T09:14:00Z",
      "lastActiveAt": "2026-11-24T06:59:10Z",
      "activeSessionCount": 1,
      "removal": null,
      "notMeAllowed": false
    },
    {
      "id": "ss_9f2e…",
      "kind": "browser",
      "platform": "windows",
      "formFactor": "desktop",
      "label": "Chrome on Windows",
      "browser": "Chrome",
      "os": "Windows",
      "current": false,
      "trusted": false,
      "attestation": null,
      "location": { "label": "Chattogram, Bangladesh", "city": "Chattogram", "region": null, "country": "BD" },
      "ipMasked": "27.147.x.x",
      "firstSeenAt": "2026-11-20T15:02:00Z",
      "lastActiveAt": "2026-11-23T18:40:00Z",
      "activeSessionCount": 1,
      "removal": { "via": "session", "id": "8c1a…sessionId" },
      "notMeAllowed": true
    }
  ],
  "events": [
    {
      "id": "ev_31d0…",
      "type": "signin_new_device",
      "occurredAt": "2026-11-20T15:02:00Z",
      "deviceId": "ss_9f2e…",
      "deviceLabel": "Chrome on Windows",
      "location": { "label": "Chattogram, Bangladesh", "city": "Chattogram", "region": null, "country": "BD" },
      "risk": "medium",
      "action": "ALERT",
      "factor": null,
      "acknowledged": false,
      "reported": false,
      "notMeAllowed": true
    }
  ],
  "eventsNextCursor": null
}
```

Field rules (backend):

| Field | Rule |
|---|---|
| `devices[]` | One entry per **trusted device** (`trusted_devices`, `revoked_at IS NULL`, id `td_<uuid>`), plus one per **browser/app session group** not tied to a trusted device (`user_sessions.is_active`, grouped by the existing `sessionDeviceId(ip, ua)`, id `ss_<deviceId>`). Revoked devices are omitted. |
| `current` | App: the trusted device whose `installation_id` = `x-device-id`. Browser: the group containing the caller's `sessionId`. Exactly one device is `current`. |
| `trusted` | `trust_state = 'trusted'` (app devices only; browsers are `false`). |
| `label` | App: `device_display_name` (e.g. "Pixel 8"); browser: `"{browser} on {os}"` from the existing `describeDevice` / `deviceBrowser`. |
| `location` | Coarse city-level geo of the **last** IP, resolved server-side at sign-in or refresh and stored. `label` = `"{city}, {country name in English}"`, or `"{country name}"` if there is no city. `null` when unknown. Never coordinates. |
| `ipMasked` | Existing `maskIp` format, or omit. |
| `removal` | `null` for the current device. `{ via: "trusted_device", id: <uuid> }` → client calls `POST /grapsee/auth/devices/{id}/revoke`. `{ via: "session", id: <sessionId> }` → client calls `DELETE /grapsee/auth/account/sessions?id={id}`. For a session group with several sessions, the server picks the most recent; after the call the client refetches the overview. |
| `notMeAllowed` | `false` for the current device and for devices already revoked; otherwise `true`. |
| `events[].type` | Mapping from `SECURITY_EVENT_TYPES` and activity logs: `security.signin`→`signin`, `.signin.new_device`→`signin_new_device`, `.signin.suspicious`→`signin_suspicious`, `.signin.blocked`→`signin_blocked`, `.password.changed` / activity `password_changed`→`password_changed`, `.factor.added` + factor passkey→`passkey_added`, `.factor.removed` + passkey→`passkey_removed`, activity `two_factor_enabled`→`two_step_enabled`, `two_factor_disabled`→`two_step_disabled`, `.recovery.requested`→`recovery_requested`, `.recovery.used`→`recovery_used`, recovery-codes generate→`recovery_codes_generated`, `.device.enrolled`→`device_added`, `.device.revoked`→`device_removed`, `.session.revoked` / `all_sessions_signed_out`→`session_signed_out`, new `security.not_me.reported`→`not_me_reported`, `.account.created`→`account_created`. `otp.*`, `attestation_*` are **not** listed (noise). |
| `events[].risk` | from `risk_score`: 0–29 `low`, 30–59 `medium`, ≥ 60 `high`. |
| `events[].deviceId` | the overview device id (`td_…` / `ss_…`) if that device is still listed, else `null` (`deviceLabel` still filled when known). |
| `events[].notMeAllowed` | `true` for `signin*`, `password_changed`, `passkey_*`, `two_step_*`, `recovery_*`, `device_added` when not originating from the current device and not already reported; else `false`. |
| `score`, `checklist` | §4.1–4.2 exactly. `checklist` always lists all 8 ids, in table order. `state: "not_applicable"` items have `earned: 0` and are excluded from `possiblePoints`. |
| `account.passwordChangeRequired` | new `users.password_change_required` boolean. Set by not-me, cleared by any successful password change or reset. Also returned by `GET /auth/me` and `GET /auth/account/profile` so the mailbox banner (§3.6) can render without the overview. |

Errors: `401` (standard), `429 RATE_LIMITED` (60 requests / 10 min per user).

### 6.3 `POST /grapsee/auth/security/not-me`

Request — **exactly one** of `deviceId` / `eventId`:

```json
{ "deviceId": "ss_9f2e…" }
{ "eventId": "ev_31d0…" }
{ "deviceId": "ss_9f2e…", "proof": "<action proof from verify-password>" }
```

Server behaviour, in order, inside one transaction where possible:
1. Resolve the target. For an event: its `device_id` → trusted device, and/or its `session_id` → session group. An event with neither still runs steps 3–7.
2. If the target resolves to the **current** device or session → `409 CANNOT_TARGET_CURRENT`. Nothing changes.
3. **Step-up:** if the caller's session is < 15 min old **and** the caller is not a trusted app device, require `proof`. A missing or invalid proof → `403 STEP_UP_REQUIRED`. The proof comes from `POST /grapsee/auth/account/verify-password {password, purpose: "secure-account"}`; add the `secure-account` purpose to `mintProof`, which today takes `delete-account` / `enable-2fa`.
4. Revoke the target: `revokeTrustedDevice` for `td_…` (this already kills its bound sessions and push registration). For `ss_…`, close every session in the group.
5. Sign out **all other sessions** of the user except the caller's (`closeAllSessions` minus current), and revoke their refresh tokens.
6. Set `users.password_change_required = true`.
7. Record `security.not_me.reported` (new `SECURITY_EVENT_TYPES.notMeReported`) with `actionOverride: 'REVOKE'`, `signals: ['user_reported']`. Mark the disputed event `acknowledged_at = now()` and reported. Send the security notice to the account and the verified recovery email (`deliverSecurityNoticeEverywhere`).
8. **Idempotent:** the same target reported again within 24 h → `200` with `alreadyReported: true`, and steps 4–7 are not repeated.

Rate limit: 5 per hour per user → `429 RATE_LIMITED`.

`200` response:

```json
{
  "success": true,
  "result": {
    "reportId": "ev_77aa…",
    "target": { "kind": "device", "id": "ss_9f2e…" },
    "revokedDeviceIds": ["ss_9f2e…"],
    "signedOutSessions": 3,
    "passwordChangeRequired": true,
    "alreadyReported": false
  },
  "nextSteps": ["change_password", "review_recovery"]
}
```

| `code` | HTTP | When | Client copy (§3.4) |
|---|---|---|---|
| `TARGET_REQUIRED` | 400 | neither id sent | (bug) generic error |
| `TARGET_AMBIGUOUS` | 400 | both sent | (bug) generic error |
| `NOT_FOUND` | 404 | id not this user's, or already gone | "That device is already gone." |
| `CANNOT_TARGET_CURRENT` | 409 | target is the caller | "That's the device you're using. Use Sign out instead." |
| `STEP_UP_REQUIRED` | 403 | step 3 | password sheet, then retry |
| `RATE_LIMITED` | 429 | limiter | "Too many attempts. Try again in a few minutes." |

### 6.4 Endpoints reused as they are

| Purpose | Endpoint (gateway path) |
|---|---|
| "This was me" on an event | `POST /grapsee/auth/security/events/{id}/ack` |
| Remove trusted device | `POST /grapsee/auth/devices/{id}/revoke` |
| Remove browser session | `DELETE /grapsee/auth/account/sessions?id={id}` |
| Passkey count (for `hasPasskey`, §2.4) | `GET /grapsee/auth/passkeys` |
| Step-up proof | `POST /grapsee/auth/account/verify-password` (new purpose `secure-account`) |

Client models to add: Android `data/Api.kt` (`SecurityOverviewResponse`, `NotMeRequest`, `NotMeResponse`, kotlinx-serialization with `ignoreUnknownKeys = true`); iOS `APIClient.swift` (`Codable` structs + `securityOverview(token:)`, `reportNotMe(token:target:proof:)`); web `src/app/lib/securityOverview.ts` (types + fetchers).

---

## 7. Implementation split

Four workstreams. Each owns its files. **Shared touch points** are listed separately with one owner and an "append only" rule. Work behind RemoteConfig flags (Android `logic/RemoteConfig.kt`, iOS `RemoteConfig.swift`): `round8_identity`, `welcome_back`, `security_center`, `reader_polish`, all default **on** in debug and **off** in release until QA.

### 7.1 Helpers to reuse (do not re-implement)

| Helper | Android | iOS | Web |
|---|---|---|---|
| Width classes / readable width | `ui/components/Adaptive.kt` (`gmWidthClass()`, `gmIsShortWindow()`, `gmIsNarrowWindow()`, `GMLayout.Readable/Form/Compose`, `Modifier.gmMaxWidth`) | `ScaledFont.swift` (`GMLayout.readable/form/compose`, `.gmMaxWidth`, `GMAdaptiveStack`) | Tailwind breakpoints + `max-w-[680px]` |
| Type scale | `ui/theme/Theme.kt` (`H1…Micro`) | `ScaledFont.swift` (`.gmFont(.token)`, `Font.gm(_:scaledFor:)`), `Theme.swift` `GMFont` | — |
| Motion + reduce motion | `ui/components/Motion.kt` (`GMMotion`, `rememberMotionReduced()`) | `Motion.swift` (`GMMotion`, `.gated`, `GMShakeEffect`) | `prefers-reduced-motion`, framer `useReducedMotion` |
| Haptics | `logic/GMHaptics.kt` (`success/failure/filing/marking`) | `Haptics.swift` (`GMHaptics.*`) | — |
| Tips | `ui/components/FeatureTip.kt` + `logic/FeatureTipsStore.kt` | `FeatureTips.swift` (TipKit, iOS 17+ gated) | — |
| Screen states | `ui/components/ScreenStates.kt` | `ScreenStates.swift` | — |
| Security cache | `data/SecurityProfileCache.kt`, `ui/screens/security/SecuritySnapshot.kt` | `SecuritySnapshot.swift` | — |
| Security building blocks | `ui/screens/security/SecurityComponents.kt` | `SecurityComponents.swift` | — |
| Artwork | `ui/artwork/Artwork.kt` | `Artwork.swift` | `public/` assets |
| Passkeys | `security/PasskeyManager.kt` | `PasskeyManager.swift` | WebAuthn in `account/sign-in` |

### 7.2 Workstream A — Identity (tokens, avatars, Match wallpaper)

| Platform | Owns |
|---|---|
| Android | `ui/theme/Color.kt`, `ui/theme/Theme.kt` (tokens, shapes, `dynamicColor` default false + pref), **new** `ui/theme/AvatarColors.kt` (palette + FNV-1a + initials + mask helpers — Workstream B imports `maskAddress` from here), `ui/components/Mail.kt` (avatar composable only: replace `rampIndex`), `ui/components/Account.kt` (account avatar), `ui/components/GrapMailLogo.kt` (gradient), `data/MailDisplayPrefs.kt` (`gm.appearance.matchWallpaper`), the Appearance row in the settings screen (`ui/screens/mail/MailSecondaryScreens.kt` — find with "ReaderColour"), **new** `res/values/strings_identity.xml`, `scripts/mail-honesty.py` |
| iOS | `Theme.swift`, **new** `Avatar.swift` (palette, hash, initials, `maskAddress`, `GMAvatarView`), `MailComponents.swift` (`MailUI.tint` → `Avatar.swift`), `AccountComponents.swift`, `Motion.swift` (add `extraLong`, `celebration`) |
| Web | `src/styles/grapmail.css` (all `--gm-*` per §1.12, radii, motion vars), **new** `src/app/(mail)/mail/_components/avatarColour.ts` (+ test generating `docs/design/avatar-hash-vectors.json`), `src/app/(mail)/mail/page.tsx` **only** the `AVATAR_COLOURS` / `colourFor` / `initialsOf` block (lines ~319–329) → import from `avatarColour.ts`, `_components/senderAvatars.ts` |

### 7.3 Workstream B — Welcome-back sign-in

| Platform | Owns |
|---|---|
| Android | **new** `data/RememberedAccounts.kt` (DataStore `grapmail_remembered`, avatar cache), **new** `ui/screens/account/WelcomeBackScreen.kt`, **new** `ui/screens/account/AccountChooserScreen.kt`, `ui/screens/account/SignInScreen.kt` (entry + prefill), `security/PasskeyManager.kt` (combined passkey + password `GetCredentialRequest`), `data/SessionStore.kt` (hooks: write on sign-in, reason on sign-out/expiry), `data/SessionEvents.kt` / `SessionRefresh.kt` (emit `expired` / `revoked`), `logic/AccountFlowViewModel.kt`, **new** `res/values/strings_welcome_back.xml` |
| iOS | **new** `RememberedAccounts.swift`, **new** `WelcomeBackView.swift` (+ chooser), `AuthScreens.swift` (sign-in entry), `AccountAuthViews.swift`, `SessionStore.swift` (hooks + install-marker wipe), `PasskeyManager.swift` (+ `ASAuthorizationPasswordProvider`), `AccountSwitcher.swift` (sign-out sheet checkbox) |
| Web | `src/app/(auth)/account-signin/page.tsx`, **new** `src/app/(auth)/account-signin/WelcomeBack.tsx`, **new** `src/app/lib/rememberedAccounts.ts`, the sign-out control in `src/app/(mail)/mail/_components/AccountShell.tsx` (checkbox only) |

### 7.4 Workstream C — Security Center + safety score (+ backend contract consumer)

| Platform | Owns |
|---|---|
| Backend (other builder, already in progress) | new route file (e.g. `services/shared/src/routes/auth-security-overview.ts`) implementing §6; `lib/security-events.ts` (`notMeReported` type); migration for `users.password_change_required`, `password_breached_at`, stored geo label; `mintProof` purpose `secure-account` |
| Android | `data/Api.kt` (overview / not-me DTOs + calls — append in a new `// region Round 8 security` block), **new** `ui/screens/security/SecurityCenterScreen.kt`, **new** `ui/screens/security/SafetyScore.kt` (ring, checklist, celebration), **new** `ui/screens/security/NotMeFlow.kt` (sheet, secured screen, recovery review), `ui/screens/security/SecurityHubScreens.kt` (header insert in `AccountSecurityScreen`; `TrustedDevicesScreen` entry → center), `ui/screens/security/SecuritySnapshot.kt`, `data/SecurityProfileCache.kt`, `ui/mailfeatures/MailFeatureCards.kt` (**new** `SafetyNudgeCard` composable), `ui/screens/mail/MailboxScreen.kt` (**one** insertion point for the nudge card + §3.6 banner), **new** `res/values/strings_security_center.xml` |
| iOS | `APIClient.swift` (models + calls in a `// MARK: Round 8 security` extension at the end of the file), **new** `SecurityCenterView.swift`, **new** `SafetyScoreView.swift`, **new** `NotMeFlow.swift`, `SecurityViews.swift` (`AccountSecurityView` header), `DeviceSecurityViews.swift` (`TrustedDevicesView` entry → center), `SecuritySnapshot.swift`, **new** `SafetyNudgeCard.swift`, `MailboxView.swift` (**one** insertion point for the card + banner), `FeatureTips.swift` (add `safetyScore` tip) |
| Web | `src/app/(mail)/mail/account/security/page.tsx`, **new** `src/app/(mail)/mail/_components/SecurityCenter.tsx`, **new** `_components/SafetyScore.tsx`, **new** `_components/NotMeSheet.tsx`, **new** `_components/SafetyNudgeCard.tsx`, **new** `src/app/lib/securityOverview.ts`, `src/app/(mail)/mail/page.tsx` **one** line rendering `<SafetyNudgeCard/>` above the list |

### 7.5 Workstream D — Reader & conversation polish

| Platform | Owns |
|---|---|
| Android | `ui/screens/mail/ThreadScreen.kt`, `ui/screens/mail/MailHtmlBody.kt` (base CSS, quote-collapse script, image aspect reservation, textZoom), `ui/screens/mail/ReaderAdaptive.kt`, `ui/screens/mail/MailOpenTransition.kt`, `ui/screens/mail/AttachmentThumb.kt`, **new** `ui/screens/mail/ConversationCards.kt`, **new** `ui/screens/mail/AttachmentGrid.kt`, **new** `ui/screens/mail/PdfThumbnail.kt`, **new** `ui/screens/mail/QuickReplyBar.kt`, `res/values/strings_reader.xml` (append) |
| iOS | `MailReaderViews.swift`, `MailHtmlBody.swift`, `MailTwoPane.swift`, `QuickLookPresenter.swift`, `AttachmentDrag.swift`, **new** `ConversationCards.swift`, **new** `AttachmentGrid.swift`, **new** `QuickReplyBar.swift` |
| Web | the reader section of `src/app/(mail)/mail/page.tsx` → extract into **new** `src/app/(mail)/mail/_components/reader/` (`ConversationView.tsx`, `MessageCard.tsx`, `QuotedText.tsx`, `AttachmentGrid.tsx`, `PdfThumb.tsx`, `QuickReplyBar.tsx`), `src/app/lib/mailAttachments.ts` |

### 7.6 Shared touch points (one owner, append-only for everyone else)

| File | Owner | Others may | Rule |
|---|---|---|---|
| Android `MainActivity.kt` (route constants + `NavHost`) | C | B adds `WELCOME_BACK = "signin/welcome-back"`, `ACCOUNT_CHOOSER = "signin/choose"`; C adds `SEC_CENTER = "security/center"`, `SEC_NOT_ME_DONE = "security/not-me/done/{device}"`, `SEC_RECOVERY_REVIEW = "security/recovery-review"` | Each workstream adds its constants in its own `// Round 8 — <workstream>` block and its own `composable(...)` block. No edits to others' lines. |
| iOS `RootView.swift` (`Route` enum + destination switch) | C | B adds `case welcomeBack(address: String)`, `case accountChooser`; C adds `case secCenter`, `case secNotMeDone(device: String)`, `case secRecoveryReview` | Same block rule (`// MARK: Round 8 — <workstream>`). |
| iOS `en.lproj/Localizable.strings` (+ `.stringsdict` plurals: "{n} earlier messages", "{n} other sessions", "Review {n} security alerts", "{k} steps to Strong") | each workstream its own section | — | Append-only, `/* MARK: Round 8 — <workstream> */` header. Turkish (`tr.lproj`, `values-tr`) falls back to English until translated. |
| Android `ui/screens/mail/MailboxScreen.kt` | C (card + banner insertion) | A only through `Mail.kt` (no direct edit) | — |
| Web `src/app/(mail)/mail/page.tsx` | D (reader extraction) | A edits only the avatar block; C adds one `<SafetyNudgeCard/>` line | A and C land first; D rebases. |
| `docs/design/*` | this spec | builders may add `avatar-hash-vectors.json` (A) | Changes to §6 need the backend builder's agreement and an update to the JSON Schema in the same commit. |

### 7.7 Merge order

1. A (tokens + avatars). Everything else reads the new tokens.
2. Backend §6 + C, in parallel with B.
3. D last (largest diff, rebases on the A/C one-line edits).

Each workstream attaches light and dark screenshots of phone, tablet and landscape to its PR, plus a reduce-motion screen recording for any animated surface.
