# 🧩 rimixyz Morphe Patches

All-in-one personal collection of patches for [Morphe](https://morphe.software) — **Made in Brazil** 🇧🇷.

## ❓ About

This is a clean, all-in-one repository for my personal Morphe patches across multiple Android apps.

> [!NOTE]
> **100% AI-crafted & tested before release:** Every patch in this repository is 100% created with AI, and is tested until it works properly before being released.

### How to use these patches

Click the button below, or manually add this repository URL to Morphe Manager: `https://github.com/rimixyz/morphe-patches`

<a href="https://morphe.software/add-source?github=rimixyz/morphe-patches">
  <img src=".github/assets/add-to-morphe.svg" alt="Add to Morphe" height="48">
</a>

## 🩹 Patches list

<!-- PATCHES_START EXPANDED -->
> **[v1.6.5](https://github.com/rimixyz/morphe-patches/releases/tag/v1.6.5)**&nbsp;&nbsp;•&nbsp;&nbsp;`main`&nbsp;&nbsp;•&nbsp;&nbsp;23 patches total
<details open>
<summary>📦 New Star Soccer&nbsp;&nbsp;•&nbsp;&nbsp;11 patches</summary>
<br>

**Package name:** `com.newstargames.newstarsoccer`

**🎯 Supported versions:**

| 4.34 |
| :---: |

| 💊&nbsp;Patch | 📜&nbsp;Description | ⚙️&nbsp;Options |
|----------|----------------|-----------|
| [Convert IAP items to Star Bux](#convert-iap-items-to-star-bux) | Converts real-money boots, Super NRG drinks, and Pro/Expert agents and trainers into Star Bux purchases in both modern and classic UI, and replaces the dead Buy Bux redirect with an in-game insufficient-funds banner. |  |
| [Disable Google Play Games](#disable-google-play-games) | Disables broken Google Play Games auto-sign-in on re-signed APKs and hides the dead Play Games button on the Career screen (modern and classic UI). |  |
| [Disable ads](#disable-ads) | Blocks AdMob and DT Exchange/Fyber interstitial ads, removes the built-in promo interstitial, and hides the promotional "Games we like" button while keeping opt-in rewarded videos. |  |
| [Disable analytics and tracking](#disable-analytics-and-tracking) | Disables Firebase Analytics, Flurry analytics and startup measurement, and removes Advertising ID permissions. |  |
| [Disable remote config and cloud overrides](#disable-remote-config-and-cloud-overrides) | Freezes the in-game Cloud/DLC/Cohort override system so remote updates and cached DLC files can never overwrite local patched assets or TweakValues, and speeds up startup. |  |
| [Disable retention notifications](#disable-retention-notifications) | Prevents the game from scheduling local "come back and play" reminder notifications whenever it goes to the background. |  |
| [Enhanced replays and MP4/GIF export](#enhanced-replays-and-mp4-gif-export) | Adds a Replay Studio button during goal replays to customize playback speed, loop, slow-mo overlays, all-chance replays, and export clean HUD-free MP4 videos or animated GIFs. |  |
| [Fix text input backspace](#fix-text-input-backspace) | Fixes backspace when typing names in the game by using the in-game keyboard, which has a working delete button. |  |
| [Retro season selector](#retro-season-selector) | Prompts to choose a historical club database (2011–2026) bundled in the APK whenever you start a new career. Existing careers are unaffected. |  |
| [Save backup and restore](#save-backup-and-restore) | Adds a Backup button to the Career screen (modern and classic UI) to export and restore your full save via the system file picker, with automatic rollback protection. |  |
| [Unlock Pro features](#unlock-pro-features) | Unlocks the Pro license, ad removal, pitches/weather and special balls by setting the local license flags the game checks. |  |

</details>

<details open>
<summary>📦 MyTherapy&nbsp;&nbsp;•&nbsp;&nbsp;5 patches</summary>
<br>

**Package name:** `eu.smartpatient.mytherapy`

**🎯 Supported versions:**

| 4.16.2 |
| :---: |

| 💊&nbsp;Patch | 📜&nbsp;Description | ⚙️&nbsp;Options |
|----------|----------------|-----------|
| [Disable PairIP license check in MyTherapy](#disable-pairip-license-check-in-mytherapy) | Neutralizes the PairIP license check so the re-signed APK can start, without removing its components. |  |
| [Disable all ads](#disable-all-ads) | Blocks all ad formats (banner, native, interstitial and rewarded), stops Mobile Ads/mediation SDK initialization, and skips UMP ad consent prompts. |  |
| [Disable analytics and tracking](#disable-analytics-and-tracking) | Disables Firebase Analytics, AppsFlyer attribution, Sentry diagnostics, internal analytics tracking, and removes Advertising ID permissions. |  |
| [Local backup and restore](#local-backup-and-restore) | Adds a Local Backup & Restore item to Settings to export/restore local .zip backups (with automatic rollback) and import official MyTherapy Archive CSV history. |  |
| [Unlock Plus benefits in MyTherapy](#unlock-plus-benefits-in-mytherapy) | Grants the local Plus state the app reads for ad-free access, extra calendar days and the PDF entry point. |  |

</details>

<details open>
<summary>📦 Wrestling Revolution&nbsp;&nbsp;•&nbsp;&nbsp;3 patches</summary>
<br>

**Package name:** `air.WRCareer`

**🎯 Supported versions:**

| 2.150.64 |
| :---: |

| 💊&nbsp;Patch | 📜&nbsp;Description | ⚙️&nbsp;Options |
|----------|----------------|-----------|
| [Disable Yodo1 analytics](#disable-yodo1-analytics) | Stops the ThinkingData behavioural tracker bundled with Yodo1 MAS (endpoint c1.yodo1.com), including automatic app/screen/click/crash events. |  |
| [Remove ads](#remove-ads) | Suppresses Yodo1 MAS banners, interstitials, rewarded videos and app-open ads, including the preloads done at SDK init. Rewarded flows still grant their in-game reward. |  |
| [Unlock Pro](#unlock-pro) | Grants the Pro license locally by injecting a synthetic purchase into the Distriqt billing events. Unlocks the editor and the full career content. |  |

</details>

<details open>
<summary>📦 17TRACK&nbsp;&nbsp;•&nbsp;&nbsp;3 patches</summary>
<br>

**Package name:** `yqtrack.app`

**🎯 Supported versions:**

| 3.1.6896.1 |
| :---: |

| 💊&nbsp;Patch | 📜&nbsp;Description | ⚙️&nbsp;Options |
|----------|----------------|-----------|
| [Fix Firebase after re-signing](#fix-firebase-after-re-signing) | Restores the original certificate SHA-1 so Firebase Auth (Google login) and push notifications keep working after the APK is re-signed. |  |
| [Force Premium membership](#force-premium-membership) | Rewrites the cached user model to Premium level (4020). Unlocks client-side VIP features and hides ads. Requires a fresh install. |  |
| [Remove Deals ads](#remove-deals-ads) | Hides the 'Deals' tab and campaign ads by forcing the local preference flags to true. |  |

</details>

<details open>
<summary>🌐 Universal&nbsp;&nbsp;•&nbsp;&nbsp;1 patch</summary>
<br>

| 💊&nbsp;Patch | 📜&nbsp;Description | ⚙️&nbsp;Options |
|----------|----------------|-----------|
| [Spoof install source](#spoof-install-source) | Makes the app think it was installed from Google Play. Useful when an app blocks features because it detects a sideloaded install. | • Store to impersonate |

</details>

<!-- PATCHES_END -->

### 🛠️ Building locally

- Requires **Java 21** and GitHub Packages credentials with `read:packages` scope:
  - Set `GITHUB_ACTOR` and `GITHUB_TOKEN` environment variables, **or**
  - Add `gpr.user=<username>` and `gpr.key=<token>` to your user-level `~/.gradle/gradle.properties` (never commit tokens to the repository's `gradle.properties`).
- Run `./gradlew buildAndroid`
- The built patches `.mpp` file is found in `patches/build/libs/patches-*.mpp`
- Patch using [Morphe Desktop](https://github.com/MorpheApp/morphe-desktop) like any other patch bundle.

See the [Morphe documentation](https://github.com/MorpheApp/morphe-documentation) for more information.

## 📜 License

rimixyz Morphe Patches are licensed under the [GNU General Public License v3.0](LICENSE).
