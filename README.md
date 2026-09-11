# Screenshot Capture Mobile

Native Android and iOS starter projects for a screen-capture utility.

## What is implemented

- Android: grant overlay and MediaProjection consent once, then a floating cluster of three buttons — capture, return to this app, close — stays on top of every screen. Capture hides the cluster, saves a PNG of the visible display to `Pictures/Screenshot Capture`, and reports the result in a toast.
- Android: the cluster is draggable from any of its buttons and stays inside the screen bounds; the mirrored display is re-created after a rotation so captures keep the current screen size.
- Android: the `STREAM_SYSTEM` volume is recorded, muted only while the screenshot is written, and restored from every completion, cancellation, and failure route.
- iOS: an honest native UI explains the operating-system restriction. Apple does not allow an App Store app to capture another app's screen, enumerate other apps, or set the device's system volume. This is not a missing permission that can be added.

## Important platform boundaries

Android's MediaProjection prompt is mandatory, and the overlay permission must be granted before the floating buttons can appear. The system captures the display, not an arbitrary package: whatever is on screen when the capture button is tapped is what gets saved. Secure content and apps using `FLAG_SECURE` remain unavailable.

The mirrored display only produces a frame when the screen changes, so a completely still screen can need a moment; the capture retries briefly and says so if no frame arrives.

On some devices or regions the camera shutter sound is enforced by the OS. An app cannot reliably override that policy; this project only controls Android's system-sound stream when the device permits it.

## Open the projects

- Android: open `android/` in Android Studio, sync Gradle, and run on Android 10+.
- iOS: open `ios/project.yml` with XcodeGen (`xcodegen generate`), then open the generated project in Xcode. iOS 16+ is targeted.
