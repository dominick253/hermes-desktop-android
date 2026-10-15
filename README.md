# Hermes (Android)

A small, fast Android shell that runs the **real Hermes desktop interface** on a phone.

It is not a re-implementation and not a chatbot client. It points a Chromium
WebView at the Hermes dashboard's desktop web renderer
(`http://<your-host>:9119/desktop/`), which serves the byte-identical Electron
renderer bundle. Same sessions, same skills, same memory, same model selector as
the desktop app.

* Target device: Samsung Galaxy S22 Ultra (1440x3088, 515 ppi, Android 13+)
* APK size: **~0.75 MB** (zero third-party dependencies, framework only)
* Min SDK 26, target SDK 35

## Why this design

| Decision | Reason |
| --- | --- |
| WebView wrapper, not a native client | The desktop renderer is the product; a native client would fork its UI |
| No AndroidX / no Compose | Smaller APK, faster cold start, no dependency downloads at build time |
| Point at `:9119/desktop/`, not a separate web UI | One backend, one auth path, one session list |
| Bundle assets stay on the server | They ship with `cache-control: public, max-age=31536000, immutable`, so the WebView caches ~9.5 MB once and later launches are instant |

## Build

```bash
sdkmanager --install "platforms;android-35" "build-tools;35.0.0"   # once
echo "sdk.dir=$HOME/Android/Sdk" > local.properties
./gradlew assembleRelease
# -> app/build/outputs/apk/release/app-release.apk (signed)
```

Release signing reads `keystore.properties` (git-ignored). Generate one with:

```bash
keytool -genkeypair -keystore hermes-release.keystore -alias hermes \
  -keyalg RSA -keysize 2048 -validity 10950 \
  -storepass <pw> -keypass <pw> -dname 'CN=Hermes, OU=Personal, O=You, C=US'
```

Then `keystore.properties` holds `storeFile`, `storePassword`, `keyAlias`,
`keyPassword`. Without it the release APK is unsigned and Android refuses to
install it.

## Sideload

1. Copy `app-release.apk` to the phone.
2. Settings > Apps > Install unknown apps > enable for your file manager.
3. Open the APK and install.
4. Launch, sign in with the same dashboard credentials as the desktop app.

Via adb (USB debugging on):

```bash
adb install -r -g app/build/outputs/apk/release/app-release.apk
```

## Pointing it at your server

The dashboard address is **build-time configuration**, not a secret baked into
the source. Set it with either:

```bash
./gradlew assembleRelease -PserverUrl=http://your-host:9119/desktop/
# or, once, in the git-ignored local.properties:
#   hermes.serverUrl=http://your-host:9119/desktop/
```

Without either, the build falls back to the placeholder `http://192.168.1.100:9119/desktop/`.
You can also change it at any time in the running app: menu (three dots, top
right) > **Server**. Any
`http://`/`https://` host works, including a Tailscale address for off-LAN use.

## In-app menu

| Item | What it does |
| --- | --- |
| Reload | Reloads the current address |
| Layout | Fit screen (phone width) or desktop width, pan and pinch-zoom |
| Wider / narrower desktop view | Cycles 1024 / 1280 / 1440 / 1600 / 1920 CSS px |
| Text size | 85 / 100 / 115 / 130 / 150 % |
| Keep streaming in background | Foreground service + partial wake lock so a turn finishes with the screen off (off by default) |
| Full screen | Hides the status and navigation bars; swipe an edge to reveal them |
| Server | Change the dashboard address |
| Sign out | Clears the session cookie and stored page state |

## Performance notes

* `android:configChanges` covers rotation, dark mode, font scale and density, so
  the WebView, its renderer process, the WebSocket and the asset cache all
  survive those events instead of reloading.
* `WebView.setRendererPriorityPolicy(RENDERER_PRIORITY_BOUND, true)` keeps the
  Chromium renderer alive when the app is backgrounded.
* Session cookies persist for 12 h (access) and 30 d (refresh), so sign-in
  survives app restarts and reinstalls.
* `onRenderProcessGone` rebuilds the view instead of crashing if Android evicts
  the renderer.

## Security

* Cleartext HTTP is permitted (see `res/xml/network_security_config.xml`)
  because the LAN dashboard is plain HTTP. If you front the dashboard with TLS,
  use an `https://` server address; the app accepts it as-is.
* No credentials are stored in the APK or in this repository. Sign-in happens in
  the page and the session lives in the WebView cookie jar.
* The signing keystore and `keystore.properties` are git-ignored and must never
  be committed.

## Verified

Built and driven on an Android 14 emulator at a 411 CSS px viewport (the same
layout width as the S22 Ultra): install, splash, login, full-screen desktop UI,
sending a message and receiving the streamed assistant reply, keyboard handling,
launcher icon. See `AGENTS.md` for the repeatable verification recipe.
