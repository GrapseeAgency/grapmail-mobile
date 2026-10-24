# GrapMail Mobile — Design Tokens (measured from founder PNGs)

Every value below was pixel-sampled from the design renders in `raw-designs/`
(light screens) and their dark counterparts. These are the single source of
truth for both native apps.

## Color — Light theme

| Token | Value | Where measured |
|---|---|---|
| `bg` | `#FBFCFE` | welcome/step screens full-bleed |
| `bgTinted` | `#F7F9FC` | sign-in screen |
| `card` | `#FFFFFF` | success card, verification card |
| `softFill` | `#EFF5FD` | info banners, summary chips |
| `infoFill` | `#E3ECFB` | address summary card |
| `border` | `#E1E7F2` | input strokes, card strokes |
| `borderSoft` | `#D9E6F9` | divider strokes |
| `primaryTop` | `#206BFD` | button gradient top (welcome-alt `#2068FC`, address `#1A6EFD` → averaged family) |
| `primaryBottom` | `#1560F5` | button gradient bottom |
| `primarySolid` | `#1A6DF0` | links, active step segment, focus ring |
| `buttonDisabled` | `#C4DBFD` | disabled Continue (terms screen, measured) |
| `textPrimary` | `#0F172A` | headings |
| `textSecondary` | `#5B6B84` | body secondary |
| `textTertiary` | `#94A3B8` | micro labels |
| `success` | `#10B981` | success notes, verified check |
| `error` | `#EF4444` | error notes, field error stroke |
| `stepInactive` | `#DCE6F5` | inactive step segments |

## Color — Dark theme

| Token | Value | Where measured |
|---|---|---|
| `bg` | `#070F18` | password screen full-bleed |
| `bgAlt` | `#081019` | name screen dark variant |
| `card` | `#0C182A` | inputs, cards |
| `inputFill` | `#0D1521` | text fields |
| `border` | `#1B2A44` | strokes |
| `softFill` | `#12233C` | banners |
| `primaryTop/Bottom` | `#1F6DFD` / `#1560F5` | button identical family in dark |
| `buttonDisabled` | `#1E3A66` | derived from dark card + blue family |
| `textPrimary` | `#F1F5F9` | headings |
| `textSecondary` | `#8FA3C0` | body secondary |
| `textTertiary` | `#5E7290` | micro labels |
| `success` | `#34D399` | success notes |
| `error` | `#F87171` | error notes |
| `stepInactive` | `#1B2A44` | inactive segments |

## Typography

| Style | Size / weight | Usage |
|---|---|---|
| H1 | 30 / 700 | "Better Email. A Brighter You.", "You're all set!" |
| H2 | 22 / 700 | step titles ("What's your name?") |
| Title | 17 / 600 | card titles, field section titles |
| Body | 15 / 400-500 | inputs, descriptions |
| Caption | 13 / 400-500 | helper text, links |
| Micro | 11-12 / 500-700 | eyebrow labels ("ACCOUNT CREATED", step counter) |

iOS: SF Pro (system). Android: system sans (Roboto) — designs are Inter-like;
Roboto is the platform-correct equivalent.

## Shape

| Token | Value |
|---|---|
| `radiusButton` | 16 |
| `radiusCard` | 20 |
| `radiusInput` | 14 |
| `radiusChip` | 999 (pill) |
| `radiusSmall` | 10 |
| Button height | 54-56 |
| Input height | 54-56 |
| Screen gutter | 24 |

## Motion (inferred from static PNGs — native, smooth, never childish)

| Interaction | Motion |
|---|---|
| Wizard step change | slide + fade horizontal, 320ms, easing in/out (direction-aware) |
| Step segment fill | animated width, 300ms, spring-ish |
| Button press | scale 0.97 → 1, 120ms |
| Field focus | stroke color crossfade + soft glow, 150ms |
| Error appear | slide-down + fade, 200ms |
| Terms checkbox | spring bounce |
| Puzzle slider | finger-tracking with 0.9 damping on release; success = snap + checkmark pop + haptic |
| Success confetti | gentle particle drift from illustration, 1.2s, once |
| Screen enter (welcome/created) | illustration rises 16dp + fade, 400ms, staggered under text |

## Illustration assets (cropped from founder PNGs — `assets/`)

| File | Content | Used on |
|---|---|---|
| `ill-welcome.png` | envelope + letter + plane + shield scene | Welcome |
| `ill-step1-name.png` | person at desk | Step 1 Name |
| `ill-created-a.png` | envelope checkmark + confetti | Account Created (A) |
| `ill-created-b.png` | glowing envelope celebration | Account Created (B) |
| `puzzle-photo.png` | mountain-lake photo strip | Human Verification slider |

The logo (rounded-square envelope mark + wordmark) is geometric and is
recreated as native vector on both platforms — crisp at every density.
