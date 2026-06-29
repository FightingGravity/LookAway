# LookAway Privacy Notes

LookAway is built to run locally on the user's device. It does not include analytics, telemetry, ads, cloud sync, or a remote control channel.

## Sensitive Permissions

- Accessibility: used to observe window/package changes and send tap gestures configured by the user. LookAway does not request window content access and does not collect text from other apps.
- Screen capture: used only while ADAM's eye is open so OpenCV can match the current screen against saved target images. Frames are processed in memory and are not uploaded by LookAway.
- Display over other apps: used to show ADAM's floating eye and target tools.
- VPN: used by SAM to route only user-selected app traffic into a local VPN interface where packets are dropped on-device.
- Notifications and foreground services: used so Android can show when ADAM or SAM is active. SAM can be disabled from its notification; ADAM shows a re-enable action only when screen capture needs to be granted again.
- Vibration: used for the optional Ad Complete Vibrate signal when ADAM closes automatically.

## Local Data

LookAway stores settings, selected app lists, trigger rules, and saved target images on the device. Debug logs may appear in local Android logcat during development, but the app does not upload them.

Use LookAway responsibly and in accordance with any agreements that apply to the apps or services you use.
