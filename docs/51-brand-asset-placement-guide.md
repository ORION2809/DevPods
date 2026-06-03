# DevPods Brand Asset Placement Guide

Date: 2026-06-03
Scope: Placement plan for the current branding artifacts in `assets/`.

## Verdict

Use the square rounded tile as the product identity anchor, the light wordmark for normal documentation and pairing surfaces, the dark wordmark for high-impact launch/hero moments, the teal standalone mark for dark in-app hero art, and the white standalone mark only for monochrome/system surfaces.

The current files are high-resolution raster PNG exports. Treat them as approved visual sources, not final production resource names. Before wiring them into code, copy them into a normalized `assets/brand/` structure and generate platform-specific derivatives.

## Implementation Status

Implemented on 2026-06-03:

- Source assets normalized under `assets/brand/source/`.
- Android launcher/round icons now use DevPods adaptive icon resources instead of the Android system placeholder.
- Android notifications now use branded DevPods small and large icons.
- Android onboarding, setup, and Help surfaces now render the approved wordmark/tile/mark assets.
- Wear launcher icons now use DevPods adaptive icon resources, and the Wear tile now uses standalone DevPods marks for ready and approval states.
- Bridge pairing HTML now renders the DevPods wordmark and favicon.
- Portable Windows packaging now includes a generated DevPods `.ico` plus a shortcut-creation helper for branded desktop/taskbar entry points.
- Proof-report generation now opens with branded DevPods cover/header art.
- Export packs now exist for Play Store, favicon/app touch icon, Windows icon, and social avatar/hero derivatives.
- `README.md` now opens with the approved DevPods wordmark.

Generated derivative set:

- `assets/brand/android/devpods-play-store-512.png`
- `assets/brand/web/favicon-16.png`
- `assets/brand/web/favicon-32.png`
- `assets/brand/web/favicon.ico`
- `assets/brand/web/apple-touch-icon-180.png`
- `assets/brand/windows/devpods-bridge.ico`
- `assets/brand/social/devpods-avatar-512.png`
- `assets/brand/social/devpods-avatar-1024.png`
- `assets/brand/social/devpods-hero-dark-1600x900.png`
- `assets/brand/docs/devpods-proof-report-cover.png`

## Asset Inventory

| Source file | Size | Visual role | Canonical derivative name | Primary job |
| --- | ---: | --- | --- | --- |
| `assets/ChatGPT Image Jun 1, 2026, 02_32_08 PM (1).png` | 1672x941 | Dark horizontal DevPods wordmark with earbud mark | `devpods-wordmark-dark-16x9.png` | Premium hero/banner on dark surfaces |
| `assets/ChatGPT Image Jun 1, 2026, 02_32_09 PM (2).png` | 1254x1254 | Rounded square app tile with teal earbud-terminal mark | `devpods-app-icon-tile.png` | App icon, launcher, avatar, store identity |
| `assets/ChatGPT Image Jun 1, 2026, 02_32_09 PM (3).png` | 1672x941 | Light horizontal DevPods wordmark with earbud mark | `devpods-wordmark-light-16x9.png` | Default wordmark for light UI, docs, bridge pairing |
| `assets/ChatGPT Image Jun 1, 2026, 02_32_09 PM (4).png` | 1254x1254 | White monochrome earbud-terminal mark on black | `devpods-mark-white-mono.png` | Notification, lock-screen, monochrome, ambient surfaces |
| `assets/ChatGPT Image Jun 1, 2026, 02_32_11 PM (5).png` | 1254x1254 | Teal standalone earbud-terminal mark on black | `devpods-mark-teal-dark.png` | Dark in-app illustration and compact brand mark |

## Canonical Asset Folder

Create these copies before implementation:

```text
assets/brand/source/devpods-wordmark-dark-16x9.png
assets/brand/source/devpods-wordmark-light-16x9.png
assets/brand/source/devpods-app-icon-tile.png
assets/brand/source/devpods-mark-teal-dark.png
assets/brand/source/devpods-mark-white-mono.png
assets/brand/android/
assets/brand/web/
assets/brand/docs/
assets/brand/windows/
assets/brand/social/
```

Do not delete the original `assets/ChatGPT Image...png` files until every code reference points to normalized names.

## Placement Matrix

| Product surface | Asset to use | Target location | Notes |
| --- | --- | --- | --- |
| Android launcher icon | `devpods-app-icon-tile.png` | `android-relay/app/src/main/res/mipmap-*dpi/ic_launcher.png`, `mipmap-anydpi-v26/ic_launcher.xml` | Replace `@android:drawable/sym_def_app_icon` in `AndroidManifest.xml`. Generate adaptive and round icons from this asset. |
| Android Play Store icon | `devpods-app-icon-tile.png` | Release/store listing artwork | Export at 512x512. Keep full tile visible inside safe zone. |
| Wear app launcher icon | `devpods-app-icon-tile.png` | `android-relay/wear/src/main/res/mipmap-*dpi/ic_launcher.png` | Match phone app identity exactly. |
| Android notification small icon | `devpods-mark-white-mono.png` | `android-relay/app/src/main/res/drawable/ic_stat_devpods.xml` | Derive a transparent, single-color white vector from the mark. Android small notification icons must be monochrome. |
| Android notification large icon | `devpods-mark-teal-dark.png` | `android-relay/app/src/main/res/drawable-nodpi/devpods_notification_large.png` | Use for expanded foreground, approval, reminder, and soft-ping notifications. |
| Android splash screen | `devpods-app-icon-tile.png` | `android-relay/app/src/main/res/drawable/splash_icon.*`, theme config | Use icon tile centered on `DevPodsColor.Background`; avoid wide wordmark on splash. |
| Android onboarding top identity | `devpods-wordmark-light-16x9.png` | `OnboardingScreen.kt` | Use as a compact top brand image on the current light background, or keep text title and use the asset in the hero. |
| Android onboarding hero visual | `devpods-mark-teal-dark.png` | `OnboardingScreen.kt` | Replace the hand-drawn earbud/waveform block with this dark hero image. It matches the current `DevPodsColor.DarkPanel`. |
| Android Home idle/ready card | `devpods-mark-teal-dark.png` | `HomeScreen.kt` | Use as a subtle compact mark when relay is idle/ready. Do not repeat the full wordmark in dense cards. |
| Android Setup wizard intro | `devpods-app-icon-tile.png` | `SetupWizardScreen.kt` | Use at the start of setup and pairing discovery. This makes setup feel product-owned. |
| Android Setup complete state | `devpods-mark-teal-dark.png` | `SetupWizardScreen.kt` | Use the teal mark next to "Setup complete" and degraded setup recovery cards. |
| Android Settings/About section | `devpods-app-icon-tile.png` plus `devpods-wordmark-light-16x9.png` | `SettingsScreen.kt` or future About card | Use tile as avatar-size identity, wordmark only if there is enough horizontal room. |
| Android Help footer | `devpods-wordmark-light-16x9.png` | `HelpScreen.kt` | Use small wordmark at the bottom with app version and bridge version. |
| Wear tile active/approval state | `devpods-mark-white-mono.png` | `DevPodsTileService.kt` | Use white mark for ambient/dark Wear surfaces. Keep approval text/action chips primary. |
| Wear tile normal state | `devpods-mark-teal-dark.png` | `DevPodsTileService.kt` | Use the teal mark when no approval is pending. |
| Bridge pairing page header | `devpods-wordmark-light-16x9.png` | `src/bridge/server.ts` `renderPairingPage()` | Use in the page header above "DevPods Relay Pairing". Keep QR card clean and readable. |
| Bridge pairing page dark mode | `devpods-wordmark-dark-16x9.png` | Future dark stylesheet in `renderPairingPage()` | Use only if the page supports dark mode. |
| Desktop bridge portable folder | `devpods-app-icon-tile.png` | `packaging/windows/` generated `.ico` | Use for shortcut/taskbar identity in the Windows portable bundle. |
| README top banner | `devpods-wordmark-light-16x9.png` | `README.md` or docs landing page | Use light wordmark as the default GitHub-readable banner. |
| Release notes / proof report cover | `devpods-wordmark-dark-16x9.png` | `simulation/android-relay/generate-proof-report.ts` output and release docs | Use as cover/header art, not as inline logo. |
| GitHub repo/social avatar | `devpods-app-icon-tile.png` | Repository/social profile settings | Use square tile. Export 512x512 and 1024x1024. |
| Social/marketing hero | `devpods-wordmark-dark-16x9.png` | Social banners, launch graphics | Best asset for polished brand reveal. Add copy outside the logo safe area. |
| Favicon / pinned tab | `devpods-app-icon-tile.png`, `devpods-mark-white-mono.png` | `assets/brand/web/favicon.*` | Use tile for favicon/app touch icon; mono mark for mask/pinned tab. |

## Per-Asset Rules

### `devpods-wordmark-dark-16x9.png`

Use for:

- Release splash/hero banners.
- Dark documentation covers.
- Social launch headers.
- Proof report cover pages.
- Dark bridge page only if the full page background is dark.

Do not use for:

- Android launcher icons.
- Notification icons.
- Small toolbar slots.
- QR cards or dense setup panels.

Reason: it already contains a full dark background and a wide wordmark. It needs breathing room and loses clarity when shrunk.

### `devpods-wordmark-light-16x9.png`

Use for:

- README and docs landing headers.
- Bridge pairing page header.
- Android Help/About footer.
- Light website or store listing header art.

Do not use for:

- Dark cards.
- App launcher.
- Notification small icon.

Reason: this is the default readable wordmark on the app's existing warm light background.

### `devpods-app-icon-tile.png`

Use for:

- Android launcher and round launcher icons.
- Wear launcher icon.
- Play Store icon.
- Windows shortcut `.ico`.
- GitHub/social avatar.
- Settings/About product identity tile.

Do not use for:

- Notification small icon.
- Full-width hero background.
- Inline icon buttons.

Reason: this is the strongest identity anchor because it is already square, self-contained, and readable at avatar/app-icon sizes.

### `devpods-mark-teal-dark.png`

Use for:

- Android onboarding hero image.
- Home idle/ready card compact brand mark.
- Wear tile normal state.
- Notification large icon.
- Dark feature cards and dark docs sections.

Do not use for:

- Light backgrounds without a dark container.
- Launcher icon by itself.
- Monochrome system icon slots.

Reason: this asset carries the product symbol without the wordmark, but it depends on its dark field for contrast.

### `devpods-mark-white-mono.png`

Use for:

- Android notification small icon source.
- Lock-screen-safe approval/foreground icons.
- Wear ambient mode.
- Monochrome favicon/mask icon.
- Dark print or single-color watermark.

Do not use for:

- Primary marketing identity.
- Light backgrounds.
- App store primary icon.

Reason: this is the system-safe mark. It is not expressive enough to be the main brand asset, but it is exactly the right source for monochrome OS surfaces.

## Android Implementation Notes

Current state: `android-relay/app/src/main/AndroidManifest.xml` now uses `@mipmap/ic_launcher` and `@mipmap/ic_launcher_round`, backed by DevPods adaptive icon resources. Notification surfaces now use `R.drawable.ic_stat_devpods` and `R.drawable.devpods_notification_large`.

Recommended resource names:

```text
android-relay/app/src/main/res/drawable-nodpi/devpods_wordmark_light.png
android-relay/app/src/main/res/drawable-nodpi/devpods_wordmark_dark.png
android-relay/app/src/main/res/drawable-nodpi/devpods_mark_teal_dark.png
android-relay/app/src/main/res/drawable-nodpi/devpods_notification_large.png
android-relay/app/src/main/res/drawable/ic_stat_devpods.xml
android-relay/app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml
android-relay/app/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml
```

Use optimized WebP or density-specific PNGs where appropriate. Keep the original high-resolution PNGs out of `res/drawable` unless they are intentionally `drawable-nodpi`; otherwise Android can over-scale and bloat the APK.

Wear now mirrors the same icon system with its own adaptive launcher resources under `android-relay/wear/src/main/res/`.

## Bridge Web Implementation Notes

The pairing page is currently generated inline in `src/bridge/server.ts`. For branding:

Current state: the pairing page now embeds the light wordmark and app tile favicon as inline data URIs, keeping the page self-contained and visually aligned with the Android experience.

Continue to enforce:

1. Keep `devpods-wordmark-light-16x9.png` above the page title.
2. Keep the QR code as the visual priority inside the card.
3. Keep the favicon tied to the square tile asset.

Do not place the dark wordmark on the current light pairing page. It will look like a pasted poster instead of a native page header.

## Documentation And Proof Reports

Use the light wordmark at the top of normal docs and the dark wordmark for cover-style PDFs/release reports. Use the app icon tile only when a square avatar is needed. Proof reports should include the wordmark once in the cover/header and avoid repeating it in every evidence section.

Recommended docs targets:

- `README.md`: light wordmark banner.
- `docs/07-implementation-summary.md`: light wordmark in exported/PDF version only.
- `docs/release-matrix.md`: small light wordmark header.
- `simulation/android-relay/generate-proof-report.ts`: dark wordmark cover/header.

Current state: the proof report generator now emits a branded cover/header and uses the dark cover derivative from `assets/brand/docs/devpods-proof-report-cover.png`.

## Brand Usage Rules

- Keep one primary brand asset per screen. Do not stack wordmark, icon tile, and standalone mark together.
- Use full wordmarks only at header/cover scale.
- Use the square tile for identity and navigation across platforms.
- Use the teal standalone mark for dark hero illustration.
- Use the white standalone mark only for monochrome/system surfaces.
- Do not crop the earbud mark.
- Do not recolor the logo manually.
- Do not place the dark-logo PNG on light cards.
- Do not place the light-logo PNG on dark cards.
- Maintain clear space around wordmarks equal to at least the height of the earbud mark.
- Minimum digital sizes: full wordmark 160px wide, square tile 32px, standalone mark 24px.

## Rollout Order

1. Normalize asset names under `assets/brand/source/`.
2. Generate Android launcher, notification, and splash derivatives from the square and mono assets.
3. Replace the Android placeholder launcher icon in `AndroidManifest.xml`.
4. Add onboarding hero and Setup wizard brand imagery.
5. Brand the bridge pairing page.
6. Add README/docs/proof-report headers.
7. Generate Windows `.ico`, web favicon, app touch icon, and social avatar exports.
8. Keep future surfaces drawing from the generated derivative pack instead of the raw `ChatGPT Image...png` exports.

## Acceptance Checklist

- Android launcher no longer uses `@android:drawable/sym_def_app_icon`.
- Notification small icon is a monochrome transparent icon derived from `devpods-mark-white-mono.png`.
- Onboarding uses exactly one full logo/hero brand moment and now includes the approved light wordmark.
- Pairing page shows the light wordmark without reducing QR readability.
- Wear tile uses standalone marks, not the full wordmark.
- Docs and reports use the wordmark consistently.
- Every generated derivative can be traced back to one of the five source assets above.
