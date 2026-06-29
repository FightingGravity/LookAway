# LookAway

![lookaway_banner.png](app/src/main/res/drawable/lookaway_banner.png)

LookAway is a personal Android utility for making repetitive mobile ad flows less annoying. It gives you two separate tools:

- **ADAM**, short for **Ad Detect and Advance Mode**, watches selected apps for ad flows and taps targets that you personally teach it, such as Next, Skip, and X buttons.
- **SAM**, short for **Selective Airplane Mode**, can block network access for only the apps you choose while the rest of your phone stays online.

LookAway is built for sideloading, transparency, and local control. It is not intended for app-store distribution.

## What LookAway Does

ADAM is the floating eye. When enabled, it can detect ad-related screens in apps you monitor, scan the screen locally, match saved button images, and perform the tap gestures needed to advance or close the ad. You can run ADAM automatically or manually.

SAM is the network switch. It uses Android's local VPN interface to drop traffic for selected apps only. It does not connect to a remote VPN server, and it does not route your traffic anywhere else.

Both tools are optional. You can use ADAM without SAM, SAM without ADAM, both together, or neither until you are ready.

## Why The Permissions Are Needed

LookAway asks for powerful Android permissions because it works across other apps. The app is designed so sensitive work stays on your device.

- **Accessibility Service**: Lets LookAway detect app and ad-window changes, open or close ADAM at the right time, and perform tap gestures that you configure.
- **Screen Capture**: Gives ADAM temporary frames to scan while the eye is open. Frames are processed locally for target matching.
- **Display Over Other Apps**: Shows the floating eye, target reticle, tutorial overlays, and tap feedback.
- **VPN Configuration**: Lets SAM create a local on-device VPN slot so selected app traffic can be blocked without a remote server.
- **Notifications**: Keeps foreground services visible while they are active.
- **Vibration**: Lets ADAM give a small pulse when it automatically finishes looking.
- **Media Volume Control**: Lets ADAM mute and restore media audio while scanning, if you enable that setting.

LookAway does not upload screen frames, target images, accessibility data, or VPN traffic.

## How ADAM Works

ADAM is trained by you. When you find a button it should tap, long-press the floating eye, frame the target with the reticle, and save it. ADAM stores that target on the device and uses OpenCV template matching to find it later.

The normal flow looks like this:

1. You choose which apps ADAM should monitor.
2. ADAM opens its eye when it sees a known ad trigger, or when you open it manually.
3. ADAM scans only while the eye is open.
4. If a saved target is found, ADAM taps it.
5. If an ad redirects to Google Play, Samsung, or a browser, LookAway can return to the ad flow and keep scanning.
6. When LookAway sees the app return to normal gameplay, ADAM closes its eye.

If an ad uses a new screen type, the Trigger Manager lets you decide whether that screen should open ADAM, be ignored, or be reviewed later.

## How SAM Works

SAM is a per-app offline mode. Instead of putting the whole phone into airplane mode, SAM blocks network traffic only for apps you select.

This is done through Android's `VpnService`, but LookAway is not a VPN provider. There is no remote endpoint. SAM uses the local VPN interface as an Android-approved way to filter selected app traffic on the device.

## First Run

LookAway includes an onboarding tutorial for new users. It walks through the permissions, explains what each one is for, helps you choose apps for SAM and ADAM, and includes a target-practice screen so you can teach ADAM its first buttons before trying it in a real app.

The home screen also includes:

- **INFO**: A plain-language explanation of what LookAway does and what its permissions mean.
- **TUTORIAL**: A replay button for onboarding.

## Suggested Setup

Start small:

1. Install LookAway and complete onboarding.
2. Pick one game or app to test first.
3. Enable ADAM for that app.
4. Leave ADAM in Automatic Mode unless you prefer to open the eye yourself.
5. Teach ADAM a few common targets, such as Next, Skip, and X.
6. Use the Trigger Manager if an ad does not open ADAM automatically.
7. Add SAM only for apps where you want selective offline behavior.

You can tune scan area, match sensitivity, widget size, ad-complete vibration, and media mute behavior from Settings.

## Installation

LookAway is distributed for sideloading.

1. Open the repository's **Releases** page.
2. Download the latest APK.
3. Open the APK on your Android device.
4. Allow install from unknown sources if Android asks.
5. Launch LookAway and follow onboarding.

## Building From Source

Open the project in Android Studio, or build from PowerShell:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :app:assembleDebug
```

Install a debug build to a connected Android device:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :app:installDebug
```

## Tech Stack

- Java / Android SDK
- OpenCV
- MediaProjection API
- AccessibilityService
- VpnService
- Foreground services
- BlurView UI effects

## Project Status

LookAway is a personal, open-source Android project. Mobile ads, games, storefront redirects, and Android behavior can change over time, so some ad flows may need new targets or trigger rules.

If LookAway saves you time, the in-app Ko-fi link is available from the home screen.
