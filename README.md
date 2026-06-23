# xob0t Morphe Patches

Personal Morphe patches for Android apps.

## Import

1. Install the Morphe app from [morphe.software](https://morphe.software/).
2. Open [this link](https://morphe.software/add-source?github=xob0t/morphe-patches) on Android to add this source.

## Patches

<!-- PATCHES_START EXPANDED -->
> **[v1.6.0](https://github.com/xob0t/morphe-patches/releases/tag/v1.6.0)**&nbsp;&nbsp;•&nbsp;&nbsp;`main`&nbsp;&nbsp;•&nbsp;&nbsp;24 patches total
<details open>
<summary>📦 Avito&nbsp;&nbsp;•&nbsp;&nbsp;7 patches</summary>
<br>

**🎯 Supported versions:**

| 227.0 | 226.5 | 225.5 | 224.6 |
| :---: | :---: | :---: | :---: |

| 💊&nbsp;Patch | 📜&nbsp;Description | ⚙️&nbsp;Options |
|----------|----------------|-----------|
| [AMOLED dark theme](#amoled-dark-theme) | Makes the dark theme fully pure-black (AMOLED): backgrounds and surfaces/cards/bars become #000000. Dark mode only; borders, dividers and branded fills are kept for readability. |  |
| [Block listings](#block-listings) | Hides Avito offers from blacklisted adverts or sellers and adds a blacklist manager (import/export compatible with the Ave Blacklist extension). |  |
| [Disable telemetry](#disable-telemetry) | Disables Avito first-party clickstream analytics and Avito's direct Adjust telemetry wrapper. |  |
| [Disable update prompts](#disable-update-prompts) | Prevents Avito's force-update screen opener from launching update screens. Toggleable in Настройки Morphe. |  |
| [Morphe settings](#morphe-settings) | Adds a "Настройки Morphe" entry to Avito's settings that hosts the configuration for the other Morphe patches. |  |
| [Remove ads](#remove-ads) | Disables Avito ads by removing ad SDK entry points and short-circuiting commercial banner loading. |  |
| [UI tweaks](#ui-tweaks) | Optional interface tweaks, each toggleable in Настройки Morphe: single-row home categories, hide the "Подписки" tab in Избранное, hide installments (Рассрочка) and the "Спросите у продавца" block on offers, expand descriptions by default (no "Читать далее"), and hide the Avi assistant tab in the bottom navigation. |  |

</details>

<details open>
<summary>📦 TBank&nbsp;&nbsp;•&nbsp;&nbsp;2 patches</summary>
<br>

**🎯 Supported versions:**

| 7.36.0 |
| :---: |

| 💊&nbsp;Patch | 📜&nbsp;Description | ⚙️&nbsp;Options |
|----------|----------------|-----------|
| [Bypass anti-tamper](#bypass-anti-tamper) | Stubs TBank's native RASP executor calls and neutralizes tamper flag reporting. |  |
| [Remove TBank ads](#remove-tbank-ads) | Removes TBank stories and promotional surfaces. |  |

</details>

<details open>
<summary>📦 Ozon&nbsp;&nbsp;•&nbsp;&nbsp;1 patch</summary>
<br>

**🎯 Supported versions:**

| 19.22.0 | 19.16.0 | 18.37.0 |
| :---: | :---: | :---: |

| 💊&nbsp;Patch | 📜&nbsp;Description | ⚙️&nbsp;Options |
|----------|----------------|-----------|
| [Remove Ozon ads](#remove-ozon-ads) | Removes Ozon ad widgets, banner carousels, video ads, and PDP promo blocks. | • Hide recommendation grids |

</details>

<details open>
<summary>📦 Wildberries&nbsp;&nbsp;•&nbsp;&nbsp;1 patch</summary>
<br>

**🎯 Supported versions:**

| 7.6.8001 | 7.6.1000-rustore | 7.0.6000 |
| :---: | :---: | :---: |

| 💊&nbsp;Patch | 📜&nbsp;Description | ⚙️&nbsp;Options |
|----------|----------------|-----------|
| [Remove Wildberries ads](#remove-wildberries-ads) | Removes Wildberries home banners, grid banners, promo headers, product recommendations, and lottery popups. | • Hide recommendation grids |

</details>

<details open>
<summary>🌐 Universal&nbsp;&nbsp;•&nbsp;&nbsp;13 patches</summary>
<br>

| 💊&nbsp;Patch | 📜&nbsp;Description | ⚙️&nbsp;Options |
|----------|----------------|-----------|
| [Disable Adjust](#disable-adjust) | Disables Adjust attribution manifest entry points. |  |
| [Disable AppMetrica](#disable-appmetrica) | Disables AppMetrica and legacy Yandex Metrica SDK entry points. |  |
| [Disable AppsFlyer](#disable-appsflyer) | Disables AppsFlyer install referrer and attribution manifest entry points. |  |
| [Disable Firebase telemetry](#disable-firebase-telemetry) | Disables Firebase telemetry collection flags and DataTransport sender entry points. |  |
| [Disable Google Analytics](#disable-google-analytics) | Disables legacy Google Analytics manifest entry points. |  |
| [Disable MyTracker](#disable-mytracker) | Disables MyTracker manifest entry points. |  |
| [Disable RuStore metrics](#disable-rustore-metrics) | Disables RuStore metrics manifest entry points. |  |
| [Disable Sentry telemetry](#disable-sentry-telemetry) | Disables Sentry telemetry by turning off SDK auto-init and clearing the DSN. |  |
| [Disable freeRASP](#disable-freerasp) | Disables the freeRASP mobile security SDK startup. |  |
| [Spoof USB debugging status](#spoof-usb-debugging-status) | Spoofs USB debugging and related developer settings through common Android APIs. |  |
| [Spoof VPN status](#spoof-vpn-status) | Spoofs VPN state through common Android network APIs. |  |
| [Spoof emulator status](#spoof-emulator-status) | Spoofs emulator state through common Build, QEMU file, command, and system property checks. |  |
| [Spoof install source](#spoof-install-source) | Spoofs package installer checks to report Google Play as the install source. |  |

</details>

<!-- PATCHES_END -->

## Usage

Add this repository as a remote patch source in Morphe:

```text
https://github.com/xob0t/morphe-patches
```

Or use the raw bundle metadata URL:

```text
https://raw.githubusercontent.com/xob0t/morphe-patches/main/patches-bundle.json
```

## License

GPLv3. See [LICENSE](LICENSE).
