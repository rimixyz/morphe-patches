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
> **[v1.5.1](https://github.com/rimixyz/morphe-patches/releases/tag/v1.5.1)**&nbsp;&nbsp;•&nbsp;&nbsp;`main`&nbsp;&nbsp;•&nbsp;&nbsp;15 patches total
<details open>
<summary>📦 New Star Soccer&nbsp;&nbsp;•&nbsp;&nbsp;8 patches</summary>
<br>

**Package name:** `com.newstargames.newstarsoccer`

**🎯 Supported versions:**

| 4.34 |
| :---: |

| 💊&nbsp;Patch | 📜&nbsp;Description | ⚙️&nbsp;Options |
|----------|----------------|-----------|
| [Disable Firebase Analytics](#disable-firebase-analytics) | Disables Firebase Analytics collection through the SDK's official manifest flag, while keeping push notifications and the consent UI. |  |
| [Disable Flurry analytics session](#disable-flurry-analytics-session) | Prevents the game from starting a Flurry analytics session without removing SDK classes or changing the consent flow. |  |
| [Disable Flurry startup measurement](#disable-flurry-startup-measurement) | Stops the Flurry provider from capturing startup time and memory usage while keeping the component declared. |  |
| [Fix text input backspace](#fix-text-input-backspace) | Fixes backspace when typing names in the game by using the in-game keyboard, which has a working delete button. |  |
| [Remove in-game promo interstitial](#remove-in-game-promo-interstitial) | Suppresses the game's built-in promo interstitial on all three entry paths while preserving navigation and return to play. |  |
| [Remove network interstitial ads](#remove-network-interstitial-ads) | Blocks loading and display of non-rewarded interstitials from AdMob and DT Exchange/Fyber while preserving rewarded videos. |  |
| [Restrict advertising ID](#restrict-advertising-id) | Removes GAID and AdServices Ad ID permissions to reduce ad tracking, while keeping Attribution and Topics. |  |
| [Unlock Pro features](#unlock-pro-features) | Unlocks the Pro license, ad removal, pitches/weather and special balls by setting the local license flags the game checks. |  |

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
