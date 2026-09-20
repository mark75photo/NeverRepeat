# NeverRepeat Android build handoff

Goal: installable Android APK that opens NeverRepeat directly, while Spotify remains the playback service.

## Current proven web app
The production player is index.html on main. Preserve Remembered Rotation behavior, especially durablePlayed, played/history, queue refill, sync, genre grouping, Discovery Sprinkle, Drive Mode, seek and Spotify playback controls.

## Android architecture
Use Capacitor. App ID: com.markhansen.neverrepeat. App name: NeverRepeat.

Copy the current index.html and manifest.json into www/ for the packaged app. Do not rewrite playback logic unless needed for Android authentication.

## Spotify authentication
The existing browser code derives redirect_uri from location.origin + location.pathname. That will not be suitable inside the packaged app. Implement an Android-safe OAuth PKCE return path using an app link/custom URI supported by Spotify's current developer settings, then route the callback into the Capacitor app. Keep Client ID configurable for this test build and never embed a client secret.

The Spotify developer dashboard must contain the exact redirect URI used by the APK.

## Persistence
Capacitor WebView localStorage should preserve the current nr_* state. Never clear nr_durablePlayed during queue refill, Sync Likes, app restart, background/foreground, or ordinary upgrades. Only clear it after a genuinely completed rotation or the double-confirmed Start New Shuffle Cycle action.

## Build
Create Android project, launcher icon using the existing NeverRepeat/RR branding where assets permit, and GitHub Actions workflow that builds a debug APK and uploads it as an artifact. Verify Gradle build succeeds.

## Acceptance checks
1. Tap NeverRepeat icon -> player opens directly.
2. Spotify authorization returns to NeverRepeat.
3. Sync Liked Songs works.
4. Start playing, background/reopen app, count persists.
5. Queue refill does not reset count.
6. Next/Previous/seek/Drive Mode work.
7. Spotify may be opened as playback service, but user does not need to navigate back to the browser version.
