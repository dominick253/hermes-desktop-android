# DOX — Hermes Android WebView shell

Inherits from `~/.hermes/AGENTS.md` and `/home/dom/infra/workspace-context-reference.md`.

## What this is

Framework-only Android shell (Kotlin, no AndroidX, no Compose) that loads the
Hermes dashboard desktop renderer at `http://<dashboard-host>:9119/desktop/` (set per
build through `local.properties` key `hermes.serverUrl`, git-ignored). The
dashboard side (the `/desktop/` mount, the shim, `~/.hermes/desktop-web/`) is
governed by `/home/dom/infra/hermes-dashboard/AGENTS.md`, not by this file.

## Hard rules

* Never commit `hermes-release.keystore`, `keystore.properties`,
  `.keystore_pass`, or `local.properties`. They are git-ignored; keep it that way.
* No dashboard credentials in code, resources, README or comments. The password
  is only ever typed into the login page.
* Do not add AndroidX/Compose dependencies without a stated reason; the zero-dep
  build is deliberate (fast, offline, ~0.75 MB APK).
* Do not "improve" the UI by re-implementing chat screens natively. The renderer
  is the product; only the shell belongs here.

## Build / verify loop

```bash
cd /home/dom/projects/hermes-desktop-android
export ANDROID_HOME=/home/dom/Android/Sdk
./gradlew assembleRelease --console=plain > build.log 2>&1; grep -E '^e: |BUILD ' build.log
adb install -r -g app/build/outputs/apk/release/app-release.apk
adb shell am start -n com.nousresearch.hermesdesktop/.MainActivity
adb exec-out screencap -p > /tmp/shot.png
```

Emulator: AVD `pixel_6_android34` (1080x2400 @ 420 dpi = **411 CSS px wide, the
same layout viewport as the S22 Ultra**, so layout findings transfer). Boot it
visibly:

```bash
DISPLAY=:0 $ANDROID_HOME/emulator/emulator -avd pixel_6_android34 \
  -gpu swiftshader_indirect -no-snapshot -no-audio -no-boot-anim -memory 3072 -cores 4
```

Release builds do not expose WebView DevTools. To inspect the DOM, build the
debug variant (`applicationIdSuffix .debug`, `setWebContentsDebuggingEnabled`)
and use `adb forward tcp:9223 localabstract:webview_devtools_remote_<pid>`.

## Shell pitfalls learned the hard way

* The manifest MUST set `android:theme="@style/Theme.Hermes"`. Without it the
  system default theme adds a grey ActionBar with the app title over the page.
* With `setDecorFitsSystemWindows(false)` the IME no longer resizes the window.
  Read `WindowInsets.Type.ime()` in the inset listener and feed it to the bottom
  padding, or the chat composer hides behind the keyboard.
* WebView content is not in `uiautomator` dumps; drive taps from screenshot
  pixel analysis instead of expecting accessibility nodes.
* `adb shell input text '%20'` types a literal `%20` here. Type without spaces.
* `WebView` has `onPause()`/`onResume()`; `pause()`/`resume()` are not public.
* `WebSettings.setAlgorithmicDarkeningAllowed` is API 33, not 29.
* AGP 8.13.2 needs Gradle 8.13+; the wrapper is pinned to the cached 8.13 dist.
* The launcher icon: the Hermes mark is black line art on white. Adaptive icon =
  white background layer + artwork-only foreground; do not invert it.

## Viewport strategy

The renderer ships `<meta name="viewport" content="width=device-width">`, which
squeezes a three-pane desktop layout into ~411 CSS px. Desktop mode rewrites that
meta to `width=<n>, user-scalable=yes` after page load (`applyViewportMode`).
`setInitialScale` does not work here because the page has a viewport meta.
