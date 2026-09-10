# Screenshot Capture Mobile

Native Android and iOS starter projects for a screen-capture utility.

## What is implemented

- Android: choose any launchable app, request the platform's MediaProjection consent, open that app, capture the visible display, and save a PNG to `Pictures/Screenshot Capture`.
- Android: the `STREAM_SYSTEM` volume is recorded, muted only while the screenshot is written, and restored from every completion, cancellation, and failure route.
- iOS: an honest native UI explains the operating-system restriction. Apple does not allow an App Store app to capture another app's screen, enumerate other apps, or set the device's system volume. This is not a missing permission that can be added.

## Important platform boundaries

Android's MediaProjection prompt is mandatory. The system captures the display, not an arbitrary package directly: this app opens the selected package first, then captures after it has moved to the foreground. Secure content and apps using `FLAG_SECURE` remain unavailable.

On some devices or regions the camera shutter sound is enforced by the OS. An app cannot reliably override that policy; this project only controls Android's system-sound stream when the device permits it.

## Open the projects

- Android: open `android/` in Android Studio, sync Gradle, and run on Android 10+.
- iOS: open `ios/project.yml` with XcodeGen (`xcodegen generate`), then open the generated project in Xcode. iOS 16+ is targeted.
