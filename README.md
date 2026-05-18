# xob0t Morphe Patches

Personal Morphe patches for Android apps.

## Import

Primary option: open this link on Android to import the source in Morphe:

[Add xob0t Morphe Patches](https://morphe.software/add-source?github=xob0t/morphe-patches)

Manual source URL:

```text
https://github.com/xob0t/morphe-patches
```

## Patches

<!-- PATCHES_START EXPANDED -->
> **[v1.3.2-dev.2](https://github.com/xob0t/morphe-patches/releases/tag/v1.3.2-dev.2)**&nbsp;&nbsp;•&nbsp;&nbsp;`dev`&nbsp;&nbsp;•&nbsp;&nbsp;21 patches total
<details open>
<summary>📦 Avito&nbsp;&nbsp;•&nbsp;&nbsp;4 patches</summary>
<br>

**🎯 Supported versions:**

| all |
| :---: |

| 💊&nbsp;Patch | 📜&nbsp;Description | ⚙️&nbsp;Options |
|----------|----------------|-----------|
| [Avito privacy](#avito-privacy) | Disables Avito first-party clickstream analytics and Avito's direct Adjust telemetry wrapper. |  |
| [Disable update prompts](#disable-update-prompts) | Prevents Avito's force-update screen opener from launching update screens. |  |
| [Hide Avi bottom tab](#hide-avi-bottom-tab) | Removes the Avi assistant button from Avito's bottom navigation bar. |  |
| [Remove ads](#remove-ads) | Disables Avito ads by removing ad SDK entry points and short-circuiting commercial banner loading. |  |

</details>

<details open>
<summary>📦 TBank&nbsp;&nbsp;•&nbsp;&nbsp;2 patches</summary>
<br>

**🎯 Supported versions:**

| 7.34.0 | 🧪&nbsp;all |
| :---: | :---: |

| 💊&nbsp;Patch | 📜&nbsp;Description | ⚙️&nbsp;Options |
|----------|----------------|-----------|
| [Bypass anti-tamper](#bypass-anti-tamper) | Stubs TBank's native RASP executor calls and neutralizes tamper flag reporting. |  |
| [Remove TBank ads](#remove-tbank-ads) | Removes TBank stories and promotional surfaces. |  |

</details>

<details open>
<summary>📦 Ozon&nbsp;&nbsp;•&nbsp;&nbsp;1 patch</summary>
<br>

**🎯 Supported versions:**

| 19.16.0 | 18.37.0 | 🧪&nbsp;all |
| :---: | :---: | :---: |

| 💊&nbsp;Patch | 📜&nbsp;Description | ⚙️&nbsp;Options |
|----------|----------------|-----------|
| [Remove Ozon ads](#remove-ozon-ads) | Removes Ozon ad widgets, banner carousels, video ads, and PDP promo blocks. | • Hide recommendation grids |

</details>

<details open>
<summary>📦 Wildberries&nbsp;&nbsp;•&nbsp;&nbsp;1 patch</summary>
<br>

**🎯 Supported versions:**

| 7.6.1000-rustore | 7.0.6000 | 🧪&nbsp;all |
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
