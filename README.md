# BG App Runner

An Android app that runs other apps inside itself. You pick an APK, it installs into a virtual
container, and you launch it from there. A foreground service plus a wake lock tries to keep it
running while the screen is off or you are in another app.

It is built on the open-source [NewBlackbox](https://github.com/ALEX5402/NewBlackbox) virtual engine
(Apache-2.0). This repo only holds the overlay: a launcher screen (`RunnerActivity`), the keep-alive
service (`KeepAliveService`), and a script that patches them into the engine at build time. GitHub
Actions fetches the engine, applies the overlay, and builds the APK.

## Get the APK

1. Create a new empty repository on GitHub (any name).
2. Push this folder to it:
   ```
   git init -b main
   git add .
   git commit -m "BG App Runner"
   git remote add origin https://github.com/<you>/<repo>.git
   git push -u origin main
   ```
3. Open the **Actions** tab. The "Build APK" workflow starts on push (about 10 to 20 minutes the
   first time). If it does not, open it and press **Run workflow**.
4. When it finishes, open the **Releases** page of the repo and download the APK there. (The same files are also attached to the run as the `bg-app-runner-apk` artifact.)
5. Install the APK that matches your phone: `arm64-v8a` for almost every phone made after 2017.
   `universal` also works, and `armeabi-v7a` is for old 32-bit phones.

The APK is debug-signed, so you may need to allow "install unknown apps" for your browser or file
manager.

**Updating.** Every build after build 35 is signed with the same key (`signing/debug.keystore`), so a
newer APK installs over the older one and keeps everything inside the container (apps, Google services,
sign-ins). Builds 35 and older were each signed with a new random key; going from one of those to a
newer build needs one uninstall first, which erases the container once. The key is a plain debug key and,
because this repository is public, so is the key. If you want it private, make your own keystore
(alias `androiddebugkey`, store and key password `android`), save it as a repository secret named
`DEBUG_KEYSTORE_B64` (base64 of the file), and the workflow uses that instead. Every build is checked at
the end of the workflow against the key it should carry.

## Use it

**Look:** calm liquid glass. A deep blue backdrop with soft lights that drift, swell and slowly change
shade (mostly blue, with violet, teal and a touch of rose), flowing contour lines, faint rings, tiny
floating lights and a grid of dots that creeps along, with frosted glass panels on top. The same
backdrop is behind the BlackBox manager screens. It moves all the time while the screen shows it, at
about 30 frames per second from a few cached gradients, whatever the phone's animation or battery
saver settings say. It only stops while the screen is off or another app is in front.

The main screen is laid out top to bottom:

- **BlackBox manager** (the big card at the top): the engine's own manager. Install apps from your
  phone or a file, create home-screen shortcuts, clear app data, set a fake location, Google
  services and settings. Its screens use the same colours as the main screen.
- **Your apps**: everything installed in the container. Tap a row (or **Launch**) to start an app;
  the three dots stop it or remove it (removing asks first, because it deletes the app's data).
  **+ Add APK** installs a single APK file.
- **Keep running in the background**, **Sound**, **Allow on this phone**, **Opening files**,
  **Google services**, **Help**: the settings, grouped by purpose. The two phone settings the app can
  read (battery use, notifications) show **Allowed / Not allowed** and **On / Blocked**.
- Messages appear in a bar at the bottom of the screen and go away by themselves.

Getting started:

1. Open **BG App Runner** and tap **Open BlackBox manager** (or **+ Add APK**) to add an app.
2. Tap **Launch** next to the app that appears.
3. Turn on **Keep apps running** and allow the notification.
4. Tap **Allow unrestricted battery use** and approve the dialog.
5. Open **Autostart in background** and **Battery saver** (or **App info**) and allow background
   activity if your phone has that option (many brands hide it under different names).

## Floating button

While a virtual app is on screen, a small round **BG** button floats on top. Drag it anywhere. Tap it
to keep the app running in the background and go to the home screen. Hold it to hide it until the
next time an app opens. It needs "Display over other apps" permission: tap **Floating button** in
the app once to allow it.

While keep-alive is on, the app also binds to the processes the virtual apps run in with
high importance, which raises their priority so the phone is less likely to stop them.

**Stay active in background** (on by default, experimental): after you tap BG, the virtual app is
not told that its screen went away. It never gets its pause/stop callbacks, so it keeps believing
it is on screen and does not reload when you come back. Android still removes the window surface
of a hidden app, so a game that draws every frame may stall its picture while hidden, even though
the app itself keeps running. Turn the switch off if an app misbehaves.

**Mute app sound in background** (on by default): after you tap BG, the virtual app's own audio
players are set to volume 0 and restored when you come back, so only this app's media player is
left. It covers sound played through Android's normal players (MediaPlayer, ExoPlayer, SoundPool,
Java AudioTrack) and, through a small native hook on the system's AudioTrack, most game audio
(OpenSL ES and the older AAudio path). Low-latency AAudio streams that bypass AudioTrack may still
be heard. The debug log shows `BgMute: native audio hooks installed: N of 5` when it is active. The native
part is only put into a game when this switch is on at the moment the game starts, so if a game
closes by itself, turn the switch off, stop the game (three dots, Stop app) and start it again.

## Links and Google services

Web links (`http` and `https`) that a virtual app opens go to the phone's own browser.

**Open with:** when you open a file from another app (a PDF from your file manager, say), the
list now includes **Open in BG App Runner**. It lists every app in the container, with the ones that
say they support that kind of file first, and sends the file to the one you pick, even if it does
not declare support for it. It never refuses a file. Android only lets an
app list itself once, so every container app is behind that one entry rather than listed by its
own name. The file is copied into this app first, so edits are saved to the copy, not the original.
Turn it off with the **Show in the "Open with" menu** switch.

**Sign-in links that return to an app** (for example Minecraft's Microsoft sign-in, which opens your
browser and then comes back through an `ms-xal-...://auth` address): Android only knows this
app, not the apps inside it, so a small screen in this app catches that address and passes it to
the container app. The build registers it for Minecraft's address. Other apps that sign in through
the browser need their own address added to the manifest in `scripts/apply_overlay.py`.

When the browser sends you back, the answer goes to the screen of the app that is waiting for it,
the way Android would deliver it, and only then is the app brought to the front. If the real
Minecraft is also installed, Android asks which one should open the link: pick **BG App Runner**
("Continue in BG App Runner"). **Always** is fine if you only sign in inside the container, but then
the real Minecraft's own sign-in links also come here, so **Just once** is the safer choice. The
browser page you signed in on can still be open on top of the game after returning; close it
(back or X) and the game is behind it.

**Google Play services in the container** copies the phone's Google Play services, Play Store and
Play Games into the container (the same switch the full BlackBox manager has). The phone must
have them installed. Apps then find Google services as on a normal phone. Features that check the
app's original package name and signing key, such as Google sign-in or Play Games for some apps,
can still refuse to work because the app is not running as itself.

**Signing in to Google** (Play Store, or any app that asks for a Google account): install Play
services and Play Store into the same space as the app, then tap Sign in. The engine's account
service never recorded which space an account belongs to, so every account looked like it was in
space 0 and sign-in from any other space died with `Not allowed to bind to service ...`
(`GoogleAccountAuthenticatorService`) and closed the app that asked. `apply_overlay.py` now sets the
space on every lookup, rebuilds the list of sign-in services when an app is installed (it used to
keep only the newest app's), and turns a refused connection into a normal "sign-in failed"
answer. The steps after that (Google's own sign-in screens, device registration, tokens) have not
been seen working yet. The log lines to look for carry the tag `BlackBoxAccounts`.

A **Sign in** button sits next to every space that has Google services turned on (Settings > GMS
Manager) and in the "Google services" card of the main screen. It sends the same "add a Google
account" request Android's Settings sends (`GoogleSignIn.java`), opens the sign-in screen Google
returns inside the container, and opens Play Store once an account was added.

Three more fixes went in after the button was reported to do nothing:

- The screen Google returns was opened through the app's splash screen, which quits silently when
  the intent names no package (Google names its screen by class only). The button now starts it
  directly, checks first that the screen exists in that space, and says so if it does not.
- If Google Play services give no first answer, the button no longer waits forever (see "The
  sign-in box" below for what it does now).
- The account service treated an account with no per-app visibility as hidden from every app, so
  the lookups that filter by visibility (the ones Google services and sign-in pickers use) found
  no accounts even after a successful sign-in. Such an account is now visible, as on Android.

The log (tag `BlackBoxAccounts`) now shows each step: sign-in service found, connected, asked,
answered (entry names only), screen opened, or the error.

**The sign-in box (build 38).** Sign in used to show one line, "Opening Google sign-in...", and then
nothing at all when Google Play services never answered, so there was no way to tell where it
stopped. Tapping Sign in now opens a box with the live step and the seconds counted ("Starting
Google Play services...", "Waiting for Google's sign-in screen..."), and a Cancel button. The step
comes from the container's own `BlackBoxAccounts` log lines, so it follows what the account service
really did. The engine now also keeps the reason a connection to Google's sign-in service failed (no
sign-in service in that space, Android refused, Android declined) instead of the one word "bind
failure", and logs when the service is still not connected after 15 seconds.

- If it stops, the box says which step it stopped at and shows a report you can copy (button
  **Copy details**): phone and Android version, the Google Play services version in that space,
  whether Play Store is there, the steps with their times, the container's sign-in log lines, and the
  last three crashes logged inside the container. The report holds no account name.
- After 25 seconds without an answer, or at once when Google's service reports a problem, the app
  also tries to open one of Google Play services' own sign-in screens directly (it looks the screen
  up in that space; which sign-in screens exist is written into the report), and watches for the new
  account. If Google's service answers late, its screen is opened as well.
- Once a sign-in screen is open the box says so and offers **Show report**, for the case where the
  screen never appears or closes at once.
- It gives up after 60 seconds. The GitHub build numbers can skip a number, because a failed run
  also uses one.

**Looking inside Google's side of the sign-in (build 39).** The build 38 report showed that the
container reaches Google Play services' sign-in service, sends it the request, and then hears
nothing, with no error either. Android's account service talks to that service with one-way calls,
and a one-way call has no answer, so an error inside Google's service (for example a refused
permission) is thrown away and looks exactly like silence. Build 39 looks into that:

- A small watcher (`AuthenticatorTap`) now sits in front of Google's sign-in service inside the
  container. It writes down in the log (lines that start with `authenticator tap:`) that the request
  arrived in Google's process, whether it ended with an error (the error and where it came from), and,
  when it is still running after 3 seconds, where its thread is stuck. If the request ends with an
  error, the error is passed on as the answer, so the sign-in box names the reason at once instead
  of waiting a minute. Everything else is passed through unchanged. It only watches Google Play
  services' own sign-in services.
- The request now carries who is asking (user id and process id), as Android's own account service
  adds it, and says it comes from Google Play services itself. Before, it named this app, which is not a
  package of the container, and left the ids out.
- The report now also holds Google Play services' own log lines from right after the request (its
  errors and warnings and the lines that mention accounts, permissions or callers), the full reason
  of every "Caused by" in a crash (the direct way had crashed with a message the old report cut off),
  and a list of the sign-in screens and sign-in services Google Play services declares.

**The Play Store record (builds after 35).** Google's own client code, which is inside every app that uses
Play services, checks that the Play Store on the phone is the genuine one: it compares its signing
certificate with Google's (log: `requires Google Play Store, but its signature is invalid`). The engine
answered every question about the Play Store (`com.android.vending`) with a record it made up, which
carried no signature, so that check always failed. It now answers with the real record: the copy
installed in that space if there is one, otherwise the phone's own. This is also what Instagram's
"signature is invalid" message came from.

## If apps still stop

Phone battery managers can be aggressive. Try these first: **Autostart in background** and
**Battery saver** (No restrictions) in the app, lock the app in recents, and use the BG button
instead of swiping the app away. On phones without those screens, the buttons open App info.

If that is not enough, turn on **Backup: silent audio mode**. It plays inaudible audio and shows a
media player in the notification shade, so the phone treats the app like a music app. It is off
by default because it uses more battery. The switch shows its live status, and notifications
must be allowed for the player to appear.

## Privacy

The engine this app is built on contains a built-in "send logs" feature: it uploads the app's whole
log, with device details, to a server run by the engine's author, and does so automatically after a
crash. This build switches it off (`apply_overlay.py` blanks the chat id it needs) and removes the
**Send Logs** entry from the manager's settings. **Copy debug log** only copies to your clipboard.

## What to expect

- On phones where the app runs in a second user (Xiaomi Second space, work profile, "user 10"),
  apps used to crash on launch with `requires INTERACT_ACROSS_USERS`. The build patches the engine
  so guests see the real user. The debug log shows `androidUser=` so you can tell which user you are in.

- The container runs apps inside this app's processes, so any phone's battery manager can still
  stop them. Steps 3 to 5 improve the odds a lot, but nothing here can guarantee it.
- Only one complete `.apk` file works. Split bundles (`.apks`, `.xapk`, `.apkm`) do not, and the base
  APK taken from a Play Store split install installs but shows a blank screen. The app warns you
  when it detects either case.
- If an app opens to a blank or black screen, or closes by itself, tap **Copy debug log**, then paste
  it to get the real error. The report holds: the settings, how each container process ended (Android's
  own record: exit reason, signal, and for a native crash the call stack and the last log lines),
  the crash reports the container saved (the engine hides the error message of an app that dies from
  an uncaught exception; `BlackBoxCrash` in the log carries it from now on), the apps inside the
  container with their files (APK, libraries, game data and OBB), and the recent log.
- Apps that depend on Google Play services, root or integrity checks, banking apps, and some games
  may refuse to run or crash.
- The container keeps its own copy of the phone's storage (`Android/data`, `Download`, ...). The
  folders an app expects there (its own `Android/data/<app>/files`, `Android/obb/<app>`, `Download`,
  `DCIM`, ...) are created before it starts.
  Files the real app saved on the phone, such as downloaded game data, are not visible inside the
  container, so a game that needs them has to download them again in there.
- **GTA San Andreas closing right after its loading bar** was not (only) the missing folders. The
  crash was in the game's own file code (`OS_FileWrite`, null file handle) because the game had been
  given the phone's real folder `/storage/emulated/10/Android/data/com.rockstargames.gtasa/files`,
  which Android does not let it write to, instead of the container's. An app learns its folders from
  Android's list of storage volumes, and the container swaps that list for its own folder. The swap
  started with a check "ask for user 0's volumes", which Android refuses when the app runs in any other
  user (here user 10), so the swap was skipped. `BStorageManagerService` now asks for the user the app
  really runs in, and the app process repeats the swap itself (`ExternalVolumes`) if the container's
  service gives no answer. The log line `ExternalVolumes: storage volumes now point at the container
  folder` (once per app start) shows that it happened.
- The engine says it supports Android 5 to 14+. Newer versions may have problems. If the build
  breaks after the engine changes, pin it: run the workflow manually and set `engine_ref` to a
  commit that worked.
- Android 15 and later limit what foreground services can start from the background. The keep-alive
  service is started while the app is open, which is allowed.

## Files

| Path | What it is |
| --- | --- |
| `overlay/app/src/main/java/.../runner/RunnerActivity.java` | Main screen: BlackBox manager card, app list, grouped settings |
| `overlay/app/src/main/java/.../runner/Glass*.java` | The glass look: live backdrop, frosted panel, switch, colours |
| `overlay/app/src/main/java/.../view/base/BaseActivity.kt` | Puts the live backdrop behind every manager screen |
| `overlay/app/src/main/res/` | Colours, theme and layouts that restyle the BlackBox manager screens |
| `overlay/Bcore/src/main/java/.../fake/service/ICredentialManagerProxy.java` | Answers Android's Credential Manager (Sign in with Google picker, passkeys) with "no saved logins" inside the container; without it Android refuses with a SecurityException and Instagram closes ~10 s after it starts |
| `overlay/Bcore/src/main/java/.../fake/frameworks/GoogleSignIn.java` | Starts "add a Google account" for one space, opens Google's sign-in screen in the container, follows the steps in the log, tries the direct way and builds the report |
| `overlay/Bcore/src/main/java/.../fake/frameworks/AuthenticatorTap.java` | Sits in front of Google Play services' sign-in service inside the container: logs the request arriving, the error it ends with, and where it is stuck; passes the error on as the answer |
| `overlay/app/src/main/java/.../view/gms/GoogleSignInDialogs.java` | The boxes around a sign-in: progress, "screen is open", and the reason with a report to copy |
| `overlay/Bcore/src/main/java/.../core/env/ExternalFolders.java` | Creates the folders an app expects on "phone storage" inside the container before it starts |
| `overlay/Bcore/src/main/java/.../core/env/ExternalVolumes.java` | Points the storage volumes Android reports to an app at the container's own folder |
| `overlay/Bcore/src/main/java/.../core/system/os/BStorageManagerService.java` | The container's storage service (copy of the engine's file without the check that failed outside user 0) |
| `signing/debug.keystore` | The fixed debug signing key, so updates install over older builds (`apply_overlay.py` copies it into the app module and names it in the app's signing config) |
| `overlay/app/src/main/java/.../view/gms/` | GMS Manager list with the Sign in button (copies of the engine's two files, changed) |
| `overlay/app/src/main/java/.../runner/DebugReport.java` | Pieces of the debug report: process exit reasons, saved crash reports, apps and files inside the container |
| `overlay/app/src/main/java/.../runner/Tombstone.java` | Reads the native crash record Android keeps for a process (signal, call stack, last log lines) |
| `overlay/app/src/main/java/.../runner/KeepAliveService.java` | Foreground service with a partial wake lock |
| `overlay/Bcore/.../StayActive.java` | Engine-side hook behind "Stay active in background" and the Java side of the mute |
| `overlay/Bcore/src/main/cpp/BgMute/BgMute.cpp` | Native AudioTrack hook that mutes game audio in the background |
| `scripts/apply_overlay.py` | Copies the overlay into the engine and patches its manifest and sources |
| `.github/workflows/build-apk.yml` | Builds the APK on GitHub Actions |
