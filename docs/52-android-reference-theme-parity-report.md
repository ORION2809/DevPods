# DevPods Android UI Reference Theme Parity Report

Date: 2026-06-01

Scope: Compare the current Android Compose UI in `android-relay/app/src/main/java/com/openclaw/relay/ui/` against the reference prototype HTML and screenshots under `docs/prototypes/` and `docs/prototypes/assets/`. This report focuses on visual theme, color usage, component structure, screen rhythm, and UX information architecture.

Reference sources:

- `docs/prototypes/devpods-relay-mobile-ui-prototype.html`
- `docs/prototypes/devpods-premium-entry-hero.html`
- `docs/prototypes/assets/Screenshot 2026-05-12 155311.png`
- `docs/prototypes/assets/Screenshot 2026-05-12 155327.png`
- `docs/prototypes/assets/Screenshot 2026-05-12 155343.png`
- `docs/prototypes/assets/Screenshot 2026-05-12 155356.png`
- `docs/prototypes/assets/Screenshot 2026-05-12 155448.png`
- `docs/prototypes/assets/Screenshot 2026-05-12 155500.png`

Current implementation sources inspected:

- `android-relay/app/src/main/java/com/openclaw/relay/ui/theme/Color.kt`
- `android-relay/app/src/main/java/com/openclaw/relay/ui/theme/Theme.kt`
- `android-relay/app/src/main/java/com/openclaw/relay/ui/theme/Shape.kt`
- `android-relay/app/src/main/java/com/openclaw/relay/ui/theme/Type.kt`
- `android-relay/app/src/main/java/com/openclaw/relay/ui/components/`
- `android-relay/app/src/main/java/com/openclaw/relay/ui/screens/`
- `android-relay/app/src/main/java/com/openclaw/relay/MainActivity.kt`
- Existing runtime captures under `docs/ui-audit/2026-05-12/`

## Executive Verdict

The current Android UI is on the right brand path, but it is not yet identical to the reference UI.

The strongest part is the palette: `DevPodsColor` already matches nearly every core reference token. The biggest remaining gaps are not basic colors. They are the shell, spacing, card rendering, typography, background blobs, bottom navigation, setup/onboarding flow, and information architecture.

If the goal is pixel-level reference parity, the app needs a deliberate UI hardening pass before it is considered visually done. The work should start at the shared design system layer instead of editing each screen by hand.

Highest-impact gaps:

1. The standard bottom nav has the wrong structure. Reference standard mode has `Home`, `Activity`, `Device`, `Help`; Dev mode adds `Dev`. Current code adds `Settings` as a standard tab and can show six tabs with Dev.
2. Onboarding and setup bypass the shared app shell and do not use the same topbar, note slot, background blobs, or bottom nav rhythm as the reference.
3. The background uses the right colors but not the reference geometry. Current radial glows are full-screen center gradients; the prototype uses fixed top-left mint and top-right amber blobs.
4. Cards are close, but not exact. Current cards miss the accent-tinted secondary background layer, the shine overlay is likely ineffective, and padding is 16dp where the prototype uses 20px.
5. Typography uses Android default fonts instead of Inter/system for the mobile app. There are no bundled fonts under `android-relay/app/src/main/res/font`.
6. Screen horizontal padding is inconsistent and usually too narrow. The prototype phone shell uses 24px page padding; current screens mostly use 16dp or 20dp.
7. Reference UX treats Settings and fallback controls as Help surfaces. Current implementation promotes Settings to a primary navigation tab, which changes the product model.

## Reference Design Contract

### Core Tokens

The reference mobile UI defines these operational tokens:

| Token | Reference value | Intended use |
| --- | --- | --- |
| `--bg` | `#f5f1e8` | App screen background inside phone |
| `--surface` | `#fffcf4` | Button/card base surface |
| `--surface-2` | `#f0e9dc` | Muted chips, tracks, inactive controls |
| `--ink` | `#0d1b1e` | Primary text, dark hero panels |
| `--ink-2` | `#173235` | Secondary dark panel shade |
| `--muted` | `#62716d` | Body copy and inactive labels |
| `--line` | `#d8ded5` | Secondary button and divider borders |
| `--teal` | `#0f766e` | Primary action, ready state, standard mode |
| `--mint` | `#35d68b` | Waveform, live/audio signal |
| `--mint-soft` | `#ddf8e9` | Success chips, active nav background |
| `--amber` | `#b96a16` | Setup, degraded, reconnecting, Dev mode |
| `--amber-soft` | `#fff1d6` | Warning chips and Dev active nav |
| `--red` | `#b42318` | Error, hard approval, destructive action |
| `--red-soft` | `#ffe1dc` | Danger chips and approval rejection surfaces |
| `--blue` | `#2563eb` | Activity, diagnostics, info state |
| `--blue-soft` | `#e1eaff` | Info chips |
| Canvas | `#e9e2d6` | Outer prototype/tabletop background |

Current status: `Color.kt` matches almost all operational tokens exactly. Missing or under-modeled tokens are the outer canvas `#e9e2d6`, glass alpha tokens, border alpha tokens, and shadow tokens.

### Shell Geometry

Reference phone shell:

- Width: `390px`
- Height: `844px`
- Radius: `34px`
- Page padding: `0 24px`
- Status row height: `46px`
- Topbar margin-top: `8px`
- Note margin-top: `20px`
- Bottom nav: left/right `16px`, bottom `20px`, height `68px`, radius `28px`

Android does not need to render a fake phone frame, but it should preserve the same internal rhythm:

- screen content horizontal padding should be 24dp, not 16dp;
- nav horizontal inset should remain 16dp;
- every main screen should share a header slot: title, mode pill, and a short support/note line;
- setup and onboarding should use the same product background language unless intentionally using the premium entry hero.

### Component Anatomy

Reference card:

```css
background:
  linear-gradient(145deg, rgba(255,255,255,.74), rgba(255,255,255,.34)),
  linear-gradient(135deg, color-mix(in srgb, var(--accent) 12%, transparent), rgba(255,252,244,.54));
border: 1px solid rgba(255,255,255,.72);
border-radius: 24px;
box-shadow:
  inset 0 1px 0 rgba(255,255,255,.86),
  inset 0 -18px 36px rgba(255,255,255,.18),
  0 18px 46px rgba(13,27,30,.12);
padding: 18px 20px;
```

Reference card also has:

- a 7px semantic accent rail;
- accent glow around that rail;
- a screen-like shine overlay using `radial-gradient(circle at 18% 8%, ...)`;
- no nested card-in-card framing;
- danger cards use a red-soft tinted background, not only a red accent rail.

Current status: `DevPodsCard.kt` has the right concept, rail width, shape, and base white glass layer. It is still missing the exact accent tint layer and likely does not render the shine overlay correctly because the overlay uses `height(IntrinsicSize.Min)` without content.

Reference button:

- visual height: `52px`
- pill radius: `999px`
- primary: teal filled, white text, soft shadow;
- secondary: surface background, `line` border, teal text;
- danger: red-soft background, red text, `#f4a79e` border.

Current status: `DevPodsButton.kt` has correct pill shape, press scale, ripple, and 48dp minimum height. It does not draw the reference secondary/danger borders, primary shadow, or exact 52dp height.

Reference chip:

- min-height: `32px`
- dot size: `8px`
- radius: pill;
- font: 12px, weight 800;
- semantic color families: mint/teal, amber, red, blue, muted.

Current status: `DevPodsChip.kt` is very close. It should add `heightIn(min = 32.dp)` for exactness and keep dot behavior consistent across all state chips.

Reference nav:

- standard mode: four tabs only: Home, Activity, Device, Help;
- developer mode: five tabs, adding Dev;
- active tab: mint-soft or amber-soft pill;
- inactive tab: muted dot + label;
- no Settings tab in primary nav.

Current status: `BottomNav.kt` has icons, five standard tabs, and six Dev tabs. That is the biggest UX parity miss.

## Current Implementation Parity Matrix

| Area | Current implementation | Reference expectation | Parity |
| --- | --- | --- | --- |
| Core palette | `Color.kt` matches `#f5f1e8`, `#fffcf4`, `#0f766e`, `#35d68b`, etc. | Same values | Strong |
| Outer canvas | No `#e9e2d6` token | Use for framed previews, screenshots, and any marketing shell | Missing |
| Background blobs | `DevPodsBackground` uses two full-screen radial gradients | Fixed mint blob at `left -92/top -88`, amber blob at `right -76/top 38` | Partial |
| Typography | `FontFamily.Default`, Android runtime font | Inter/system for mobile prototype | Partial |
| Letter spacing | Current has mild negative spacing on larger headlines | Prototype has stronger display tightening, but body/title should stay clean | Partial |
| Page padding | Mostly 16dp, onboarding/setup 20dp, topbar 20dp | 24px screen padding, 16px nav inset | Partial |
| Topbar | Shared only inside `RelayAppShell` | Shared on every reference screen | Partial |
| Note/support line | Mostly absent in current screens | Present below topbar on reference screens | Partial |
| Onboarding | Dark rectangular hero, no shell topbar/mode/note | Circular earbud visual, standard topbar, note slot, 24px rhythm | Partial |
| Setup wizard | Bypasses shell, no bottom nav, flat background | Reference setup remains in same product shell rhythm | Partial |
| Cards | Glass card with accent rail | Glass card with accent tint, shine overlay, inset highlights, exact padding | Close but not exact |
| Buttons | Correct colors, no secondary/danger borders | Border and shadow system is visible | Partial |
| Chips | Very close | 32px min height and dot language everywhere | Close |
| Bottom nav | Icons plus labels, 5 or 6 tabs | Dot plus labels, 4 or 5 tabs | Mismatch |
| Settings IA | Settings is primary tab | Settings/fallback controls live under Help or as secondary surface | Mismatch |
| Dev mode | Dedicated screen exists | Dev mode isolated and amber-coded | Close, but nav count wrong |
| Diagnostics consent | Implemented in Help | Reference pattern exists | Close, but current rendering needs density pass |
| Empty states | Activity empty states implemented | Reference success/empty cards | Close |
| QR failure | Device error card exists | Reference has scan-window modal state | Partial |
| Notification controls | Not represented in Compose shell | Reference includes foreground notification action design | Needs separate Android notification parity pass |

## Detailed Findings

### P0-1: Standard Navigation Structure Does Not Match The Reference

Evidence:

- Reference nav CSS is `repeat(4, 1fr)` for standard mode and `.dev-nav { grid-template-columns: repeat(5, 1fr); }`.
- Reference screenshots show `Home`, `Activity`, `Device`, `Help`; Dev mode adds `Dev`.
- Current `BottomNav.kt` defines `Home, Activity, Device, Settings, Help, Dev`.
- Current `BottomNav.kt` always adds Settings before Help, and conditionally adds Dev.

Impact:

This changes the product mental model. The reference says:

- Home = product state
- Activity = transcript + approvals
- Device = pairing + capability truth
- Help = recovery + diagnostics
- Dev = operator console

The current Settings tab splits recovery/preferences away from Help and turns standard mode into a five-tab app. In Dev mode it becomes six tabs, which is visually crowded and not reference-identical.

Required change:

- Remove `Settings` from `DevPodsTab`.
- Move the Settings content into Help as a card/sheet/secondary route, or open it from Help via a button.
- Keep bottom nav standard mode at four items.
- Keep Dev mode at five items.
- If implementation wants Android icons for platform familiarity, decide explicitly. For exact prototype parity, use the reference dot+label nav, not icons.

Files:

- `android-relay/app/src/main/java/com/openclaw/relay/ui/components/BottomNav.kt`
- `android-relay/app/src/main/java/com/openclaw/relay/MainActivity.kt`
- `android-relay/app/src/main/java/com/openclaw/relay/ui/screens/SettingsScreen.kt`
- `android-relay/app/src/main/java/com/openclaw/relay/ui/screens/HelpScreen.kt`

### P0-2: Onboarding And Setup Bypass The Reference Shell

Evidence:

- `MainActivity.kt` returns early for `OnboardingScreen` and `SetupWizardScreen` before `RelayAppShell`.
- `OnboardingScreen.kt` uses a direct `Column`, `statusBarsPadding`, vertical scroll, and 20dp padding.
- `SetupWizardScreen.kt` uses a direct `Column`, `statusBarsPadding`, flat `DevPodsColor.Background`, and 20dp padding.
- The shared `DevPodsBackground`, `TopBar`, and `BottomNav` only exist in `RelayAppShell`.

Impact:

The two most important first-run surfaces do not share the same reference rhythm as the rest of the app. The prototype first launch has:

- status row/topbar;
- `DevPods` title;
- `Standard mode` pill;
- note/support copy slot;
- soft top-left mint and top-right amber background shapes;
- bottom nav on setup/device states;
- 24px internal shell padding.

Current onboarding looks close in brand but not in layout. It uses a dark rectangular hero instead of the reference circular earbud visual. Current setup is functional but too plain and does not feel like the same finished product shell.

Required change:

- Introduce a shared `DevPodsScreenShell` used by Home, Activity, Device, Help, Dev, Onboarding, and Setup.
- Shell should provide background blobs, topbar, optional note text, content padding, and optional bottom nav.
- Onboarding can use the premium entry concept only if intentionally upgraded, but the operational mobile reference expects the circular earbud visual rather than a full-width dark rectangle.
- Setup should be full-screen in content priority, but not visually disconnected from the product shell.

Files:

- `android-relay/app/src/main/java/com/openclaw/relay/MainActivity.kt`
- `android-relay/app/src/main/java/com/openclaw/relay/ui/screens/OnboardingScreen.kt`
- `android-relay/app/src/main/java/com/openclaw/relay/ui/screens/SetupWizardScreen.kt`
- `android-relay/app/src/main/java/com/openclaw/relay/ui/components/DevPodsBackground.kt`
- `android-relay/app/src/main/java/com/openclaw/relay/ui/components/TopBar.kt`

### P0-3: Background Geometry Is Not Reference-Accurate

Evidence:

Reference:

- App base: `#f5f1e8`
- Outer canvas: `#e9e2d6`
- Top-left blob: mint, `left: -92px`, `top: -88px`, `260px`
- Top-right blob: amber, `right: -76px`, `top: 38px`, `220px`

Current:

- `DevPodsBackground.kt` fills `DevPodsColor.Background`.
- It layers two `Brush.radialGradient` backgrounds over the entire screen.
- The gradients do not specify the reference top-left/top-right centers.

Impact:

The current app gets the warm color but misses the signature reference composition. The reference screenshots have visible anchored blobs that create a product-specific identity. The current implementation will read more like a soft full-screen wash.

Required change:

- Add tokens:
  - `Canvas = #E9E2D6`
  - `GlowMint = #35D68B`
  - `GlowAmber = #F4B860`
- Replace full-screen radial layers with positioned oversized circles or a `Canvas` draw pass.
- Use these approximate Compose dimensions:
  - mint blob: `260.dp`, offset `x = -92.dp`, `y = -88.dp`, alpha `0.14f`;
  - amber blob: `220.dp`, align top-end, offset `x = 76.dp`, `y = 38.dp`, alpha `0.13f`;
  - optional outer/screenshot canvas: `#E9E2D6`.

File:

- `android-relay/app/src/main/java/com/openclaw/relay/ui/components/DevPodsBackground.kt`

### P1-1: Card Rendering Is Close But Not Exact

Evidence:

Current `DevPodsCard.kt` includes:

- 24dp radius via `DevPodsShapes.large`;
- 18dp elevation shadow;
- white glass linear gradient;
- 1dp white border;
- 7dp accent rail.

Gaps:

- Missing the second accent-tinted gradient layer.
- Missing inset top and bottom highlights.
- Shine overlay uses `height(IntrinsicSize.Min)` without content, so it can render as zero height or much smaller than the card.
- Shine gradient center uses `Offset(0.18f, 0.08f)`, but Compose offsets are pixels, not percentages.
- Content padding is `horizontal = 16.dp`, but reference is `18px 20px`.
- Danger cards often use only a red rail. Reference danger/approval cards also tint the card surface red-soft.

Required change:

- Build a `DevPodsGlassCard` implementation using `drawWithCache` or layered `Box(matchParentSize())`.
- Add a second background layer using the accent at 10-12 percent opacity.
- Replace the current shine overlay with a `matchParentSize()` overlay.
- Use content padding `horizontal = 20.dp`, `vertical = 18.dp`.
- Add a `surfaceTone` or `CardTone` parameter:
  - `Normal`
  - `Danger`
  - `Modal`
  - `DarkHero`
- For danger cards, use red-soft tint plus red border, not only red accent rail.

File:

- `android-relay/app/src/main/java/com/openclaw/relay/ui/components/DevPodsCard.kt`

### P1-2: Button Rendering Needs Border And Shadow Tokens

Evidence:

Current `DevPodsButton.kt`:

- uses the correct teal, red-soft, and surface colors;
- uses pill clipping;
- uses press scale and ripple;
- uses `heightIn(min = 48.dp)`;
- does not draw the reference secondary border;
- does not draw the reference danger border;
- does not add the primary button shadow.

Reference:

- Primary button: teal fill, white text, shadow `0 12px 22px rgba(13,27,30,.16)`;
- Secondary button: surface fill, `line` border, teal text;
- Danger button: red-soft fill, red text, `#f4a79e` border;
- Visual height: 52px.

Required change:

- Add a border color to `ButtonStyle`.
- Add primary shadow with 10-12dp elevation or a custom shadow modifier.
- Set standard visual height to 52dp while keeping minimum touch target >= 48dp.
- Add a separate compact button style for dense Dev mode and notification action surfaces. Do not shrink the touch target below accessibility minimum.

File:

- `android-relay/app/src/main/java/com/openclaw/relay/ui/components/DevPodsButton.kt`

### P1-3: Typography Is Not Reference-Accurate

Evidence:

- Reference mobile UI uses `Inter, ui-sans-serif, system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif`.
- Current `Type.kt` uses `FontFamily.Default` for every type style.
- There is no `res/font` directory with Inter/Manrope/Space Grotesk assets.

Impact:

Even with the exact colors, Android default typography will not match the prototype. The current screenshots read heavier and more Android-native than the reference.

Required change:

- Add Inter font files under `android-relay/app/src/main/res/font/`.
- Define:
  - `Inter` for operational app UI;
  - optional `Manrope` and `Space Grotesk` only if the premium entry hero is implemented.
- Keep letter spacing at `0.sp` for compact UI, except large hero/display text where the reference intentionally tightens.
- Match key sizes:
  - app title: 22sp/800;
  - hero title: 31sp, tight line-height;
  - card title: 18sp bold;
  - card body: 13-15sp muted;
  - chip: 12sp extra-bold.

File:

- `android-relay/app/src/main/java/com/openclaw/relay/ui/theme/Type.kt`

### P1-4: Screen Padding And Vertical Rhythm Drift From The Prototype

Evidence:

Reference:

- phone content padding is `0 24px`;
- topbar appears after status row, then a 20px note gap;
- cards use `margin-top` values around 20-32px depending hierarchy;
- bottom nav is independently inset by 16px.

Current:

- `HomeScreen` uses `padding(horizontal = 16.dp)`.
- `ActivityScreen` uses `padding(horizontal = 16.dp)`.
- `DeviceScreen` uses `padding(16.dp)`.
- `HelpScreen` uses `padding(16.dp)`.
- `SettingsScreen` uses `padding(horizontal = 16.dp)`.
- `OnboardingScreen` and `SetupWizardScreen` use 20dp horizontal padding.
- `TopBar` uses 20dp horizontal padding.

Impact:

Cards are too wide and the app loses the airy reference spacing. Different first-run and main screens also feel like separate design passes.

Required change:

- Create `DevPodsSpacing` tokens:
  - `screenX = 24.dp`
  - `navX = 16.dp`
  - `sectionGap = 24.dp`
  - `cardGap = 20.dp`
  - `cardX = 20.dp`
  - `cardY = 18.dp`
  - `buttonH = 52.dp`
  - `navH = 68.dp`
- Replace screen-level 16dp/20dp padding with shell-owned 24dp content padding.
- Keep bottom nav inset at 16dp.

Files:

- `android-relay/app/src/main/java/com/openclaw/relay/ui/theme/`
- all screen files under `android-relay/app/src/main/java/com/openclaw/relay/ui/screens/`

### P1-5: Settings Screen Is Visually And Conceptually Off-Reference

Evidence:

- Prototype note: "Settings and fallback controls are first-class Help surfaces."
- Prototype standard nav has no Settings tab.
- Current `SettingsScreen.kt` is a full bottom-tab destination.
- Current `SettingsSectionCard` wraps content in `DevPodsCard`, then adds another `Column.padding(16.dp)`, creating card padding that does not match the reference.
- Current settings uses Material `Switch` with default switch styling unless inherited from the color scheme.

Impact:

The screen may be functional, but it does not match the prototype's UX hierarchy. It feels like a generic Android settings list inside the DevPods skin instead of a reference-designed Help/recovery surface.

Required change:

- Move Settings into Help or a Help-launched sheet.
- Restyle settings sections as reference cards with 20dp card padding only once.
- Use custom reference toggle rows for fallback controls and Material switches only if fully themed.
- Replace text-heavy raw setup fields like `Setup phase: X` with user-facing status chips.
- Use compact segmented controls for notification style instead of three large primary-looking buttons.
- Destructive actions like learned phrase delete/reset should use danger styling and confirmation/undo.

Files:

- `android-relay/app/src/main/java/com/openclaw/relay/ui/screens/SettingsScreen.kt`
- `android-relay/app/src/main/java/com/openclaw/relay/ui/screens/HelpScreen.kt`
- `android-relay/app/src/main/java/com/openclaw/relay/ui/components/BottomNav.kt`

### P1-6: Onboarding Should Use The Reference Product Promise Layout

Reference:

- Top shell present.
- Note says first launch/product promise.
- Large circular earbud visual, not a full-width rectangular dark panel.
- Hero headline below the visual.
- Three feature cards.
- Primary CTA at bottom.

Current:

- No topbar/mode pill.
- Dark full-width hero rectangle.
- Feature cards are present and close.
- Copy differs from prototype: current says "Tap, long-press..." while reference first-launch copy says "Pair the desktop bridge, verify your earbuds, then speak short developer commands hands-free."

Required change:

- Replace the dark rectangular onboarding hero with a circular earbud/waveform visual component.
- Use the same shell/topbar/note/background blobs as the reference.
- Keep feature cards but apply exact card padding and 24dp screen padding.
- Use the reference first-launch body copy unless product copy has been intentionally approved otherwise.
- The premium entry hero HTML can inform a richer first-run hero later, but it should not replace the operational mobile shell unless the team intentionally chooses a cinematic onboarding.

File:

- `android-relay/app/src/main/java/com/openclaw/relay/ui/screens/OnboardingScreen.kt`

### P1-7: Setup Wizard Should Match The Reference Guided Setup State

Reference:

- Setup is visually part of the product shell.
- Progress is a thin teal track, not an amber-to-teal gradient except for queue/reconnect state.
- Wake test uses the dark panel with waveform and timer.
- Primary CTA plus fallback action are clearly stacked.

Current:

- Setup bypasses `RelayAppShell`.
- Progress uses `QueueMeter`, which is amber-to-teal. That gradient makes sense for queue/retry, but the guided setup progress reference is solid teal.
- Setup step count is inconsistent in code: some copy says 4 steps while phase logic maps to 5.
- Gesture mapping can create nested cards inside a card-like screen section.

Required change:

- Add a separate `SetupProgressBar` with solid teal fill.
- Normalize setup copy to one step model.
- Place setup inside the shared reference shell or at least reuse background/topbar/note.
- Keep wake/STT test dark panels, but match reference dark-panel radius, spacing, waveform sizes, and CTA layout.
- Avoid nested `DevPodsCard` structures inside gesture mapping; use rows or segmented pills within one glass card.

File:

- `android-relay/app/src/main/java/com/openclaw/relay/ui/screens/SetupWizardScreen.kt`

### P2-1: Home Is Close But Needs Exact Hero And Status Rhythm

Current positives:

- Home has state-driven sections.
- Ready/listening/autonomy/queue/approval states are represented.
- Dark hero card exists.
- Status chips use the right semantic color families.

Gaps:

- Reference Home has a note/support line under the topbar.
- Reference ready hero has the chip at top-left and waveform visually placed at top-right; current centers chip/waveform together.
- Reference hero action buttons overlap the bottom of the hero in a controlled row; current code overlaps too, but spacing should be checked after card padding changes.
- Current body copy for ready state is dynamic and can diverge from reference layout. It needs a maximum line width and fixed vertical rhythm.

Required change:

- Add the shell note slot: "Home answers: what state are we in, and what should I do next?" for prototype parity, or a user-facing equivalent.
- Align chip/waveform with the reference hero composition.
- Keep status chips in two-row wrapping layout, but tune chip spacing to match 8px gap.

File:

- `android-relay/app/src/main/java/com/openclaw/relay/ui/screens/HomeScreen.kt`

### P2-2: Activity Is Functionally Close But Detail Sheet Needs Reference Treatment

Current positives:

- Approval pending card, conversation, timeline, empty states, history, and diagnostics/sent confirmation are implemented.
- Activity history now uses Material icons, which is better than raw symbols.

Gaps:

- Reference approval detail is visually a modal sheet with a handle, centered heading, risk grid, consequence row, gesture row, and bottom action pair.
- Current detail uses a `DevPodsCard` inline in Activity. It has the right content pieces but not the exact sheet treatment.
- Danger approval cards need red-soft surface tint, not only red rail.

Required change:

- Create `ApprovalDetailSheet` style as a modal/bottom-sheet visual component.
- Add `CardTone.Danger` for hard approval cards.
- Match risk grid cell radius, spacing, and typography.

File:

- `android-relay/app/src/main/java/com/openclaw/relay/ui/screens/ActivityScreen.kt`

### P2-3: Device Has The Right Content But Is Too Dense And Not Reference-Ordered

Current positives:

- Device owns provider status, pairing, setup lifecycle, calibration, capability, bridge management, fallbacks, and speech/output.
- That matches the reference concept that Device owns hardware truth.

Gaps:

- Reference starts with a concise unverified card, pairing card, then CTA, setup state, capability truth, bridge management.
- Current Device includes many more cards in one vertical scroll and can bury the primary next action.
- QR placeholder is much larger than the reference card layout and not styled like the prototype QR tile.
- Some current cards use generic/no accent color by default, which makes the semantic rail less intentional.

Required change:

- Reorder Device by next best action:
  1. Verification/setup status
  2. Pair bridge
  3. Guided setup CTA/state
  4. Calibration summary
  5. Capability summary
  6. Bridge management
  7. Listening fallbacks
  8. Speech/output
- Collapse advanced provider details behind a card expansion or Help/Dev detail surface.
- Match reference QR tile dimensions and side-by-side pairing card layout.

File:

- `android-relay/app/src/main/java/com/openclaw/relay/ui/screens/DeviceScreen.kt`

### P2-4: Help Is Close But Should Absorb Settings And Reduce Engineering Density

Current positives:

- Help includes bridge recovery, permissions, voice proof, diagnostics, accessibility, localization, and version mismatch.
- This mostly maps to the reference recovery/diagnostics surfaces.

Gaps:

- Help is very long and engineering-heavy.
- Voice proof matrix is useful but may belong under Dev or an advanced diagnostics expansion.
- Settings exists separately, but reference treats settings/fallback controls as Help surfaces.
- Modal permission state is represented as a card, but reference has a dimmed modal layer.

Required change:

- Make Help the standard destination for:
  - recovery actions;
  - permission repair;
  - fallback controls;
  - diagnostics consent;
  - notification preferences/settings entry.
- Move voice proof advanced metrics to an "Advanced diagnostics" section or Dev mode.
- Implement reference modal layer for permission and QR failure states.

File:

- `android-relay/app/src/main/java/com/openclaw/relay/ui/screens/HelpScreen.kt`

### P2-5: Dev Mode Needs Reference Compactness Without Losing Accessibility

Current positives:

- Dev mode is isolated.
- Amber mode badge is present.
- QA controls are not shown in standard mode.

Gaps:

- Current bottom nav can become six tabs because of Settings.
- Current Dev relay controls use regular 48dp min buttons, which makes the console much taller than the reference.
- Current screenshots show Dev controls visually dominating the screen.

Required change:

- Standard nav four tabs, Dev nav five tabs.
- Add a compact visual button for Dev controls while preserving 48dp touch target.
- Keep amber as the mode color; avoid teal-filled buttons for every Dev control.

File:

- `android-relay/app/src/main/java/com/openclaw/relay/ui/screens/DeveloperModeScreen.kt`

## Recommended Implementation Plan

### Phase 1: Lock The Design Tokens

Create or extend:

- `DevPodsColor`
- `DevPodsSpacing`
- `DevPodsElevation`
- `DevPodsAlpha`
- `DevPodsComponentTokens`

Add missing tokens:

```kotlin
val Canvas = Color(0xFFE9E2D6)
val Glass = Color.White.copy(alpha = 0.58f)
val GlassStrong = Color.White.copy(alpha = 0.74f)
val GlassBorder = Color.White.copy(alpha = 0.72f)
val DangerBorder = Color(0xFFF4A79E)
val GlowAmber = Color(0xFFF4B860)
```

Add spacing:

```kotlin
object DevPodsSpacing {
    val screenX = 24.dp
    val navX = 16.dp
    val navBottom = 20.dp
    val navHeight = 68.dp
    val cardX = 20.dp
    val cardY = 18.dp
    val cardGap = 24.dp
    val buttonHeight = 52.dp
    val chipMinHeight = 32.dp
}
```

### Phase 2: Refactor The Shared Shell

Build:

- `DevPodsScreenShell`
- `DevPodsScreenHeader`
- `DevPodsBackground`
- `DevPodsBottomNav`

Shell requirements:

- anchored mint and amber blobs;
- topbar with `DevPods` and mode pill;
- optional note/support line slot;
- 24dp content padding;
- 16dp nav inset;
- bottom nav standard/dev rules.

Use this shell for:

- onboarding;
- setup;
- Home;
- Activity;
- Device;
- Help;
- Dev.

### Phase 3: Make Components Reference-Exact

Update:

- `DevPodsCard`
- `DevPodsHeroCard`
- `DevPodsButton`
- `DevPodsChip`
- `QueueMeter`
- `Waveform`
- toggle rows;
- checkbox rows;
- modal/sheet components.

Component requirements:

- cards use two-layer glass background and correct shine;
- cards use 20dp horizontal and 18dp vertical padding;
- buttons use borders and shadows;
- chips have 32dp minimum height;
- waveform bars should match reference proportions more closely: 9 bars, 7dp-ish visual width, 8dp-ish gap, same height sequence;
- toggles should match the reference teal switch rows.

### Phase 4: Fix Information Architecture

Required for reference parity:

- Remove Settings from bottom nav.
- Move Settings content under Help as:
  - "Notification preferences"
  - "Workspace nudges"
  - "Reminders"
  - "Learned phrases"
- Keep Dev as hidden/progressively disclosed.
- Keep Device focused on pairing, setup, calibration, capability truth, and bridge management.

### Phase 5: Screen Parity Pass

Screen-by-screen targets:

| Screen | Target |
| --- | --- |
| Onboarding | Match first reference phone: shell, note, circular earbud visual, feature cards, CTA |
| Setup | Match guided setup reference: shell, solid teal progress, dark wake/STT panel |
| Home | Match ready/autonomy/offline queue state references |
| Activity | Match approval/global timeline/detail sheet references |
| Device | Match pairing, capability, setup lifecycle, bridge management references |
| Help | Match recovery, permissions, diagnostics consent, accessibility/localization references |
| Dev | Match amber-mode isolated console reference |

### Phase 6: Screenshot Parity Gate

Before calling this done, capture fresh emulator screenshots for:

- first launch/onboarding;
- pairing/device setup;
- guided wake test;
- ready Home;
- pending approval Home/Activity;
- approval detail sheet;
- offline queue;
- Device capability summary;
- bridge management;
- Help recovery;
- diagnostics consent;
- Settings/Help preferences surface;
- Dev mode.

Compare against `docs/prototypes/assets/` using this checklist:

- same warm base color;
- visible anchored mint and amber blobs;
- 24dp screen content padding;
- card radii and rail width match;
- card shine visible but subtle;
- secondary buttons have visible line border;
- danger buttons have red-soft fill and red border;
- standard nav has exactly four tabs;
- Dev nav has exactly five tabs;
- no Settings primary tab;
- onboarding and setup share the same shell language;
- no topbar/status overlap;
- no text clipping in nav, chips, buttons, or cards.

## File-by-File Change Map

| File | Required change |
| --- | --- |
| `ui/theme/Color.kt` | Add canvas, glass, alpha/border/shadow tokens while keeping current exact palette |
| `ui/theme/Type.kt` | Add Inter font family; optionally add Manrope/Space Grotesk for premium entry only |
| `ui/theme/Shape.kt` | Add component-specific radii: card 24, nav 28, sheet/modal 30-32, shell preview 34 |
| `ui/components/DevPodsBackground.kt` | Replace full-screen radial gradients with anchored reference blobs |
| `ui/components/DevPodsCard.kt` | Add accent tint layer, fixed shine overlay, 20x18 padding, danger tone |
| `ui/components/DevPodsButton.kt` | Add border/shadow tokens and exact 52dp visual height |
| `ui/components/DevPodsChip.kt` | Add 32dp min height; keep dot semantics consistent |
| `ui/components/BottomNav.kt` | Remove Settings tab, support 4 standard/5 dev items, choose dot+label for exact parity |
| `ui/components/TopBar.kt` | Use 24dp horizontal rhythm and expose note/header shell integration |
| `MainActivity.kt` | Stop bypassing shell for onboarding/setup; remove Settings tab routing from primary nav |
| `ui/screens/OnboardingScreen.kt` | Match reference first-launch layout and circular earbud visual |
| `ui/screens/SetupWizardScreen.kt` | Match guided setup shell and progress treatment |
| `ui/screens/HomeScreen.kt` | Align hero chip/waveform/action layout and note rhythm |
| `ui/screens/ActivityScreen.kt` | Add reference modal sheet treatment and danger card tone |
| `ui/screens/DeviceScreen.kt` | Reorder and tighten Device content to match reference state order |
| `ui/screens/HelpScreen.kt` | Absorb Settings surfaces and implement modal/recovery reference patterns |
| `ui/screens/SettingsScreen.kt` | Either remove as primary screen or convert into Help-launched sheet/cards |
| `ui/screens/DeveloperModeScreen.kt` | Compact controls visually and keep amber Dev isolation |

## Final Recommendation

Do not mark the app UI as reference-identical yet.

The current implementation has the correct brand palette and many of the right components, but the reference is a complete product shell and UX system. To make the app identical, prioritize:

1. bottom nav information architecture;
2. shared shell for onboarding/setup/main screens;
3. background blob geometry;
4. exact glass card rendering;
5. button borders/shadows;
6. 24dp screen rhythm;
7. Inter typography;
8. screen-by-screen screenshot parity.

Once those are implemented, run a fresh emulator screenshot pass and compare it directly against `docs/prototypes/assets/`. That should be the acceptance gate for "perfect branding/theme/UX parity."
