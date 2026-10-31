# Design source notes

This is a curated source list, not a collection of third-party screenshots. The concept board is an original TACResQ-specific illustration informed by these public design resources. No third-party screen images are redistributed in this folder.

## 1. Android Developers — Layouts and navigation patterns
Why selected: official guidance for top-level navigation and adaptive layout decisions. It states that a navigation bar can hold three to five same-level destinations and that navigation should adapt for larger windows.

Source: https://developer.android.com/design/ui/mobile/guides/layout-and-content/layout-and-nav-patterns

## 2. Android Developers — Build adaptive navigation
Why selected: official guidance on switching between a bottom navigation bar and a navigation rail based on window size.

Source: https://developer.android.com/develop/adaptive-apps/guides/build-adaptive-navigation

## 3. Google Codelabs — Build adaptive apps with Jetpack Compose
Why selected: practical Compose guidance for responsive window sizes, ergonomics, and testing across device configurations.

Source: https://codelabs.developers.google.com/jetpack-compose-adaptability

## Application to TACResQ

These sources inform the layout rules, not a wholesale copy of another app. TACResQ's emergency map and centered Map destination are product-specific needs. They should be evaluated against usability and actual phone screenshots rather than assumed to be validated by the references alone.

## 4. Material Components — Navigation bar guidance and Jetpack Compose implementation
Why selected: the component guidance states that a bar supports three to five destinations, each represented by icon and label. The Compose `NavigationBarItem` implementation specifically notes that with five destinations, inactive labels may be shown if space permits. This is important nuance: keeping all labels visible is the product's preferred first prototype, not an absolute rule that overrides legibility or platform guidance.

Source: https://github.com/material-components/material-components-android/blob/master/docs/components/BottomNavigation.md
Source: https://android.googlesource.com/platform/frameworks/support/+/refs/heads/androidx-main/compose/material3/material3/src/commonMain/kotlin/androidx/compose/material3/NavigationBar.kt

## 5. Android Developers — Adaptive layout and navigation
Why selected: official guidance recommends choosing navigation patterns based on window size and interaction context instead of using the same bottom bar at every size.

Source: https://developer.android.com/design/ui/mobile/guides/layout-and-content/layout-and-nav-patterns

## Research implication
A good result is not “labels must never be hidden.” It is “labels should not be hidden casually to conceal a layout defect.” Prototype a labeled five-slot baseline, measure text and available width, then compare the standard Material behavior and TACResQ's custom centered Map treatment on actual narrow phones. Select the option that remains clear, balanced, and accessible.
