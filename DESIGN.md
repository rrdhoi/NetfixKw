---
name: Cinematic Obsidian
colors:
  surface: '#0f131d'
  surface-dim: '#0f131d'
  surface-bright: '#353944'
  surface-container-lowest: '#0a0e18'
  surface-container-low: '#171b26'
  surface-container: '#1c1f2a'
  surface-container-high: '#262a35'
  surface-container-highest: '#313540'
  on-surface: '#dfe2f1'
  on-surface-variant: '#d8c3ad'
  inverse-surface: '#dfe2f1'
  inverse-on-surface: '#2c303b'
  outline: '#a08e7a'
  outline-variant: '#534434'
  surface-tint: '#ffb95f'
  primary: '#ffc174'
  on-primary: '#472a00'
  primary-container: '#f59e0b'
  on-primary-container: '#613b00'
  inverse-primary: '#855300'
  secondary: '#4cd7f6'
  on-secondary: '#003640'
  secondary-container: '#03b5d3'
  on-secondary-container: '#00424e'
  tertiary: '#c7c8ff'
  on-tertiary: '#1000a9'
  tertiary-container: '#a7a9ff'
  on-tertiary-container: '#2b29bb'
  error: '#ffb4ab'
  on-error: '#690005'
  error-container: '#93000a'
  on-error-container: '#ffdad6'
  primary-fixed: '#ffddb8'
  primary-fixed-dim: '#ffb95f'
  on-primary-fixed: '#2a1700'
  on-primary-fixed-variant: '#653e00'
  secondary-fixed: '#acedff'
  secondary-fixed-dim: '#4cd7f6'
  on-secondary-fixed: '#001f26'
  on-secondary-fixed-variant: '#004e5c'
  tertiary-fixed: '#e1e0ff'
  tertiary-fixed-dim: '#c0c1ff'
  on-tertiary-fixed: '#07006c'
  on-tertiary-fixed-variant: '#2f2ebe'
  background: '#0f131d'
  on-background: '#dfe2f1'
  surface-variant: '#313540'
  surface-default: '#121826'
  surface-elevated: '#1A2234'
  surface-card: '#161D2E'
  rating-amber: '#FBBF24'
  favorite-active: '#EF4444'
  favorite-inactive: '#94A3B8'
  text-primary: '#F8FAFC'
  text-secondary: '#94A3B8'
  text-muted: '#64748B'
  status-error: '#F87171'
  border-subtle: rgba(255, 255, 255, 0.08)
  glass-scrim: rgba(11, 15, 25, 0.82)
typography:
  headline-xl:
    fontFamily: Inter
    fontSize: 36px
    fontWeight: '700'
    lineHeight: 44px
    letterSpacing: -0.02em
  headline-xl-mobile:
    fontFamily: Inter
    fontSize: 28px
    fontWeight: '700'
    lineHeight: 34px
    letterSpacing: -0.01em
  headline-lg:
    fontFamily: Inter
    fontSize: 24px
    fontWeight: '600'
    lineHeight: 30px
    letterSpacing: -0.01em
  headline-sm:
    fontFamily: Inter
    fontSize: 18px
    fontWeight: '600'
    lineHeight: 24px
  title-md:
    fontFamily: Inter
    fontSize: 16px
    fontWeight: '600'
    lineHeight: 22px
  body-lg:
    fontFamily: Inter
    fontSize: 16px
    fontWeight: '400'
    lineHeight: 24px
  body-md:
    fontFamily: Inter
    fontSize: 14px
    fontWeight: '400'
    lineHeight: 20px
  body-sm:
    fontFamily: Inter
    fontSize: 12px
    fontWeight: '400'
    lineHeight: 16px
  label-md:
    fontFamily: Inter
    fontSize: 12px
    fontWeight: '600'
    lineHeight: 16px
    letterSpacing: 0.02em
  label-sm:
    fontFamily: Inter
    fontSize: 11px
    fontWeight: '500'
    lineHeight: 14px
    letterSpacing: 0.03em
  badge-numeric:
    fontFamily: Inter
    fontSize: 12px
    fontWeight: '700'
    lineHeight: 14px
rounded:
  sm: 0.25rem
  DEFAULT: 0.5rem
  md: 0.75rem
  lg: 1rem
  xl: 1.5rem
  full: 9999px
spacing:
  touch-target-min: 48px
  screen-edge-mobile: 16px
  screen-edge-tablet: 24px
  card-gap: 12px
  poster-aspect-ratio: 2/3
  poster-width-list: 92px
  meta-gap: 8px
  chip-gap: 6px
  bottom-nav-height: 64px
---

## Brand & Style

This design system delivers an immersive, dark-first cinema catalog experience engineered around the tactile elegance of native Android Material You (M3) blended with modern cinematic polish. Designed for media enthusiasts, casual viewers, and collectors alike, the visual personality balances high-utility information density with theatrical atmosphere.

The style unifies deep midnight foundations, luminous golden accentuation, and subtle translucent glass layers. The primary emotional tone is focused, modern, and prestigious: metadata (IMDb scores, runtime, releases) remains immediate and glanceable, while key interactive hooks evoke the warm glow of premier cinema projection. Interfaces prioritize content containment, zero-shift loading postures, and robust edge-to-edge Android ergonomics.

## Colors

The palette operates strictly in dark mode, optimizing OLED power efficiency and visual contrast for vibrant movie poster assets.

- **Primary (`#F59E0B`)**: Electric Amber is reserved for high-prominence states—primary call-to-actions, active navigation pills, highlighted text spans, and core interactive anchors.
- **Secondary (`#06B6D4`)**: Neon Cyan provides contrast for specialized information flags, filter chips, and interactive audio/visual specs (4K, HDR, Dolby).
- **Tertiary (`#6366F1`)**: Electric Indigo works as a soft companion for secondary badges, bookmark confirmation toasts, and genre chip surfaces.
- **Neutral (`#0B0F19`)**: Deep Midnight anchors the lowest canvas surface, pairing with tiered slate containers (`#121826` and `#1A2234`) to establish physical depth without pure black starkness.
- **Semantic Functional Colors**:
  - `favorite-active` (`#EF4444` Crimson) signals saved state feedback instantly against the dark backdrop.
  - `rating-amber` (`#FBBF24`) represents IMDb and critic score badges.
  - `status-error` (`#F87171`) designates offline notifications, parsing failures, and network retry prompts.

## Typography

The type hierarchy uses Inter across headlines, body copy, and metadata for mechanical precision, tight tabular numbering, and legible rendering on high-density mobile screens.

- **Title Handling**: Long movie titles on catalog cards truncate cleanly with an ellipsis at 2 lines (`title-md`) to ensure predictable vertical cell height. Detail view titles leverage `headline-xl-mobile` with negative letter-spacing for punchy cinematic scale.
- **Metadata and Plot**: Synopsis copy uses `body-md` with relaxed line-height (`20px`) for readability across sustained reading lengths. 
- **Scores and Micro-Labels**: Rating indicators use `badge-numeric` with proportional tracking, rendering numerical scores (e.g., `8.4`) sharply against solid badge containers.

## Layout & Spacing

Layout adheres to an 8-point structural base grid, using 4-point steps for dense metadata grouping and touch target offsets.

- **Standard Catalog Rhythm**: List layouts follow an asymmetrical split item layout: a locked `92px` fixed-width poster conforming rigidly to a `2:3` aspect ratio on the left, flanked by a dynamic column containing title, tags, rating badge, and metadata on the right.
- **Card-to-Card Spacing**: A uniform `12px` vertical gap (`card-gap`) separates movie items across standard vertical lists, preventing visual clutter while maintaining comfortable list scanning.
- **Touch Target Integrity**: All interactive icon touch targets (such as the favorite heart toggle, clear button, and filter chips) maintain an explicit minimum bounding box of `48px × 48px` regardless of their visual icon size.
- **Form Factors**:
  - **Mobile (<600dp)**: Single column vertical feed with edge margins of `16px`. Persistent 3-destination navigation bar fixed at bottom safe area.
  - **Tablet (≥600dp)**: Content transitions into a dual-column masonry or multi-column grid with `24px` horizontal margins and adaptive navigation rail.

## Elevation & Depth

Visual depth is achieved through layered tonal surfaces and low-contrast perimeter rings rather than heavy dropshadows, ensuring clean rendering against OLED black.

- **Base Layer (0dp)**: `#0B0F19` — Ground canvas for root scaffold and list view backing.
- **Surface Layer 1 (Card Level - 1dp)**: `#121826` — Card background bordered by a subtle 1px translucent perimeter outline (`rgba(255, 255, 255, 0.06)`).
- **Surface Layer 2 (Floating & Modals - 2dp)**: `#1A2234` — Bottom navigation container, modal bottom sheets, and search app bars. Utilizes an ambient tinted shadow: `box-shadow: 0px 8px 24px rgba(3, 7, 18, 0.65)`.
- **Glassmorphic App Bar**: Fixed search headers and floating detail action bars employ `rgba(11, 15, 25, 0.82)` with a `16px` backdrop blur filter, allowing underlying poster artwork to gracefully bleed through during vertical scroll operations.

## Shapes

The design system standardizes on `Level 2` (Rounded) geometry, aligning with native Material 3 specifications.

- **Poster Thumbnails & Movie Cards**: Main cards and image containers utilize `0.75rem` (12px) to `1rem` (16px) corner rounding (`rounded-lg`), ensuring poster corners stay soft and modern.
- **Badges and Pills**: Genre tags, year labels, and IMDb indicators use full pill rounding (`9999px`) for distinction against rectangular media posters.
- **Search Inputs**: Search bars adopt continuous pill geometry with inset icons to present an inviting touch area.

## Components

### Movie Catalog Item (List/Card)
- **Structure**: Horizontal flex row with rigid `2:3` poster asset placeholder on the left (`92px` width) and flexible meta column on the right.
- **Poster Fallback**: If poster image is resolving or missing, render an elevated `#161D2E` block with an embedded neutral film icon (`#64748B`) maintaining strict `2:3` aspect ratio to prevent layout shifting.
- **Action**: Floating or top-right anchored favorite icon toggle (`48px` hit box) using vector transition between outlined `#94A3B8` and filled `#EF4444`.

### Search App Bar
- **Styling**: `#1A2234` surface with rounded pill profile, holding `16px` horizontal padding.
- **Interaction**: Leading search glyph (`#94A3B8`), auto-debounced (350ms) text field, and trailing clear button that renders only when the input string is non-empty.

### Badges & Filter Chips
- **Rating Badge**: Pill container filled with semi-translucent Amber tint (`rgba(245, 158, 11, 0.15)`), displaying a solid Amber star glyph alongside bold `badge-numeric` rating text (`#FBBF24`).
- **Metadata Badges**: Year, runtime, and content ratings render in subtle slate tags (`#1E293B`) with `#94A3B8` text.
- **Filter Chips**: Dynamic selectable chips with horizontal scroll rhythm (`6px` gap). Unselected: `#161D2E` border. Selected: `#F59E0B` active fill with inverted black text (`#0B0F19`).

### Bottom Navigation Bar
- **Surface**: Floating or docked container with `#121826` fill, `16px` backdrop blur scrim, and top 1px border line (`border-subtle`).
- **Items**: 3-item destination architecture (`Movies`, `Search`, `Favorites`). Active destination displays an animated Electric Amber pill indicator with high-contrast active icons.

### System State Indicators
- **Loading Skeleton**: Shimmer effect over `#161D2E` placeholders across poster and metadata text lines, cycling smoothly using an ambient gradient sweep.
- **Empty State**: Centered, subdued cinema vector illustration paired with `headline-sm` title, helper description (`text-secondary`), and a primary button to reset filters or clear search.
- **Error Banner / Toast**: Docked snackbar or inline card in `#1E1B24` with an active crimson alert indicator (`#F87171`) and an Electric Amber inline "Retry" text button.
