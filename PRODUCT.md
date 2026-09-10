# Product

<!-- impeccable:product-schema 1 -->

## Platform

adaptive

## Stack

Native Android (Kotlin + Jetpack Compose) and native iOS (SwiftUI), as requested.

## Users

People who need to save the currently visible screen of a selected Android app without including this utility app.

## Product Purpose

Provide an intentional, user-authorized Android capture flow: choose a launchable app, move to it, capture the visible display, and save the image.

## Capabilities and Constraints

- Android uses the system MediaProjection permission prompt for each capture session.
- Before capture, the app remembers the system-sound stream volume, sets it to zero, and restores it in all completion and error paths.
- Android cannot capture secure windows, and the user must authorize system screen capture.
- iOS/iPadOS sandbox rules do not permit one third-party app to capture another app or programmatically change system volume; the iOS client communicates this limitation rather than implying otherwise.

## Product Principles

- Make consent and limitations explicit.
- Never leave a changed audio setting behind.
- Keep the primary action direct and recoverable.
