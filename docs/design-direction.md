# Design direction

**Status: approved by the owner on 2026-11-15** ("Approved, continue"), on the second iteration of the prototype.

- Prototype source: [`prototype/activity-detail.html`](../prototype/activity-detail.html) (one self-contained page, fictional data).
- Published copy: private Claude artifact "Running — prototype".

The first iteration (single activity page, bordered cards, elevation-first chart) was rejected as too light and too basic. The approved iteration covers the four screens of the web app and follows the owner's stated preferences.

## Principles

1. **Pastel, borderless.** Surfaces are separated by tone and a very soft shadow, never by a border line. No dividers inside cards; rows get a tonal background on hover instead.
2. **One pastel per kind of information.** Lavender = pace, rose = heart rate, mint = time/cadence, sky = distance/elevation, peach = effort, butter = neutral tags. The same pairing is used for tiles, tags, chart series and activity types.
3. **Icons over labels, with tooltips.** Secondary actions are round icon buttons with a tooltip; every icon-only control has an accessible name.
4. **Visible hover states.** Cards lift, rows tint, bars grow; nothing interactive is static under the pointer.
5. **Pace first.** The chart shows pace by default (faster is higher), with the average marked and one block per kilometre. Heart rate, cadence and elevation are one click away; relief is only a faint silhouette.
6. **Responsive by layout change, not shrinking.** Side rail on desktop, bottom tab bar under 900 px; multi-column grids collapse to one column; wide tables scroll inside their card.
7. **Both themes are designed.** Dark is not an inversion: pastels become deep tinted surfaces with light pastel ink.

## Tokens

These are the source of truth for `web/src/lib/tokens.css`.

### Colour — light

| Token | Value | Use |
|---|---|---|
| `--bg` | `#F4F3F9` | page ground |
| `--surface` | `#FFFFFF` | cards |
| `--surface-2` | `#EFEEF7` | tonal fill, hover, inputs |
| `--surface-3` | `#E6E4F2` | stronger tonal fill |
| `--ink` | `#1E1B2E` | text |
| `--ink-2` | `#54506B` | secondary text |
| `--ink-3` | `#6A6682` | tertiary text, axis labels |
| `--accent` | `#6252DE` | primary action, pace series |
| `--accent-soft` | `#E7E3FC` | selected/hover tint |
| `--on-accent` | `#FFFFFF` | text on accent |
| `--p-lav` / `--i-lav` | `#E7E3FC` / `#5446C9` | pastel fill / its ink |
| `--p-mint` / `--i-mint` | `#D6F3E5` / `#1B7550` | |
| `--p-peach` / `--i-peach` | `#FFE4D3` / `#A94F1C` | |
| `--p-sky` / `--i-sky` | `#D8EAFB` / `#1D639F` | |
| `--p-butter` / `--i-butter` | `#FBF1C9` / `#7D6000` | |
| `--p-rose` / `--i-rose` | `#FCDFE7` / `#A82C5A` | |
| `--c-rose` `--c-mint` `--c-sky` `--c-peach` | `#E46F90` `#35AF80` `#4A97D8` `#EE8A4C` | chart series lines |
| `--z1` … `--z5` | `#A3ABC8` `#7DB7F0` `#6FD0A3` `#FFAE70` `#F58AA5` | heart-rate zones |
| `--pace-fast` `--pace-mid` `--pace-slow` | `#6252DE` `#4FC3A8` `#FFB26B` | trace pace ramp |
| `--map-land` `--map-park` `--map-water` `--map-road` `--map-label` | `#ECEBF4` `#D9F0E2` `#D3E6FA` `#FFFFFF` `#6A6682` | basemap |

### Colour — dark

| Token | Value |
|---|---|
| `--bg` / `--surface` / `--surface-2` / `--surface-3` | `#121118` / `#1B1A25` / `#252431` / `#302F3F` |
| `--ink` / `--ink-2` / `--ink-3` | `#F2F0FA` / `#BDB9D1` / `#9F9BB6` |
| `--accent` / `--accent-soft` / `--on-accent` | `#A99BFF` / `#2D2A4B` / `#15122B` |
| `--p-lav` / `--i-lav` | `#2C2949` / `#BDB2FF` |
| `--p-mint` / `--i-mint` | `#1C372E` / `#8FE0BB` |
| `--p-peach` / `--i-peach` | `#3F2D20` / `#FFC39C` |
| `--p-sky` / `--i-sky` | `#1C2F44` / `#9CCBF7` |
| `--p-butter` / `--i-butter` | `#38321A` / `#EFD877` |
| `--p-rose` / `--i-rose` | `#40222E` / `#F7AFC3` |
| `--c-rose` `--c-mint` `--c-sky` `--c-peach` | `#F58AA5` `#6FD0A3` `#7DB7F0` `#FFAE70` |
| `--pace-fast` `--pace-mid` `--pace-slow` | `#A99BFF` `#5FD6BB` `#FFC08A` |
| `--map-land` `--map-park` `--map-water` `--map-road` `--map-label` | `#191822` `#1B2E29` `#1B2A40` `#292836` `#9F9BB6` |

Zone colours are shared by both themes. Profile avatar colours: `#B9AFFF` `#F7AFC3` `#8FE0BB` `#FFC39C` `#9CCBF7` `#EFD877`.

### Type

- Display and numerals: **Bricolage Grotesque** 500/600/700, tabular figures.
- Body: **Manrope** 400–700.
- Scale: h1 36 px (28 px on phone), h2 20 px, tile value 32 px, body 15 px, small 13 px, eyebrow 12 px uppercase with 0.07em tracking.
- In the application the fonts are self-hosted (no request to Google), unlike the prototype.

### Shape and depth

- Radii: 28 px cards, 20 px tiles and inner blocks, 14 px inputs and rows, full pill for buttons, chips and segmented controls.
- Shadow (light only): `0 12px 32px rgba(70,58,140,.07)`; popovers `0 16px 48px rgba(40,30,90,.18)`. Dark theme uses no card shadow.
- Icons: 24-unit stroke icons, 1.9 px stroke, round caps and joins.

### Breakpoints

- 1180 px: two-column page grids become one column.
- 900 px: side rail becomes a bottom tab bar; tiles go from four to two columns.
- 560 px: segmented controls with many options show only the active label.

## What the prototype does not decide

- The real basemap style: the prototype's map is a drawn stand-in. The MapLibre style must be tuned to the `--map-*` tokens (task 8.2, checked in 8.7).
- Imperial units, GPX export and real file import are inert in the prototype.
