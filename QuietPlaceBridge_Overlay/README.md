# Quiet Place Bridge — experimental Android overlay build

This project now includes an Android floating 🎙 button intended to sit on top of Minecraft Bedrock. Tap it to start/stop the microphone bridge; drag it to move it out of the way.

## Can I build it without a computer?
Possibly, using the GitHub website from your Android tablet and GitHub Actions. This repository includes `.github/workflows/build-apk.yml`, which builds a debug APK in GitHub's cloud and uploads it as a workflow artifact. This is not a ready APK yet.

### Cloud build using only your tablet
1. Sign in to GitHub in your tablet browser and create a **new private repository** (recommended).
2. Download this ZIP, extract it with your tablet's file manager, and upload the *contents* of the `QuietPlaceBridge_Overlay` folder to the repository root. Make sure `.github/workflows/build-apk.yml` is included; some upload interfaces hide dot-folders.
3. Open the repository's **Actions** tab and allow Actions if prompted. Run **Build Quiet Place Bridge APK** using **Run workflow**, or push/commit the files to `main`.
4. When the workflow finishes successfully, open its run, scroll to **Artifacts**, and download `QuietPlaceBridge-debug-apk`. Extract it to get `app-debug.apk`.
5. Install only if you trust the source you uploaded. Android may ask permission to install unknown apps for your browser/files app.

## Important limitations
- This is experimental source and has **not been built or tested on a physical tablet** in this chat. The cloud build may expose compile issues; if it fails, share the error log.
- Android Studio is not required if GitHub Actions successfully builds it, but a GitHub account and internet are required.
- The app uses Shizuku to tap/type the Minecraft chat command. You must separately install and activate Shizuku and grant its permission. This often requires Android wireless debugging and may be awkward on a single tablet.
- Android's microphone privacy indicator and foreground notification should remain visible while listening.
- Grant **Display over other apps** permission for the floating button.
- The floating button is an Android overlay, not a button inside Minecraft's own UI.
- It may open Minecraft chat while sending levels; this is not a smooth direct integration. Chat coordinates (default 45,45) are guesses and must be calibrated.
- The add-on cannot access the microphone directly. Back up your world and test in a copy. No voice is sent to other players; the app measures local mic level.

## Cloud build (no computer needed)

This project is built by GitHub Actions: open the repository on GitHub →
**Actions → "Build Quiet Place Bridge APK" → Run workflow** (a push under
`QuietPlaceBridge_Overlay/` also triggers it). When the run is green, download
the `QuietPlaceBridge-debug-apk` artifact from the run's summary page and
install it on your phone.

The workflow installs JDK 17, the Android 35 SDK and Gradle 8.9, verifies the
`quietplace:mic` protocol inside the .mcaddon still matches the app, runs the
JVM unit tests (`app/src/test/.../MicLevelTest.kt`, which check that the meter
changes with input level and that our chat command is exactly what the add-on
parses), then assembles and uploads the debug APK.

## Changes in this copy (v0.2.0)

- Fixed an Android 12+ crash: `FloatingMicService` was started with
  `startForegroundService()` but never called `startForeground()`. It is now a
  real foreground service (`specialUse` type) with a notification.
- `MicBridgeService` now checks the microphone permission before touching
  `AudioRecord` and enters the foreground with type `microphone`.
- The floating button now shows a live meter (`OFF` / `68%`) and keeps its
  dragged position; it cannot get stuck showing the wrong state.
- Commands are sent from a single queue thread (they used to interleave), a
  `speaking`/`silent` state flip is sent on threshold crossings, and a level
  heartbeat keeps the add-on's 2 s noise hold refreshed while you speak.
- Shizuku permission results are now handled (the old code checked
  `onRequestPermissionsResult`, which Shizuku never calls).
