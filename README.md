# LookAway
![lookaway_banner.png](app/src/main/res/drawable/lookaway_banner.png)

An intelligent, lightweight Android automation utility that actively monitors your screen to detect and skip repetitive ads, so you don't have to.

## 🚀 Features
* **Intelligent Computer Vision:** Powered by OpenCV, LookAway uses dynamic template matching to scan for specific targets on your screen in real-time.
* **On-the-Fly Acquisition:** Easily capture and crop new skip buttons directly from your screen using the built-in targeting reticle.
* **Floating Widget:** A sleek, non-intrusive floating control panel that dynamically resizes and lets you toggle the engine from anywhere.
* **Custom Scan Regions (ROI):** Conserve battery and CPU by telling the engine to only scan the top, bottom, or multiple specific regions of your screen.
* **Adjustable Accuracy:** Fine-tune the match-confidence threshold to prevent false positives.

## 🛠️ Installation
Because LookAway requires advanced system permissions to actively monitor your screen and click on your behalf, it is not available on the Google Play Store.

1. Go to the [Releases](../../releases) page.
2. Download the latest `app-release.apk`.
3. Open the file on your Android device and select **Install** (you may need to allow "Install from Unknown Sources" in your browser/file manager settings).

## ⚙️ Required Permissions
Upon launching the app, you will need to grant three core permissions for LookAway to function safely and effectively:
1. **Display Over Other Apps:** Allows the floating widget and target acquisition crosshairs to hover over your screen.
2. **Screen Capture API:** Allows the OpenCV engine to take temporary, localized frame snapshots to find your saved targets.
3. **Accessibility Service:** The core of the automation. This allows LookAway to physically "tap" the screen when it finds a match. *(Note: You will be redirected to your device's Accessibility menu to manually toggle LookAway "On").*

## ☕ Support the Developer
LookAway is completely free. If this app has saved you time (and sanity) by watching ads for you, consider dropping a tip in the jar!

[![ko-fi](https://ko-fi.com/img/githubbutton_sm.svg)](https://ko-fi.com/fightinggravity)

## 💻 Tech Stack
* Java / Android SDK
* OpenCV (Open Source Computer Vision Library)
* MediaProjection API
* AccessibilityService API