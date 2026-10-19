# TACResQ brand assets

## App icon

[`tacresq-app-icon.png`](tacresq-app-icon.png) is the selected 1024 × 1024 launcher-icon master. It uses a location pin, medical cross, and coral locator point on a deep-navy field; the mark contains no lettering so it remains legible when reduced.

The web app uses the 192 × 192 and 512 × 512 exports in `backend/web/public/`, referenced by `manifest.webmanifest`, plus `apple-touch-icon.png`. Standard Android density PNGs are staged in `app/src/main/res/mipmap-*`, with the 512 px drawable in `drawable-nodpi/`.

## Native Android integration note

The repository currently has no `app/src/main/AndroidManifest.xml` or Gradle build files. The launcher image resources are prepared, but cannot yet be verified as a runnable Android launcher icon. When the Android module is restored, set `android:icon="@mipmap/ic_launcher"` and, where supported, `android:roundIcon="@mipmap/ic_launcher_round"` in its application manifest.
