# Design

## Product surface

An operating tool for a high-consequence, permission-gated capture task. The interface prioritizes legibility, clear system boundaries, and one unambiguous next action.

## Android

- Material 3 structure: top app bar, full-width action buttons, and a native alert dialog for the launchable-app picker.
- The selected target is visible before the capture action becomes enabled.
- System permission is never represented as a faux confirmation: Android's MediaProjection dialog is the consent surface.
- Status language names recovery: select an app or grant permission.

## iOS

- SwiftUI `NavigationStack` and grouped `List` provide a native explanatory settings-like screen.
- Semantic system colors, SF Symbols, and Dynamic Type defaults are retained.
- The unavailable cross-app capture capability is stated directly, with system-supported alternatives.
