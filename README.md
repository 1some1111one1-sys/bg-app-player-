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

**Google's sign-in screen crashed asking Android who the user is (build 43).** The build 42 report from
the Lava showed that the two fixes of build 42 worked as far as they went. Google's service answered,
the app opened the redirect screen, the container started it (a screen of this app and a process for it
appeared, 0.8 s later) and it handed on to Google's real sign-in screen, `AccountIntroActivity`. That
screen then died within a second, again and again (14 crashes in 13 seconds), every time with:

`SecurityException: You either need MANAGE_USERS, CREATE_USERS, or QUERY_USERS permission to: query user`

thrown from `IUserManager.getUserInfo`. The same process also logged a caught
`SecurityException ... to: check getProfileType` just before. The reason (read from the report and the
engine's code; not confirmed on a phone yet):

- Google Play services is a system app on a real phone and holds permissions an ordinary app does not.
  Its sign-in screen asks Android's user service "who is this user" before it shows anything. Inside the
  container the question comes from this app, which holds none of those permissions, so Android refuses.
- The engine's stub in front of that service (`IUserManagerProxy`) only had four answers of its own and
  passed everything else on, refusals included, straight into Google's code, which does not expect one.
- `getProfileType` was asked about user 0 by a process that runs in user 10; Android only answers that
  across users. The Xiaomi (user 0) would meet the first refusal as soon as its screen starts.

Build 43 (`UserServiceAnswers`, called from a new `invoke` in `IUserManagerProxy`):

- the stub still asks Android first. When Android refuses a **question** (a method called `get...`,
  `is...`, `has...`, `can...`) with a SecurityException, the container answers it instead, the way a
  phone nobody manages does: `getUserInfo` gives an ordinary full user (the owner, administrator, not
  restricted, not a guest, not a work profile) with the id that was asked for, `getProfileType` says
  "full user", `isUserUnlocked` says yes, `hasUserRestriction`, `isManagedProfile` and the like say no,
  `getProfileIds` and `getProfiles` name only the user itself, a count or time is 0, a list is empty.
- a refusal is remembered per question, so Android is not asked again for the same thing. The log gets
  one line per method: `user service: the system refused getUserInfo (...), the container answers it
  itself`, which the report lists with the sign-in steps.
- a **change** (`set...`, `create...`, `remove...`) that Android refuses stays refused, and every other
  kind of failure is passed on unchanged. Nothing changes for a call Android answers, or outside the
  container's own processes. On Android 10 to 15 the user record is built the same way (the flags that
  Android 11 added are not used, because the record's constructor refuses some of them); if a phone's
  constructor still refuses, plainer flags are tried and, last, no record is given.
- the crash part of the report leaves out Android's plumbing frames (`Parcel`, binder and reflection
  proxies, the engine's call stub) and keeps nine frames after each "Caused by" instead of six. In the
  build 42 report all six went to those frames, so the frames of Google's own code that asked were never
  shown.

Whatever Google's sign-in screen does next (it loads a web page with the sign-in form) is not known yet.

**Play Store and Play services closing, and "problem communicating with Google servers" (build 44).** The
build 43 report from the Xiaomi (Android 15) shows what happens, and this part is confirmed by the log:
Google Play Store died a few seconds after it started, over and over (seven processes in 20 seconds),
with `Fatal signal 5 (SIGTRAP)` in a thread called `CronetInit`, and Google Play services died in the
same thread with a Java crash (three times). Cronet is Chrome's network library, which both apps use for everything
they send, so the part of Play services that talks to Google was dead when the sign-in screen said "checking
info", and then "problem communicating with Google servers". The line that starts it, every time:

`SecurityException: Requires one of the following permissions: android.permission.ACCESS_DOWNLOAD_MANAGER,
android.permission.NETWORK_STACK, android.permission.MAINLINE_NETWORK_STACK` thrown from
`ConnectivityManager.getNetworkInfoForUid`.

The reason (read from the report and Android's code; not confirmed on a phone yet): when Cronet starts it asks
Android about each network with `getNetworkInfo(network)`, and that hands Android the app's own uid. Inside the
container a virtual app is told that its uid is a made-up one (10003 for Play Store, 10004 for Play services),
but to Android all virtual apps are this one app with this app's real uid. Android 14 and 15 only answer a
question about some other uid to an app with the download manager's or the network stack's permissions, so the
system threw into Cronet's start-up, and Cronet aborts the whole process on an exception there. GTA and
Instagram do not use Cronet this way, which is why they were fine.

The same report shows a second problem that is probably why Play Store could not sign in even when it stayed
up: its first questions to Google's services provider (the one that holds the device id) were all refused with
`Calling uid: 10493 doesn't match source uid: 10003`, and the next lines are `Unexpected android-id = 0` and
`Unable to fetch checkin consistency token`. Since Android 12 every call to a content provider carries who is
asking (an `AttributionSource`: a uid and a package name) and the provider's process compares that uid with the
real caller's. The engine tried to correct it with field names that do not exist on Android 12 and newer, so
it corrected nothing, and it answered the refusal with an empty result. (The same report shows Play Store's
start-up stopping half way after that and parts of the app failing later with `Cannot cast ... to ...`; that is
expected to be a consequence, not a separate fault.)

And on the Lava (user 10) Google Play services crashed right after it started because it broadcast to Android
user 0, which Android refuses; the engine's handler for that refusal ran the call twice and then let the app
crash with it.

Build 44:

- `NetworkInfoForUid` (a new hook for `getNetworkInfoForUid` in `IConnectivityManagerProxy`): when the uid in
  the call is the virtual app's own made-up one, Android is asked with the real uid, which is what an ordinary
  app does. If Android still refuses, the app is told there is a connected Wi-Fi network instead of getting the
  exception. A question about any other uid is passed on as it is. Log: `BlackBoxNetwork: getNetworkInfoForUid:
  asking Android as uid ... instead of the virtual app's uid ...`.
- `RealProviderSource` (called at the top of `ContentProviderStub.invoke`): on Android 12 and newer a call to a
  provider of the real system (Google's providers) gets its `AttributionSource` set to this app's real uid and
  package name, with the helper (`ContextCompat.fixAttributionSourceState`) that the wrapper around the
  settings, media and telephony providers already used. Providers of virtual apps are not touched. Android 11
  and older have no attribution source and are not touched. Log: `BlackBoxProvider: calls to providers of the
  real system now carry this app's uid and package name`.
- `CallingGuest` (new per-call caller lookup in `NativeCore`, with `getBUidByPid` added at the end of the
  container's activity-service interface): the receiving process of a call from one virtual app to another used
  the app that happened to start that process as the caller of every call, so Play services (started by Play
  Store) saw its own other processes as Play Store and refused them (`Package com.google.android.gms does not
  belong to uid=10003`). It now asks the container's service which virtual app runs the calling process (by the
  real process id Android reports) and remembers the answer for a minute. An unknown caller gets the old answer.
  While a binder thread reads the `AttributionSource` of an incoming call (Android 12 and newer), the caller is
  reported as the system, which passes Android's check; the provider of one virtual app was refusing the calls
  of another with `Calling uid: 10003 doesn't match source uid: 10493`. Log: `BlackBoxCaller: calls from other
  virtual apps are now told apart by the caller's process`.
- `ActivityServiceAnswers` (wraps `IActivityManagerProxy.invoke`): a refusal of Android's activity service,
  bare or wrapped in a reflection exception, is answered once with the "nothing happened" value of the type
  the method returns (0, false, null); a wrong-typed answer is replaced the same way. The broadcast hook now
  maps every Android user other than the app's own to the app's own, so the broadcast that Play services sent
  to user 0 from user 10 is no longer refused.

Not fixed yet (seen in the same report, expected to be harmless or later work): Play Store's `addAccount` call is
refused with "Only 0p apps are allowed to call this API" (Google's service identifies the caller by the real
uid), Play Store's install-session questions get package mismatches, and Firebase says the INTERNET permission
is missing. If sign-in still fails after this build, the in-app sign-in report is what shows where.

**Sign-in works on one phone but not the other, and Play Store asks for a sign-in again (build 45).** Two things
were reported after build 44. The sign-in worked on the Lava (Android 14) and ended in "Something went wrong /
problem communicating with Google servers" on the Xiaomi (Android 15), with the data meter at 0 KB/s. So it is not
a fault of Google's servers: the same account and the same Google code behave differently on the two phones,
which points at something Android 15 does differently inside the container. And, on one of the phones, after a
sign-in that worked Play Store still asked to sign in, and a second sign-in said the account was already on the
device (the account was in the container, Play Store just did not get it). This build fixes the causes that the
logs and Android's code support, and adds what is missing to find the rest; none of it is confirmed on a phone yet.

Build 45:

- `DevicePolicyCaller` (an `invoke` added to `IDevicePolicyManagerProxy`): Android 15 asks many questions of its
  device policy service together with the asking app's package name, and refuses with `Caller with uid 10493
  is not com.google.android.gms` when the package does not belong to the caller's uid. Inside the container
  the caller is this app and the package is the virtual app's, so every such question was refused. Android 14
  does not send the package, which is the only difference between the two phones found so far;
  Google Play services' account chooser crashed on it, and the question "is account management turned off",
  which comes before a sign-in, is of the same kind. The package of a virtual app in the arguments is now this
  app's own, and a question (get..., is..., has..., can...) that is still refused is answered with "nothing"
  (empty list, 0, no); a change (set..., add...) is still refused. Log: `device policy: Android refused ...`.
- `AuthenticatorCaller` (used where the account service puts `KEY_CALLER_UID` into the request to Google Play
  services, for a login token and for a new account): the request carried this app's real uid for every virtual
  app, Google looked that uid up in the container, found its own packages instead of Play Store, and refused
  (`Only 0p apps are allowed to call this API`, seen for Play Store's `addAccount` in the build 44 report).
  The request now carries the uid the asking virtual app has inside the container (10003 for Play Store), found
  from the caller's process id. A caller that is no virtual app (this app's own sign-in button) keeps its uid.
  Log: `requests to sign-in services now carry the uid the calling virtual app has inside the container`.
- "All accounts" (an account question without a type, which is what `AccountManager.getAccounts()` asks) was
  answered with nothing by the container's account service, which compared every account's type with null.
  Android answers it with every account, and so does the container now (`getAccountsAsUser` and
  `getAccountsByTypeForPackage`). Without it an app that asks that way saw no account after a successful
  sign-in and asked for one again.
- What the report now shows, to find what is still unknown: the network the app used since the attempt began
  (`SignInDiagnostics`; "nothing at all" means the request never left the phone, a few KB means it did and the
  answer was refused), the number of accounts of each type in the space (counts and types, never names),
  snapshots up to 80 seconds after the screen opened, more lines of Google's screen process (120 and 90 lines
  instead of 90 and 45, with refusals and network errors ranked first), and a line for every launch that leaves
  the container (`LaunchNotes`, written by the container's `startActivity`): the action, the app or screen, the
  address with host, a shortened path and only the names of its parameters (never values), so a sign-in that was
  handed to Chrome can be told from one that failed. Web addresses opened by a virtual app were already sent to
  the phone's browser; this only records it.

Still unknown after this build: where the "Something went wrong" screen is shown (Google's own screen in the
container or the browser) and what it tried to reach; the reports above are meant to tell. Not changed:
the properties of the phone that native Google code sees (a Pixel 6 with Android 12), Play Store's install-session
questions, `hasFeatures`, Firebase's "missing INTERNET permission".

**Play Store says "Authentication is required" (build 46).** After build 45 the Xiaomi's Play Store still opens
with "Authentication is required. You need to sign in to your Google Account" and a "Try again" button. That
screen is what Play Store shows when its request for a login token fails, and from the outside it looks the same
whether Google refused the request, answered with a sign-in screen, or was never asked. No log of it was
available, so the cause is not known. This build fixes one bug of the container that can produce it and adds the
log lines that tell those cases apart; the fix is not confirmed on a phone.

Build 46:

- `invalidateAuthToken` (engine patch in `BAccountManagerService`): the usual way for an app to recover from an
  expired or refused token is to call `invalidateAuthToken` with it and ask again. The container removed the
  token from the account's user data instead of from its saved tokens, so for an authenticator whose tokens the
  container keeps itself, the old token was served again for ever. The token is now removed from the saved tokens
  (the in-memory cache of tokens was already handled). If Google Play services keeps its own tokens (the log line
  says which), this was not the cause.
- "Every account" (`getAccounts` hook added to `IAccountManagerProxy`): `AccountManager.getAccounts()` is its
  own call of Android's account service (`getAccounts(type, package)`), not a `getAccountsAsUser`. The engine
  hooked the typed calls but not this one, so it went to the phone's real account service and an app in the
  container was told about the accounts of the phone itself instead of the ones in the container. Which accounts
  that was depends on the phone: none on a phone without a Google account, the person's own on a phone that has
  one. It is now answered by the container's account service like the others (empty list if that fails, never
  the phone's accounts). This is one place where two phones can differ although the container is the same; it
  is not confirmed that Play Store uses this call.
- `TokenRequestNotes` (called from `getAuthToken` of the container's account service): for each request for a
  login token one line says who asked (the app), for which token type, which uid was sent to Google Play services,
  whether Google keeps its own tokens or the container does, whether a token of that type is saved, and what the
  container holds for the account: no master token or its length, how many data keys and saved tokens (names of
  the keys and token types only), and how long ago the last sign-in was. A second line says what Google answered:
  a token (its length only), a screen to show instead (which screen and action), an error (code and message), or
  nothing usable (the names of the keys). Each kind of line is written the first 3 times and then every 25th time.
  No account name, token or value is written. Log: `token request: app ...` and `token request of ... answered: ...`
  (tag `BlackBoxAccounts`).
- The debug log and the sign-in report now start with the build number (`SignInDiagnostics.BUILD`), the debug
  header lists the account types in the space as counts (`accountTypesInContainer=com.google x1`), and the debug
  log keeps lines that contain `GetToken`, `AuthFailure`, `Only 0p` or `Authentication is required`.

What the new lines are for: no `token request` line after Play Store opens means Play Store never asked the
container (it takes the token another way); a request with `answered: a token` means Google gave a token and the
refusal comes later, from Play Store's own server; `a screen to show instead` or an error says what Google wants.

**The background (builds 47 and 48).** Build 47 replaced the moving coloured shapes with a calm, all-blue
backdrop (git commit `6405634`; its APK is still on the `build-47` release). It was taken back at the owner's
request: build 48 has the background of build 46 again (the files `Glass.java`, `GlassBackground.java` and
`colors.xml` are as they were in build 46, and `BackdropMotion.java` is gone). Nothing else changed in build 48
except the build number shown in the debug log and the sign-in report (`SignInDiagnostics.BUILD`, 48).

**The "Check Play Store login" button (build 49).** After build 46 the Play Store on the Xiaomi still opens
with "Authentication is required", and no log of it has reached the developer. Instead of asking for a log, this
build adds a button, **Check Play Store login** (main screen, Google services section), that tests the login
and writes the result on the screen in plain words, so that a screenshot is enough. It does not repair anything:
it is there to show where the login breaks, so that the next build can fix that. How to use it: open Play Store
in the container, wait for the error, come back to this app and tap the button (it takes up to about a minute).

The check runs these steps and puts each result in the report:

1. The Google apps and the account in the space: whether Google Play services, Play Store and Google Services
   Framework are installed there (with versions), how many Google accounts the container has, which account
   types it holds (counts only) and whether a master token is saved (its length only).
2. A login request as Play Store: the container's account service is asked for the token Play Store asks for
   (`oauth2:https://www.googleapis.com/auth/googleplay`), naming Play Store as the asking app, and the answer
   is awaited for up to 45 seconds. The request carries a private key (`bgRunnerAskAsPackage`) which makes the
   container send Play Store's uid to Google Play services instead of this app's own (`AuthenticatorCaller`).
   It is honoured only for a call that comes from this app's own screens: a virtual app's call keeps the uid it
   has inside the container whatever it passes, and the key is taken out of the options before they go on. If
   the container held a saved Play Store token, it is removed for the test (otherwise the container would answer
   from it and Google would not be asked) and put back if the test fails; a token Google gives is saved like
   any other, which replaces a stale one. The answer is sorted into: a token (its length only; or "fast, saved
   token, Google was not asked"), a screen Google wants to show instead (which one), an error (the code in words
   and the message), no answer in 45 seconds, or the request could not be sent.
3. The connection: the network in use (Wi-Fi, mobile data, VPN, whether Android has validated it, private DNS),
   Data Saver, whether three addresses that a login passes can be reached (`www.google.com/generate_204`,
   `android.googleapis.com/auth`, `android.clients.google.com/`) and how long they take, and the phone's clock
   against the date Google puts on its answer (more than 5 minutes apart is reported, as that breaks secure
   connections).
4. The phone's Google device id, read from the phone's own Google Services Framework
   (`content://com.google.android.gsf.gservices`, `android_id`) as Play Store reads it. Zero, refused or an
   error is reported (an older Xiaomi log showed Play Store with `Unexpected android-id = 0`). The id itself is
   never shown or kept, only whether it was read.
5. This app's own log (every virtual app is part of it): the lines of the container's account service, Play
   Store's warnings and errors about logins, the sign-in warnings of Google Play services, and counts of
   `Unexpected android-id`, `AuthFailureError` / `Authentication is required` and refused callers.
   E-mail addresses, numbers of 12 digits or more and long token-like strings are replaced before a line is kept.

The report opens with the verdict in one to three sentences, then lines marked `!!` for things that look wrong
(a device id of zero, addresses that cannot be reached, a clock that is far off, Data Saver, authentication
failures or refused callers in the log, no Play Store request in the log although the test worked), then the facts, then the log lines kept; **Copy
details** copies all of it, and the same result is written to the log (`Play Store login check started in
space N`, `... finished: <verdict>`, tag `BlackBoxAccounts`). Only counts, lengths and yes/no leave the check:
no account name, token or device id.

Also in build 49: `TokenRequestNotes` wrote the lines about login token requests "the first 3 times, then every
25th", which would have hidden a later attempt of Play Store, so a line is now also written when more than 10
seconds have passed since the last line of its kind; and the build number shown in the reports is 49.

The check was run only on a computer against stand-ins of the parts of Android it uses, and its real build is
the first time it is compiled against Android; it has not been tried on a phone. Which phone shows which
symptom, and what the check says there, is still not known.

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
| `overlay/Bcore/src/main/java/.../fake/service/UserServiceAnswers.java` | Answers the questions Android's user service refuses a virtual app (who is this user, which profile type, is it restricted) so Google's sign-in screen does not crash on the refusal; used by `IUserManagerProxy` |
| `overlay/Bcore/src/main/java/.../fake/service/NetworkInfoForUid.java` | Asks Android about a network with the app's real uid instead of the virtual app's made-up one, so Cronet (Play Store, Play services) no longer aborts on Android 14 and 15; used by a new hook in `IConnectivityManagerProxy` |
| `overlay/Bcore/src/main/java/.../fake/service/RealProviderSource.java` | Gives the `AttributionSource` of a call to a provider of the real system (Google's) the app's real uid and package name on Android 12 and newer, so Play Store can read the device id; called from `ContentProviderStub` |
| `overlay/Bcore/src/main/java/.../fake/service/CallingGuest.java` | Finds which virtual app made a call from another virtual app (by the caller's real process id) and lets the check of an incoming `AttributionSource` pass; used by `NativeCore` |
| `overlay/Bcore/src/main/java/.../fake/service/ActivityServiceAnswers.java` | Answers a refusal of Android's activity service once, with the default of the method's return type; used by `IActivityManagerProxy` |
| `overlay/Bcore/src/main/java/.../core/system/accounts/AuthenticatorCaller.java` | The uid the account service puts into a request to Google Play services: the one the asking virtual app has inside the container (build 45); for a test request from this app's own screens that names a package (`bgRunnerAskAsPackage`), the app id of that package (build 49) |
| `overlay/Bcore/src/main/java/.../fake/frameworks/PlayLoginCheck.java` | The "Check Play Store login" test: the apps and account in the space, a login request as Play Store, the connection, the phone's Google device id and the log; reports only counts, lengths and yes/no (build 49) |
| `overlay/Bcore/src/main/java/.../fake/frameworks/PlayLoginReport.java` | Turns what the check found into plain sentences, `!!` warnings and the report text (no Android classes, so it is checked on a computer) (build 49) |
| `overlay/Bcore/src/main/java/.../fake/frameworks/PlayLoginLog.java` | Reads this app's own log and sorts the lines about the Play Store login, with e-mail addresses, long numbers and token-like strings removed (build 49) |
| `overlay/app/src/main/java/.../view/gms/PlayLoginDialogs.java` | The boxes of the check: progress, the result (monospace, Copy details, Run again) (build 49) |
| `overlay/Bcore/src/main/java/.../fake/service/DevicePolicyCaller.java` | Gives a device policy question the package Android 15 expects, and answers a refused question with "nothing" (build 45); used by `IDevicePolicyManagerProxy` |
| `overlay/Bcore/src/main/java/.../fake/service/LaunchNotes.java` | Writes a launch that leaves the container into the log without any private value (build 45); used by `ExternalLinks` |
| `overlay/Bcore/src/main/java/.../fake/frameworks/SignInDiagnostics.java` | Network bytes during a sign-in and account counts by type, for the report (build 45); the build number shown in the reports (build 46) |
| `overlay/Bcore/src/main/java/.../core/system/accounts/TokenRequestNotes.java` | The log lines about a request for a login token and what Google answered, without names or values (build 46; a line is also written after 10 quiet seconds, build 49); used by `BAccountManagerService` |
| `overlay/Bcore/src/main/aidl/.../core/system/am/IBActivityManagerService.aidl` | The engine's file plus `getBUidByPid` at the end (which virtual app runs this real process), answered by `BActivityManagerService` |
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
| `scripts/apply_overlay.py` | Copies the overlay into the engine and patches its manifest and sources, among them the engine's `IActivityManagerProxy` (device-aware permission check, Android 15), `IUserManagerProxy` (app restrictions for a user, Android 15; answers to questions Android refuses), `NativeCore` (calling user id for the retry as system; calling virtual app per call; attribution check), `ContentProviderStub` and `IConnectivityManagerProxy` (build 44), `BAccountManagerService` (all accounts; caller uid, also for the build 49 test request; token lines; removal of an invalidated token), `IAccountManagerProxy` (the "every account" call, build 46), `IDevicePolicyManagerProxy` and `ActivityManagerCommonProxy` (build 45), `ActivityStack` (host-start markers), `BActivityThread` and `HCallbackProxy` (app code kept in a second Android user) |
| `.github/workflows/build-apk.yml` | Builds the APK on GitHub Actions |
