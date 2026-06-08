# LookAway
![lookaway_banner.png](app/src/main/res/drawable/lookaway_banner.png)

An intelligent, lightweight Android automation utility with a dual-engine architecture designed to defeat repetitive ads. LookAway can actively monitor your screen to automatically skip ads, or passively kill an app's internet connection entirely so ads never load in the first place.

## 🚀 Dual-Engine Features

### Engine 1: Active Scanning (Computer Vision)
* **Intelligent Target Tracking:** Powered by OpenCV, LookAway uses dynamic template matching to scan your screen in real-time to find and "tap" 'Skip', 'X', or 'Next' buttons.
* **On-the-Fly Acquisition:** Easily capture and crop new skip buttons directly from your screen using the built-in targeting reticle.
* **Floating Widget:** A sleek, non-intrusive floating control panel that dynamically resizes and lets you toggle the active scanner from anywhere.
* **Custom Scan Regions (ROI):** Conserve battery and CPU by telling the engine to only scan the top, bottom, or multiple specific regions of your screen.
* **Adjustable Accuracy:** Fine-tune the match-confidence threshold to prevent false positives.

### Engine 2: Passive Shield (Per-App Airplane Mode)
* **Total Silence Null-Routing:** Uses Android's VpnService to create a local "black hole." Any app added to your blocklist has its network packets instantly dropped, forcing it into a permanent, battery-efficient offline state.
* **Split-Tunneling Isolation:** LookAway only blocks the apps you choose. The rest of your phone (messages, browsers, other games) stays fully connected to the internet.
* **Zero External Servers:** The VPN operates 100% locally on your device. Your traffic is never routed to a third-party server or external DNS.

## 🛠️ Installation
Because LookAway utilizes advanced system permissions to actively monitor your screen, click on your behalf, and route traffic, it is not available on the Google Play Store.

1. Go to the [Releases](../../releases) page.
2. Download the latest `app-release.apk`.
3. Open the file on your Android device and select **Install** (you may need to allow "Install from Unknown Sources" in your browser/file manager settings).

## ⚙️ Required Permissions
Upon launching the app, you will need to grant four core permissions for LookAway to function safely and effectively:
1. **Display Over Other Apps:** Allows the floating widget and target acquisition crosshairs to hover over your screen.
2. **Screen Capture API:** Allows the OpenCV engine to take temporary, localized frame snapshots to find your saved targets.
3. **Accessibility Service:** The core of the active automation. This allows LookAway to physically "tap" the screen when it finds a visual match. *(Note: You will be redirected to your device's Accessibility menu to manually toggle LookAway "On").*
4. **VPN Configuration:** Required for the Passive Shield. Allows LookAway to create the local tunnel used to drop traffic for your restricted apps. 

## ☕ Support the Developer
LookAway is completely free and open-source. If this app has saved you time (and sanity) by defeating ads for you, consider dropping a tip in the jar!

[![ko-fi](https://ko-fi.com/img/githubbutton_sm.svg)](https://ko-fi.com/fightinggravity)

## 💻 Tech Stack
* Java / Android SDK
* OpenCV (Open Source Computer Vision Library)
* MediaProjection API
* AccessibilityService API
* VpnService API
