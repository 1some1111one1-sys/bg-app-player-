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

**The permission Android 15 refused (build 40).** The build 39 report, from an Android 15 phone, named
the cause: Google's sign-in service threw `SecurityException: Access denied, requires:
android.permission.ACCOUNT_MANAGER` as soon as the request reached it. That service only takes requests
from a caller that holds this permission (on a real phone, the system itself) and asks Android whether
the caller does. The engine answers yes to that question, but Android 15 asks it with a second,
device-aware method (`checkPermissionForDevice`) that nothing covered, so the real system said no.
Build 40 fixes it in two layers:

- The engine now answers that second method too, for that one permission, the same way it answers
  the older one. Every other permission is passed on untouched, and Android 14 and older never call the
  new method, so nothing changes on them.
- If the permission is refused anyway (by whatever route), the watcher runs the request once more with
  the system (uid 1000) as the caller, which is who calls that service on a real phone. This applies to
  that one request on its own thread only, and only to a refusal that names this permission and comes
  from Android's own sign-in code, not from Google's. The log says `was refused for
  android.permission.ACCOUNT_MANAGER ... trying again as the system`, and then whether the second try
  worked.
- The report now reads the container's log once more right before it is built, so "Stopped at" is never
  older than the report, finds Google's process from the steps when the attempt ended before the first
  look at the log, and lists the lines of every Google Play services process that is running or crashed
  (the one that takes the request and the one that shows Google's sign-in screens).
- For Android 10 to 12: every activity a container app starts passes through the engine, which read
  two of this app's own yes/no markers from the intent's extras. On Android 12 and older, reading the
  extras of an intent that holds a class the engine does not know (an app's own data class, or
  Google's) fails or drops the extras after it. The markers are now read from a copy of the intent,
  and the marker on Google's sign-in screen is a category instead of an extra. Android 13 and newer
  read such extras only when they are used, so they were not affected. Not tested on a phone with
  Android 10 to 12.

**The app-restrictions refusal (build 41).** The build 40 report, from the same Android 15 phone, showed
that the permission step now passes (`permission check for ACCOUNT_MANAGER (device-aware): granted`)
and that Google's sign-in service then stopped at its next step. It asks Android for the "app
restrictions" of Google Play services (rules a work profile or school account can set; a normal phone
has none). On Android 15 that question reaches the system through a new method,
`getApplicationRestrictionsForUser`, which the engine did not cover (it only covered the older
`getApplicationRestrictions`). The real system only answers it for the system itself or for the
asking app's own package, so it refused (`Only system may: get application restrictions for other
user/app com.google.android.gms`) and Google's service answered the request with `addAccount failed`.
Build 41:

- covers the new method the same way as the old one: the question is asked for the phone's own package
  and user, which the system does answer for the app that asks. If the system still refuses, the
  answer is "no restrictions" (an empty answer, what a phone without such rules gives) and the log
  gets one line, `application restrictions: the system refused (...), answering with none`. Android
  14 and older never call the new method, so nothing changes on them.
- picks the lines of Google's processes in the report by importance (errors, crashes and lines that
  name accounts, permissions or callers first), prints them in time order, and says how many less
  important lines were left out. In the build 40 report the section of Google's sign-in screen
  process was filled with harmless stack lines before it reached the real errors. Lines that only
  report the container's own start-up (`nativeloader`, `HookManager`) no longer count as sign-in
  lines at all.

This is the second refusal found this way, and there may be more behind it. Not confirmed on a phone yet.

**Phones where the container runs in a second Android user (build 42).** On a Lava phone (Android 14)
the container app is installed in Android user 10, and there Google's sign-in service never answered,
so "Opening Google sign-in" never ended. The Xiaomi (user 0) did not have this problem. The log of the
Lava shows why:

- Android keeps one copy of an app's code in a process (a `LoadedApk`, which also owns the app's
  `Application`) and finds it again by package name, but only when the app's uid belongs to the same
  Android user as the process. The engine gives a virtual app a plain app id as its uid, which Android
  reads as user 0. In a process of user 10, Android therefore decides "different user", does not keep
  the copy, and builds a new one for every request, with no `Application` in it.
- What that did to Google Play services: its services were created with a context that had no
  `Application`, so its start-up code hit a null pointer (`Context.isDeviceProtectedStorage()` on
  null) and its proxy services logged `Proxy without impl failing onBind()`; the sign-in service never
  came up. Starting one of its screens built a second copy of its `Application`, which its code
  refuses (an exception from the constructor), so the process of its sign-in screens crashed over and
  over.

Build 42 puts that right, only when the user of the process and the user of the app's uid differ:

- `LoadedApkRegistry` files the copy the engine built for the app under its package name in Android's
  own table (`ActivityThread.mPackages`), so services and every later lookup get that one.
- When a screen is launched (Android 13 and newer, and Android 9 to 11; Android 12 already hands the
  code over itself) the launch info carries the app's uid in the process's own user, so the lookup is
  not turned away as "different user" and finds the same copy instead of building a second one.
- On the primary user nothing happens beyond one comparison, so phones like the Xiaomi (user 0) behave
  exactly as before. The log tag `LoadedApkRegistry` says `registered the code of ... for user 10` when
  it acts, and the report lists those lines.
- The direct way no longer asks the container's package service for the whole list of Google's screens
  (on that phone the answer was 4.6 MB, more than one Android call can carry, so it always failed); it
  reads the list from Google's package file instead.

Not confirmed on the Lava yet. Whatever comes after Google's screen opens is still unknown on both phones.

**The sign-in screen that "opened" and never appeared (build 42).** In the build 41 report from the Xiaomi
Google's service answered, the app opened `com.google.android.gms.ui.UNPACKING_REDIRECT`, said so, and
nothing appeared: no crash, and no error in any of Google's processes. Reading the engine shows why
(found in the code; the phone has not confirmed it yet):

- Google names that screen by an action only, with no component. The container's activity manager
  finds such a screen by matching the intent against the intent filters of the installed screens, and
  a filter only matches when it lists every category of the intent.
- Since build 40 the app marks a launch of its own with a category (`bg_runner_from_host`; an extra
  would make Android 12 and older read all of Google's extras, which fails on Google's own classes).
  No filter lists that made-up category, so the match failed, the container found no screen, and it
  dropped the request without a word. Before build 40 the marker was an extra and did not get in the way.

Build 42:

- tells the container which screen to open (the component the app had already found), so nothing has
  to be matched. When the container cannot match a request at all it now writes a warning
  (`startActivityLocked: no screen in space N answers ...`), so it can no longer happen silently.
- records what happens next, because the earlier reports left all of it out: the report describes the
  intent Google returned (the names of its extras only, never their values), notes what the phone
  shows of this app (its tasks and how important each of its processes is) before the launch and
  0.8, 2.5, 5 and 9 seconds after it, and has a new section, "Container launch lines after the sign-in
  screen was opened", with the lines of the container's activity manager and screen start-up from every
  process plus the warnings and errors of the processes that are not Google's (this app's own and the
  account service's), which the report did not show before.
- prints the names of the entries of Google's answer (it printed the identity of a set object).

What the redirect screen does once it starts (it is meant to hand over to Google's own sign-in screen)
is not known yet.

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
| `overlay/Bcore/src/main/java/.../fake/frameworks/AuthenticatorTap.java` | Sits in front of Google Play services' sign-in service inside the container: logs the request arriving, the error it ends with, and where it is stuck; passes the error on as the answer; runs a request again as the system when Android refused it for the account-manager permission |
| `overlay/Bcore/src/main/java/.../fake/service/LoadedApkRegistry.java` | Keeps a virtual app's code (and its Application) in one piece when the container runs in a second Android user, which Android otherwise refuses to cache |
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
| `scripts/apply_overlay.py` | Copies the overlay into the engine and patches its manifest and sources, among them the engine's `IActivityManagerProxy` (device-aware permission check, Android 15), `IUserManagerProxy` (app restrictions for a user, Android 15), `NativeCore` (calling user id for the retry as system), `ActivityStack` (host-start markers), `BActivityThread` and `HCallbackProxy` (app code kept in a second Android user) |
| `.github/workflows/build-apk.yml` | Builds the APK on GitHub Actions |
