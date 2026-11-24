package top.niunaijun.blackbox.fake.frameworks;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.accounts.IAccountManagerResponse;
import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.core.GmsCore;
import top.niunaijun.blackbox.core.system.ServiceManager;
import top.niunaijun.blackbox.core.system.accounts.IBAccountManagerService;
import top.niunaijun.blackbox.entity.am.RunningAppProcessInfo;

/**
 * Starts "add a Google account" for one space of the container, the same request Android's own
 * Settings sends. It asks the container's account service to talk to Google Play services of that
 * space, opens the sign-in screen Google returns inside the container, and reports the result.
 *
 * Sign-in has several slow or fragile steps (Google Play services has to start inside the
 * container first), so an attempt keeps the person informed and keeps a record of what happened:
 * a short progress text while it waits, and, when something goes wrong, a report that names the step
 * it stopped at, with the container's own log lines. When Google's service does not answer, the
 * attempt also tries to open Google's sign-in screen directly.
 *
 * Every callback of {@link Listener} runs on the main thread.
 */
public final class GoogleSignIn {
    private static final String TAG = "BlackBoxAccounts";
    private static final String ACCOUNT_TYPE = "com.google";
    private static final String GSF_PKG = "com.google.android.gsf";
    /** Start of the log lines written by {@link AuthenticatorTap}. */
    private static final String TAP_PREFIX = "authenticator tap: ";

    /** Without an answer after this long, Google's sign-in screen is tried directly as well. */
    private static final long DIRECT_AFTER_MS = 25_000;
    /** Without any result after this long, the person gets the report. */
    private static final long GIVE_UP_MS = 60_000;
    /** How long to watch for the account after a sign-in screen was opened. */
    private static final long WATCH_MS = 5 * 60_000;
    /** How long a new account has to be there before the sign-in counts as done. */
    private static final long ACCOUNT_SETTLE_MS = 20_000;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    /** When, after the sign-in screen was opened, to write down what the phone shows of this app. */
    private static final long[] SNAPSHOT_AT_MS = {800, 2500, 5000, 9000, 20_000, 40_000, 80_000};

    /** Sign-in screens of Google Play services, tried one by one when the direct way is used. */
    private static final String[] DIRECT_SCREENS = {
            "com.google.android.gms.auth.uiflows.minutemaid.MinuteMaidActivity",
            "com.google.android.gms.auth.uiflows.addaccount.AccountIntroActivity",
            "com.google.android.gms.auth.uiflows.addaccount.AddAccountActivity",
    };

    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");

    /** ", process com.google.android.gms.persistent (pid 1234)" in the line the account service writes. */
    private static final Pattern PROCESS_PID = Pattern.compile("process \\S+ \\(pid (\\d+)\\)");

    /** The same text with the name of the process captured too (group 1 name, group 2 id). */
    private static final Pattern PROCESS_NAMED_PID = Pattern.compile("process (\\S+) \\(pid (\\d+)\\)");

    /** "Process: com.google.android.gms.ui, PID: 23827" in a crash the log holds. */
    private static final Pattern CRASHED_PROCESS = Pattern.compile("Process: ([^,\\s]+), PID: (\\d+)");

    /** Matches one line of "logcat -v threadtime". */
    private static final Pattern LOG_LINE = Pattern.compile(
            "^(\\d\\d)-(\\d\\d) (\\d\\d):(\\d\\d):(\\d\\d)\\.(\\d{3})\\s+(\\d+)\\s+(\\d+)\\s+([VDIWEF])\\s+"
                    + "(.*?)\\s*:\\s?(.*)$");

    public interface Listener {
        /** A short sentence about what is happening now (it changes as the attempt goes on). */
        void onProgress(String step);

        /** Google's sign-in screen was opened inside the container. */
        void onScreenOpened();

        /** Google finished adding the account. */
        void onSignedIn(String accountName);

        /**
         * Sign-in could not be started, or Google refused it, or nothing happened in time.
         * {@code details} is a report to copy; it never contains the name of an account.
         */
        void onFailed(String message, String details);
    }

    public interface ReportListener {
        void onReport(String report);
    }

    /** Lets the caller cancel an attempt or ask for its report. */
    public static final class Handle {
        private final Attempt attempt;

        private Handle(Attempt attempt) {
            this.attempt = attempt;
        }

        /** The person gave up. No callback is made after this. */
        public void cancel() {
            attempt.cancel();
        }

        /** Builds the report as it stands now and delivers it on the main thread. */
        public void requestReport(final ReportListener listener) {
            Thread thread = new Thread(new Runnable() {
                @Override
                public void run() {
                    final String text = attempt.buildReport("Report asked for while Google's screen was open.");
                    MAIN.post(new Runnable() {
                        @Override
                        public void run() {
                            listener.onReport(text);
                        }
                    });
                }
            }, "BlackBoxGoogleSignInReport");
            thread.setDaemon(true);
            thread.start();
        }
    }

    private GoogleSignIn() {
    }

    public static Handle start(final int userId, final Listener listener) {
        final Attempt attempt = new Attempt(userId, listener);
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                attempt.run();
            }
        }, "BlackBoxGoogleSignIn");
        thread.setDaemon(true);
        thread.start();
        return new Handle(attempt);
    }

    // ------------------------------------------------------------------------------ the steps

    /** How far the sign-in got, as far as the container's own log tells. */
    private enum Stage {
        START(0, "Starting the sign-in...",
                "the container's account service has not reported anything"),
        FOUND(1, "Starting Google Play services...",
                "found Google's sign-in service, waiting for Google Play services to start"),
        WAITING(2, "Starting Google Play services...",
                "asked Android to start Google's sign-in service, it has not connected"),
        CONNECTED(3, "Google Play services started. Asking it for the sign-in screen...",
                "Google's sign-in service connected, the request is about to be sent"),
        ASKED(4, "Waiting for Google's sign-in screen...",
                "Google's sign-in service was asked for the sign-in screen and has not answered"),
        RECEIVED(4, "Google Play services is preparing its sign-in screen...",
                "Google's sign-in service received the request and has not answered"),
        ANSWERED(5, "Opening Google's sign-in screen...",
                "Google's sign-in service answered"),
        NOT_FOUND(10, "Google reported a problem...",
                "the container has no sign-in service for Google accounts in this space"),
        REFUSED(10, "Google reported a problem...",
                "Android refused to connect to Google's sign-in service"),
        DISCONNECTED(10, "Google reported a problem...",
                "Google's sign-in service closed the connection"),
        ERROR(10, "Google reported a problem...",
                "Google's sign-in service reported an error");

        final int rank;
        final String progress;
        final String report;

        Stage(int rank, String progress, String report) {
            this.rank = rank;
            this.progress = progress;
            this.report = report;
        }
    }

    /** One line of the log. */
    private static final class LogLine {
        long key;
        int pid;
        char level;
        String tag;
        String message;
        String raw;
    }

    /** One line of the timeline of an attempt. */
    private static final class TraceEntry {
        final double seconds;
        final String text;

        TraceEntry(double seconds, String text) {
            this.seconds = seconds;
            this.text = text;
        }
    }

    private static final class Attempt {
        final int userId;
        final Listener listener;
        final String id = Long.toString(System.currentTimeMillis() % 100_000_000L, 36);
        final long startedAt = SystemClock.elapsedRealtime();
        final long startedWall = System.currentTimeMillis();

        private final ArrayList<TraceEntry> trace = new ArrayList<>();
        private final StringBuilder facts = new StringBuilder();
        /** The sign-in lines of the container's log seen so far (the log can scroll away). */
        private final ArrayList<LogLine> seenLines = new ArrayList<>();
        private final Set<String> seenRaw = new HashSet<>();

        private boolean done;
        private volatile boolean screenOpened;
        private volatile boolean openedReported;
        private volatile boolean directTried;
        private volatile long openedAt;
        private volatile Stage stage = Stage.START;
        private volatile boolean logReadable = true;
        private volatile boolean markerSeen;
        /** Process id of Google's sign-in service in the container (0 until the log tells). */
        private volatile int authPid;
        /** Set once Google's own side reported a definite error for the request. */
        private volatile String tapError;
        private volatile boolean tapReceivedNoted;
        private volatile boolean tapRefusedNoted;
        private volatile boolean tapReturnedNoted;
        private volatile boolean tapStuckNoted;
        private int polls;
        private int nextSnapshot;
        private String lastProgress = "";
        private int accountsBefore;
        private long accountSeenAt;
        private IBAccountManagerService service;
        /** What the phone's network meter said for this app when the attempt began (see SignInDiagnostics). */
        private volatile long[] trafficAtStart;

        Attempt(int userId, Listener listener) {
            this.userId = userId;
            this.listener = listener;
        }

        // ------------------------------------------------------------------ bookkeeping

        private synchronized boolean isDone() {
            return done;
        }

        /** Marks the attempt as ended. Only the first caller gets true and may report the outcome. */
        private synchronized boolean finish() {
            if (done) {
                return false;
            }
            done = true;
            return true;
        }

        void cancel() {
            if (finish()) {
                note("cancelled by the person");
            }
        }

        private void note(String text) {
            synchronized (trace) {
                trace.add(new TraceEntry((SystemClock.elapsedRealtime() - startedAt) / 1000.0, text));
            }
            Log.i(TAG, "sign-in attempt " + id + ": " + text);
        }

        /** A timeline entry stamped with the time of a log line (when the log was read later). */
        private void noteAt(LogLine line, String text) {
            double seconds = (line.key - keyOf(startedWall)) / 1000.0;
            if (seconds < -2 || seconds > 3600) {
                seconds = (SystemClock.elapsedRealtime() - startedAt) / 1000.0;
            }
            synchronized (trace) {
                trace.add(new TraceEntry(Math.max(0, seconds), text));
            }
        }

        private void fact(String text) {
            synchronized (facts) {
                facts.append(text).append('\n');
            }
        }

        // ------------------------------------------------------------------ the attempt

        void run() {
            try {
                Log.i(TAG, "sign-in attempt " + id + " started in space " + userId);
                note("started");
                progress(0);
                trafficAtStart = SignInDiagnostics.traffic();
                describePhone();

                if (!BlackBoxCore.get().isInstalled(GmsCore.GMS_PKG, userId)) {
                    fail("Google Play services are not turned on for this space yet. "
                            + "Turn them on first, then sign in.");
                    return;
                }
                IBinder binder = BlackBoxCore.get().getService(ServiceManager.ACCOUNT_MANAGER);
                if (binder == null) {
                    fail("The container's account service is not running. "
                            + "Open any app in the container once, then try again.");
                    return;
                }
                service = IBAccountManagerService.Stub.asInterface(binder);
                describeSpace();
                scanGoogleScreens();

                request();
                monitor();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Throwable e) {
                Log.e(TAG, "Google sign-in could not start", e);
                note("could not start: " + e);
                fail("Google sign-in could not start: " + e);
            }
        }

        private void describePhone() {
            fact("Phone: " + Build.MANUFACTURER + " " + Build.MODEL + ", Android " + Build.VERSION.RELEASE
                    + " (SDK " + Build.VERSION.SDK_INT + "), app user "
                    + (android.os.Process.myUid() / 100000) + ", container space " + userId);
        }

        /** What the container itself says about this space, before the request is sent. */
        private void describeSpace() {
            try {
                PackageInfo gms = BlackBoxCore.getBPackageManager().getPackageInfo(GmsCore.GMS_PKG, 0, userId);
                fact("Google Play services in this space: "
                        + (gms == null ? "not found" : gms.versionName + " (code " + gms.versionCode + ")"));
            } catch (Throwable e) {
                fact("Google Play services in this space: could not be read (" + e + ")");
            }
            try {
                fact("Play Store in this space: "
                        + (BlackBoxCore.get().isInstalled(GmsCore.VENDING_PKG, userId) ? "yes" : "no"));
            } catch (Throwable e) {
                fact("Play Store in this space: could not be read");
            }
            try {
                fact("Google Services Framework in this space: "
                        + (BlackBoxCore.get().isInstalled(GSF_PKG, userId) ? "yes" : "no"));
            } catch (Throwable e) {
                fact("Google Services Framework in this space: could not be read");
            }
            try {
                Account[] accounts = service.getAccountsAsUser(ACCOUNT_TYPE, userId);
                accountsBefore = accounts == null ? 0 : accounts.length;
                fact("Google accounts already in this space: " + accountsBefore);
            } catch (Throwable e) {
                fact("Google accounts already in this space: could not be read");
            }
            try {
                // The question Android's account manager asks for "all accounts" has no type. Build 44's
                // container answered it with nothing, so Play Store found no account and asked for a sign-in.
                Account[] every = service.getAccountsAsUser(null, userId);
                fact("All accounts in this space, whatever the type (the question Play Store asks): "
                        + (every == null ? "no answer" : every.length + " (" + SignInDiagnostics.countByType(every) + ")"));
            } catch (Throwable e) {
                fact("All accounts in this space, whatever the type: could not be read (" + e + ")");
            }
        }

        /**
         * Lists the screens and sign-in services Google Play services declares, read from its own
         * package file (the container's package list does not hand out the screens). Runs on its own
         * thread because the package file is large; the result lands in the report's facts.
         */
        private void scanGoogleScreens() {
            Thread thread = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        ApplicationInfo app = BlackBoxCore.getBPackageManager()
                                .getApplicationInfo(GmsCore.GMS_PKG, 0, userId);
                        if (app == null || app.sourceDir == null) {
                            fact("Google Play services package file: not found");
                            return;
                        }
                        PackageInfo info = BlackBoxCore.getContext().getPackageManager().getPackageArchiveInfo(
                                app.sourceDir, PackageManager.GET_ACTIVITIES | PackageManager.GET_SERVICES);
                        if (info == null) {
                            fact("Google Play services package file: could not be read");
                            return;
                        }
                        StringBuilder screens = new StringBuilder();
                        int shownScreens = 0;
                        int allScreens = info.activities == null ? 0 : info.activities.length;
                        if (info.activities != null) {
                            for (ActivityInfo activity : info.activities) {
                                if (activity == null || activity.name == null || shownScreens >= 40) {
                                    continue;
                                }
                                String low = activity.name.toLowerCase(Locale.US);
                                if (low.contains("minutemaid") || low.contains("addaccount")
                                        || low.contains("accountintro") || low.contains("signin")
                                        || low.contains("uiflows") || low.contains("login")) {
                                    screens.append(shownScreens == 0 ? "" : ", ").append(shorten(activity.name));
                                    shownScreens++;
                                }
                            }
                        }
                        StringBuilder services = new StringBuilder();
                        int shownServices = 0;
                        if (info.services != null) {
                            for (ServiceInfo item : info.services) {
                                if (item == null || item.name == null || shownServices >= 12) {
                                    continue;
                                }
                                if (item.name.toLowerCase(Locale.US).contains("authenticator")) {
                                    services.append(shownServices == 0 ? "" : ", ").append(shorten(item.name))
                                            .append(item.processName == null ? "" : " [" + shorten(item.processName) + "]");
                                    shownServices++;
                                }
                            }
                        }
                        fact("Google Play services screens: " + allScreens + " in all; sign-in related: "
                                + (shownScreens == 0 ? "none" : screens.toString()));
                        fact("Google Play services sign-in services: "
                                + (shownServices == 0 ? "none" : services.toString()));
                    } catch (Throwable e) {
                        fact("Google Play services package file: could not be read (" + e + ")");
                    }
                }
            }, "BlackBoxGoogleSignInScan");
            thread.setDaemon(true);
            thread.start();
        }

        private String shorten(String name) {
            return name.startsWith(GmsCore.GMS_PKG + ".") ? name.substring(GmsCore.GMS_PKG.length() + 1) : name;
        }

        private void request() throws Exception {
            Bundle options = new Bundle();
            // The request says it comes from Google Play services itself: this app is not a package
            // of the container, so Google's service could not find it there, while its own package
            // is always present. (Android's account service adds who is asking, see the engine patch.)
            options.putString("androidPackageName", GmsCore.GMS_PKG);

            IAccountManagerResponse response = new IAccountManagerResponse.Stub() {
                @Override
                public void onResult(Bundle value) {
                    handleResult(value);
                }

                @Override
                public void onError(int errorCode, String errorMessage) {
                    handleError(errorCode, errorMessage);
                }
            };
            note("asked the container's account service for a Google sign-in");
            service.addAccount(response, ACCOUNT_TYPE, null, null, true, options, userId);
        }

        /** Waits, keeps the person informed, and gives up (or tries the direct way) when it takes too long. */
        private void monitor() throws InterruptedException {
            long nextPoll = 0;
            long nextAccountCheck = 0;
            while (!isDone()) {
                Thread.sleep(500);
                long elapsed = SystemClock.elapsedRealtime() - startedAt;
                if (isDone()) {
                    return;
                }
                if (!screenOpened) {
                    if (elapsed >= nextPoll) {
                        refreshStage();
                        nextPoll = elapsed + 1500;
                    }
                    progress(elapsed);
                    if (!directTried && elapsed >= DIRECT_AFTER_MS) {
                        note("no answer after " + (elapsed / 1000) + " s");
                        tryDirect();
                    }
                    if (!screenOpened && elapsed >= GIVE_UP_MS) {
                        fail(timeoutMessage(elapsed));
                        return;
                    }
                } else {
                    long sinceOpen = SystemClock.elapsedRealtime() - openedAt;
                    while (nextSnapshot < SNAPSHOT_AT_MS.length && sinceOpen >= SNAPSHOT_AT_MS[nextSnapshot]) {
                        note(String.format(Locale.US, "%.1f s after the screen was opened, %s; %s",
                                SNAPSHOT_AT_MS[nextSnapshot] / 1000.0, describeHostScreens(),
                                SignInDiagnostics.trafficSince(trafficAtStart)));
                        nextSnapshot++;
                    }
                    if (elapsed >= nextAccountCheck) {
                        checkForAccount();
                        nextAccountCheck = elapsed + 2000;
                    }
                    if (SystemClock.elapsedRealtime() - openedAt > WATCH_MS) {
                        note("stopped watching for the new account");
                        return;
                    }
                }
            }
        }

        private String timeoutMessage(long elapsedMs) {
            long seconds = elapsedMs / 1000;
            switch (stage) {
                case START:
                    return "Nothing happened inside the container after " + seconds + " seconds.";
                case FOUND:
                case WAITING:
                    return "Google Play services did not start inside the container (waited "
                            + seconds + " seconds).";
                case CONNECTED:
                case ASKED:
                    return "Google Play services started but never showed its sign-in screen (waited "
                            + seconds + " seconds).";
                case RECEIVED:
                    return "Google Play services got the sign-in request but never showed its sign-in "
                            + "screen (waited " + seconds + " seconds).";
                default:
                    return "Google sign-in did not finish (waited " + seconds + " seconds).";
            }
        }

        // ------------------------------------------------------------------ answers

        private void handleResult(Bundle value) {
            try {
                if (isDone()) {
                    note("an answer arrived after the attempt had ended and was ignored");
                    return;
                }
                if (value == null) {
                    failOrTryDirect("Google sign-in ended without an answer.");
                    return;
                }
                // Only the names of the entries are logged, never their values.
                Log.i(TAG, "Google sign-in from settings, answer holds " + new ArrayList<String>(value.keySet()));

                Intent screen = value.getParcelable(AccountManager.KEY_INTENT);
                if (screen != null) {
                    openScreen(screen);
                    return;
                }
                String account = value.getString(AccountManager.KEY_ACCOUNT_NAME);
                if (account != null) {
                    signedIn(account);
                    return;
                }
                String message = value.getString(AccountManager.KEY_ERROR_MESSAGE);
                failOrTryDirect("Google sign-in did not finish"
                        + (message != null ? " (" + message + ")" : "") + ".");
            } catch (Throwable e) {
                Log.e(TAG, "could not handle the sign-in answer", e);
                note("could not handle the answer: " + e);
                fail("Google sign-in could not continue: " + e);
            }
        }

        private void handleError(int errorCode, String errorMessage) {
            Log.w(TAG, "Google sign-in from settings: error " + errorCode + ": " + errorMessage);
            if (isDone()) {
                return;
            }
            note("the account service reported error " + errorCode + ": " + errorMessage);
            String shown = friendlyError(errorCode, errorMessage);
            if (errorMessage != null && errorMessage.startsWith("Google Play services refused the request")) {
                // Google's own service answered with a definite error: no other way will do better,
                // and the report right now still holds what that service wrote.
                fail(shown);
            } else {
                failOrTryDirect(shown);
            }
        }

        private String friendlyError(int errorCode, String errorMessage) {
            String m = errorMessage == null ? "" : errorMessage;
            if (m.contains("no sign-in service")) {
                return "Google Play services has no sign-in service in this space. Turn Google Play "
                        + "services off and on again, then retry.";
            }
            if (m.contains("invalid intent")) {
                return "Google's sign-in screen is not installed in this space. Turn Google Play "
                        + "services off and on again, then retry.";
            }
            if (m.equals("disconnected")) {
                return "Google Play services stopped while it was starting. It may be crashing at "
                        + "start-up inside the container.";
            }
            if (m.isEmpty()) {
                return "Google sign-in failed (error " + errorCode + ").";
            }
            return "Google sign-in failed (" + m + ").";
        }

        /** A problem on the usual way: the direct way gets a try before the person is told. */
        private void failOrTryDirect(String message) {
            if (screenOpened) {
                note("a problem was reported after the screen was opened: " + message);
                return;
            }
            if (!directTried) {
                note("the usual way did not work (" + message + ")");
                if (tryDirect()) {
                    return;
                }
            }
            fail(message);
        }

        /**
         * Opens the sign-in screen Google returned. This goes straight to the container's activity
         * manager: the splash screen {@code BlackBoxCore.startActivity} normally goes through closes
         * without launching anything when the intent names no package, which is how Google names its
         * screen. Runs on the thread the answer arrived on, never on the main thread.
         */
        private void openScreen(Intent screen) {
            final String target = screen.getComponent() != null
                    ? screen.getComponent().flattenToShortString()
                    : String.valueOf(screen.getAction());
            try {
                ResolveInfo found = BlackBoxCore.getBPackageManager().resolveActivity(screen, 0, null, userId);
                if (found == null || found.activityInfo == null) {
                    Log.w(TAG, "Google's sign-in screen " + target + " is not installed in space " + userId);
                    failOrTryDirect("Google's sign-in screen (" + target
                            + ") is not installed in this space. Turn Google Play services off and on "
                            + "again, then retry.");
                    return;
                }
                Log.i(TAG, "opening Google's sign-in screen " + target + " in space " + userId);
                note("opening the sign-in screen " + target);
                note("the intent Google returned: " + describeIntent(screen));
                if (screen.getComponent() == null) {
                    // Google names its screen by an action only. The container's activity manager finds
                    // the screen again by that action, and it only accepts an intent whose categories
                    // are all ones the screen's filter lists. The marker below is a category this
                    // app made up, so without a component the container no longer found the screen and
                    // dropped the request without a word: the screen "opened" and nothing appeared.
                    screen.setComponent(new ComponentName(found.activityInfo.packageName, found.activityInfo.name));
                    note("the container is told which screen to open: "
                            + screen.getComponent().flattenToShortString());
                }
                screen.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                // Start it from this app (which is on screen) rather than through a running container app.
                // The marker is a category, not an extra: the intent is Google's, and on Android 12 and
                // older adding an extra opens all of its extras, which fails on Google's own classes.
                screen.addCategory("bg_runner_from_host");
                note("before the launch, " + describeHostScreens());
                BlackBoxCore.getBActivityManager().startActivity(screen, userId);
                markOpened();
            } catch (Throwable e) {
                Log.e(TAG, "could not open Google's sign-in screen " + target, e);
                note("could not open the sign-in screen: " + e);
                failOrTryDirect("Could not open Google's sign-in screen: " + e);
            }
        }

        private void markOpened() {
            if (isDone()) {
                return;
            }
            screenOpened = true;
            openedAt = SystemClock.elapsedRealtime();
            if (openedReported) {
                return;
            }
            openedReported = true;
            MAIN.post(new Runnable() {
                @Override
                public void run() {
                    if (!isDone()) {
                        listener.onScreenOpened();
                    }
                }
            });
        }

        private void signedIn(final String account) {
            if (!finish()) {
                return;
            }
            note("signed in");
            Log.i(TAG, "Google sign-in from settings: account added (space " + userId + ")");
            MAIN.post(new Runnable() {
                @Override
                public void run() {
                    listener.onSignedIn(account);
                }
            });
        }

        private void fail(final String message) {
            if (!finish()) {
                return;
            }
            note("failed: " + message);
            final String details = buildReport(message);
            MAIN.post(new Runnable() {
                @Override
                public void run() {
                    listener.onFailed(message, details);
                }
            });
        }

        private void progress(long elapsedMs) {
            String text = (logReadable ? stage.progress : "Working...") + " (" + (elapsedMs / 1000) + " s)";
            if (text.equals(lastProgress)) {
                return;
            }
            lastProgress = text;
            final String shown = text;
            MAIN.post(new Runnable() {
                @Override
                public void run() {
                    if (!isDone() && !screenOpened) {
                        listener.onProgress(shown);
                    }
                }
            });
        }

        /**
         * Looks for the new account while Google's screen is open. Google's own answer (which comes
         * when its whole sign-in flow has ended) is the normal way to learn about it; this is the
         * way when no answer comes, as after the direct way. The account is added before Google's last
         * screens, so it has to be there for a while before the sign-in counts as done.
         */
        private void checkForAccount() {
            try {
                Account[] accounts = service.getAccountsAsUser(ACCOUNT_TYPE, userId);
                if (accounts != null && accounts.length > accountsBefore) {
                    long now = SystemClock.elapsedRealtime();
                    if (accountSeenAt == 0) {
                        accountSeenAt = now;
                        note("a new Google account appeared in this space");
                    } else if (now - accountSeenAt >= ACCOUNT_SETTLE_MS) {
                        signedIn(accounts[accounts.length - 1].name);
                    }
                } else {
                    accountSeenAt = 0;
                }
            } catch (Throwable ignored) {
                // tried again in two seconds
            }
        }

        // ------------------------------------------------------------------ what the phone shows

        /**
         * An intent in a few words, for the report. The values of its extras are never written (they
         * can hold account names), only their names, and the extras are read from a copy: on
         * Android 12 and older reading them can fail on Google's own classes, and must not touch the
         * intent that is about to be started.
         */
        private String describeIntent(Intent intent) {
            StringBuilder out = new StringBuilder();
            try {
                out.append("action ").append(intent.getAction());
                if (intent.getComponent() != null) {
                    out.append(", screen ").append(intent.getComponent().flattenToShortString());
                }
                if (intent.getPackage() != null) {
                    out.append(", package ").append(intent.getPackage());
                }
                out.append(", flags 0x").append(Integer.toHexString(intent.getFlags()));
                if (intent.getCategories() != null) {
                    out.append(", categories ").append(new ArrayList<String>(intent.getCategories()));
                }
                try {
                    Bundle extras = new Intent(intent).getExtras();
                    out.append(", extras ").append(extras == null ? "none" : new ArrayList<String>(extras.keySet()));
                } catch (Throwable e) {
                    out.append(", extras could not be read here (").append(e.getClass().getSimpleName()).append(')');
                }
            } catch (Throwable e) {
                out.append("(could not be described: ").append(e).append(')');
            }
            return out.toString();
        }

        /**
         * What the phone shows of this app: its tasks (the container's screens are tasks of this app
         * whose top screen is one of the engine's stand-in screens) and how important Android finds
         * each of its processes (100 means one of its screens is in front).
         */
        private String describeHostScreens() {
            StringBuilder out = new StringBuilder("the app's screens: ");
            try {
                ActivityManager am = (ActivityManager) BlackBoxCore.getContext()
                        .getSystemService(Context.ACTIVITY_SERVICE);
                List<ActivityManager.AppTask> tasks = am.getAppTasks();
                if (tasks == null || tasks.isEmpty()) {
                    out.append("no tasks");
                } else {
                    int count = 0;
                    for (ActivityManager.AppTask task : tasks) {
                        if (count++ >= 8) {
                            out.append(" ...");
                            break;
                        }
                        try {
                            ActivityManager.RecentTaskInfo info = task.getTaskInfo();
                            if (count > 1) {
                                out.append(", ");
                            }
                            out.append('#').append(fieldOf(info, "id")).append(' ')
                                    .append(componentText(fieldOf(info, "topActivity")))
                                    .append(" x").append(fieldOf(info, "numActivities"));
                        } catch (Throwable e) {
                            out.append("(a task could not be read)");
                        }
                    }
                }
            } catch (Throwable e) {
                out.append("tasks could not be read (").append(e.getClass().getSimpleName()).append(')');
            }
            out.append("; processes: ");
            try {
                ActivityManager am = (ActivityManager) BlackBoxCore.getContext()
                        .getSystemService(Context.ACTIVITY_SERVICE);
                List<ActivityManager.RunningAppProcessInfo> processes = am.getRunningAppProcesses();
                if (processes == null || processes.isEmpty()) {
                    out.append("none listed");
                } else {
                    int count = 0;
                    for (ActivityManager.RunningAppProcessInfo process : processes) {
                        if (count++ >= 10) {
                            out.append(" ...");
                            break;
                        }
                        if (count > 1) {
                            out.append(", ");
                        }
                        out.append(process.processName).append('=').append(process.importance);
                    }
                }
            } catch (Throwable e) {
                out.append("could not be read (").append(e.getClass().getSimpleName()).append(')');
            }
            return out.toString();
        }

        private String componentText(Object value) {
            if (value instanceof ComponentName) {
                return ((ComponentName) value).flattenToShortString();
            }
            return String.valueOf(value);
        }

        private Object fieldOf(Object target, String name) {
            try {
                return target.getClass().getField(name).get(target);
            } catch (Throwable e) {
                return "?";
            }
        }

        // ------------------------------------------------------------------ the direct way

        /**
         * Opens a sign-in screen of Google Play services without asking its sign-in service first.
         * Returns true when one was started.
         */
        private boolean tryDirect() {
            directTried = true;
            ComponentName screen = null;
            try {
                screen = findDirectScreen();
            } catch (Throwable e) {
                note("looking for a sign-in screen failed: " + e);
            }
            if (screen == null) {
                note("no known Google sign-in screen exists in this space, so there is no direct way");
                return false;
            }
            try {
                Intent intent = new Intent();
                intent.setComponent(screen);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                intent.putExtra("bg_runner_from_host", true);
                note("opening Google's sign-in screen directly: " + screen.flattenToShortString());
                Log.i(TAG, "opening Google's sign-in screen directly " + screen.flattenToShortString()
                        + " in space " + userId);
                BlackBoxCore.getBActivityManager().startActivity(intent, userId);
                markOpened();
                return true;
            } catch (Throwable e) {
                note("the direct way failed: " + e);
                return false;
            }
        }

        /**
         * The names of the screens Google Play services declares, read from its package file. The
         * container's package service must not be asked for the whole list: on one phone its answer
         * was 4.6 MB, far over what Android lets one call carry, so the call always failed.
         */
        private List<String> screenNamesFromPackageFile() {
            List<String> names = new ArrayList<>();
            try {
                ApplicationInfo app = BlackBoxCore.getBPackageManager()
                        .getApplicationInfo(GmsCore.GMS_PKG, 0, userId);
                if (app == null || app.sourceDir == null) {
                    note("Google Play services package file: not found");
                    return names;
                }
                PackageInfo info = BlackBoxCore.getContext().getPackageManager()
                        .getPackageArchiveInfo(app.sourceDir, PackageManager.GET_ACTIVITIES);
                if (info != null && info.activities != null) {
                    for (ActivityInfo activity : info.activities) {
                        if (activity != null && activity.name != null) {
                            names.add(activity.name);
                        }
                    }
                }
            } catch (Throwable e) {
                note("the list of Google's screens could not be read: " + e);
            }
            return names;
        }

        private ComponentName findDirectScreen() {
            BPackageManager pm = BlackBoxCore.getBPackageManager();
            List<String> names = screenNamesFromPackageFile();

            // Which sign-in related screens exist, for the report.
            StringBuilder related = new StringBuilder();
            int shown = 0;
            for (String name : names) {
                String low = name.toLowerCase(Locale.US);
                if ((low.contains("minutemaid") || low.contains("addaccount") || low.contains("accountintro")
                        || low.contains("signin")) && shown < 10) {
                    related.append(shown == 0 ? "" : ", ").append(name);
                    shown++;
                }
            }
            note(names.isEmpty()
                    ? "the list of Google's screens is not available"
                    : names.size() + " screens in Google Play services; sign-in related: "
                            + (shown == 0 ? "none" : related.toString()));

            String[] wanted = {"AccountIntro", "MinuteMaid", "AddAccount"};
            for (String want : wanted) {
                for (String name : names) {
                    if (name.contains(want) && name.endsWith("Activity")) {
                        return new ComponentName(GmsCore.GMS_PKG, name);
                    }
                }
            }
            for (String name : DIRECT_SCREENS) {
                ComponentName component = new ComponentName(GmsCore.GMS_PKG, name);
                try {
                    if (pm.getActivityInfo(component, 0, userId) != null) {
                        return component;
                    }
                } catch (Throwable e) {
                    note("asking for " + shorten(name) + " failed: " + e);
                }
            }
            return null;
        }

        // ------------------------------------------------------------------ the log

        /**
         * The lines of a log dump that were written after this attempt began. The attempt wrote a line
         * of its own when it began, so that line is the starting point. When the log has scrolled past
         * it, every line left is newer. Only when that line was never seen does the clock decide.
         */
        private List<LogLine> linesSinceStart(List<String> raw) {
            List<LogLine> all = new ArrayList<>(raw.size());
            int markerAt = -1;
            String marker = "sign-in attempt " + id + " started";
            for (String text : raw) {
                LogLine line = parse(text);
                if (line == null) {
                    continue;
                }
                all.add(line);
                if (TAG.equals(line.tag) && line.message.startsWith(marker)) {
                    markerAt = all.size() - 1;
                }
            }
            if (markerAt >= 0) {
                markerSeen = true;
                return new ArrayList<>(all.subList(markerAt, all.size()));
            }
            if (markerSeen) {
                return all;
            }
            long startKey = keyOf(startedWall - 1500);
            List<LogLine> recent = new ArrayList<>();
            for (LogLine line : all) {
                if (line.key >= startKey) {
                    recent.add(line);
                }
            }
            return recent;
        }

        /** Reads the sign-in lines of the container's log written since this attempt began. */
        private void refreshStage() {
            polls++;
            List<String> raw = readLog(6000, "logcat", "-d", "-v", "threadtime", "-t", "6000",
                    TAG + ":I", "*:S");
            if (raw == null) {
                if (polls == 1) {
                    note("the phone's log could not be read");
                }
                logReadable = false;
                return;
            }
            for (LogLine line : linesSinceStart(raw)) {
                if (!TAG.equals(line.tag)) {
                    continue;
                }
                synchronized (seenLines) {
                    if (!seenRaw.add(line.raw)) {
                        continue;
                    }
                    if (seenLines.size() < 300) {
                        seenLines.add(line);
                    }
                }
                applyLine(line);
            }
            if (!markerSeen && polls >= 4 && logReadable) {
                logReadable = false;
                note("the phone's log does not show this app's own lines, so progress cannot be followed");
            }
        }

        private void applyLine(LogLine line) {
            String m = line.message;
            if (m.startsWith("sign-in attempt ")) {
                // The attempt's own notes are never steps of the container.
                return;
            }
            if (m.startsWith(TAP_PREFIX)) {
                applyTapLine(line, m.substring(TAP_PREFIX.length()));
                return;
            }
            if (m.contains("no sign-in service is installed")) {
                raise(Stage.NOT_FOUND);
            } else if (m.startsWith("sign-in service for ")) {
                raise(Stage.FOUND);
            } else if (m.startsWith("waiting for ")) {
                raise(Stage.WAITING);
            } else if (m.startsWith("cannot connect to ")) {
                raise(Stage.REFUSED);
            } else if (m.startsWith("connected to the sign-in service")) {
                raise(Stage.CONNECTED);
                Matcher where = PROCESS_PID.matcher(m);
                if (where.find() && authPid == 0) {
                    try {
                        authPid = Integer.parseInt(where.group(1));
                    } catch (NumberFormatException ignored) {
                        // the pid stays unknown
                    }
                }
            } else if (m.startsWith("asking the sign-in service")) {
                raise(Stage.ASKED);
            } else if (m.startsWith("the sign-in service answered")) {
                raise(Stage.ANSWERED);
            } else if (m.contains("sign-in service") && m.contains("disconnected")) {
                raise(Stage.DISCONNECTED);
            } else if (m.startsWith("sign-in error")) {
                raise(Stage.ERROR);
            }
        }

        /** A line written by the tap inside Google's sign-in service (see {@link AuthenticatorTap}). */
        private void applyTapLine(LogLine line, String m) {
            if (m.startsWith("connected in process ")) {
                if (authPid == 0) {
                    authPid = line.pid;
                }
                return;
            }
            if (m.startsWith("addAccount received")) {
                authPid = line.pid;
                raise(Stage.RECEIVED);
                if (!tapReceivedNoted) {
                    tapReceivedNoted = true;
                    noteAt(line, "Google Play services received the request");
                }
            } else if (m.startsWith("addAccount returned after ")) {
                if (!tapReturnedNoted) {
                    tapReturnedNoted = true;
                    noteAt(line, "Google Play services finished handling the request without an error, "
                            + "and has not answered");
                }
            } else if (m.startsWith("addAccount FAILED after ")) {
                raise(Stage.ERROR);
                if (tapError == null) {
                    tapError = clip(m, 300);
                    noteAt(line, "Google Play services failed while handling the request");
                }
            } else if (m.startsWith("addAccount was refused for ")) {
                if (!tapRefusedNoted) {
                    tapRefusedNoted = true;
                    noteAt(line, "Google Play services refused the request for the account-manager "
                            + "permission; asked again as the system");
                }
            } else if (m.startsWith("addAccount has not returned after ")) {
                if (!tapStuckNoted) {
                    tapStuckNoted = true;
                    noteAt(line, "Google Play services is still busy with the request after 3 s");
                }
            }
        }

        private void raise(Stage next) {
            if (next.rank >= stage.rank) {
                stage = next;
            }
        }

        // ------------------------------------------------------------------ the report

        String buildReport(String headline) {
            StringBuilder r = new StringBuilder();
            try {
                // The attempt may have ended (or the report been asked for) before the log was looked
                // at, so read it once more now: "stopped at" must not be older than the report.
                try {
                    refreshStage();
                } catch (Throwable ignored) {
                    // the report goes on with what is known
                }
                r.append("BG App Runner, Google sign-in report (build ").append(SignInDiagnostics.BUILD)
                        .append(")\n");
                synchronized (facts) {
                    r.append(facts);
                }
                r.append("Network: ").append(SignInDiagnostics.trafficSince(trafficAtStart)).append('\n');
                r.append("Result: ").append(headline).append('\n');
                String error = tapError;
                r.append("Stopped at: ").append(stage == Stage.ERROR && error != null
                        ? "Google Play services failed while handling the request (" + error + ")"
                        : stage.report).append('\n');
                r.append("Timeline:\n");
                List<TraceEntry> entries;
                synchronized (trace) {
                    entries = new ArrayList<>(trace);
                }
                Collections.sort(entries, new Comparator<TraceEntry>() {
                    @Override
                    public int compare(TraceEntry a, TraceEntry b) {
                        return Double.compare(a.seconds, b.seconds);
                    }
                });
                for (TraceEntry entry : entries) {
                    r.append(String.format(Locale.US, "%5.1f s  %s\n", entry.seconds, entry.text));
                }
                r.append(logSection());
            } catch (Throwable e) {
                r.append("(the report is incomplete: ").append(e).append(")\n");
            }
            // A report is pasted into chats, so no e-mail address may be in it, whatever the log held.
            return EMAIL.matcher(r).replaceAll("<e-mail>");
        }

        /** The sign-in steps, crashes and other telling lines the container logged during the attempt. */
        private String logSection() {
            List<String> raw = readLog(30000, "logcat", "-d", "-v", "threadtime", "-t", "30000");
            if (raw == null) {
                return "Container log: could not be read on this phone.\n";
            }
            List<LogLine> window = linesSinceStart(raw);

            // 1. The sign-in steps (the lines seen while waiting plus any the log still holds).
            List<LogLine> steps;
            Set<String> have;
            synchronized (seenLines) {
                steps = new ArrayList<>(seenLines);
                have = new HashSet<>(seenRaw);
            }
            for (LogLine line : window) {
                if (TAG.equals(line.tag) && have.add(line.raw)) {
                    steps.add(line);
                }
            }
            Collections.sort(steps, new Comparator<LogLine>() {
                @Override
                public int compare(LogLine a, LogLine b) {
                    return Long.compare(a.key, b.key);
                }
            });

            // 2. The last three crashes, with the reason of every "Caused by".
            List<List<String>> crashes = new ArrayList<>();
            for (int i = 0; i < window.size(); i++) {
                LogLine line = window.get(i);
                if (!"AndroidRuntime".equals(line.tag) || !line.message.startsWith("FATAL EXCEPTION")) {
                    continue;
                }
                List<LogLine> block = new ArrayList<>();
                block.add(line);
                for (int j = i + 1; j < window.size() && j < i + 400 && block.size() < 90; j++) {
                    LogLine next = window.get(j);
                    if (next.pid != line.pid || !"AndroidRuntime".equals(next.tag)) {
                        continue;
                    }
                    if (next.message.startsWith("FATAL EXCEPTION")) {
                        break;
                    }
                    block.add(next);
                }
                crashes.add(summarizeCrash(block));
            }

            // 3. Other lines that usually explain a failed sign-in.
            List<LogLine> other = new ArrayList<>();
            Set<String> otherSeen = new HashSet<>();
            for (LogLine line : window) {
                if (TAG.equals(line.tag) || "AndroidRuntime".equals(line.tag)) {
                    continue;
                }
                if (!worthALook(line)) {
                    continue;
                }
                String key = line.tag + "|" + clip(line.message, 70);
                if (otherSeen.add(key) && other.size() < 40) {
                    other.add(line);
                }
            }

            StringBuilder out = new StringBuilder();
            out.append("Container log, sign-in steps:\n");
            if (steps.isEmpty()) {
                out.append("  (none; ").append(logReadable ? "the container wrote nothing"
                        : "the phone's log does not show them").append(")\n");
            }
            int limit = Math.min(steps.size(), 130);
            for (int i = steps.size() - limit; i < steps.size(); i++) {
                out.append("  ").append(clip(steps.get(i).raw, 320)).append('\n');
            }
            out.append(googleSide(window, steps));
            out.append(launchSide(window, steps));
            if (!crashes.isEmpty()) {
                out.append(crashes.size() <= 3
                        ? "Crashes in the container (" + crashes.size() + "):\n"
                        : "Crashes in the container (" + crashes.size() + " in all, the last 3):\n");
                for (int i = Math.max(0, crashes.size() - 3); i < crashes.size(); i++) {
                    for (String text : crashes.get(i)) {
                        out.append("  ").append(text).append('\n');
                    }
                    out.append('\n');
                }
            } else {
                out.append("Crashes in the container: none logged.\n");
            }
            if (!other.isEmpty()) {
                out.append("Other lines worth a look:\n");
                for (LogLine line : other) {
                    out.append("  ").append(clip(line.raw, 230)).append('\n');
                }
            }
            return out.toString();
        }

        /**
         * One crash as short text: the first lines in full (they hold the reason), the reason of every
         * "Caused by" in full, and the first few frames after each that are not Android's own plumbing.
         */
        private List<String> summarizeCrash(List<LogLine> block) {
            List<String> out = new ArrayList<>();
            int frames = 0;
            int framesAllowed = 3;
            for (int i = 0; i < block.size(); i++) {
                LogLine line = block.get(i);
                String text = line.message.trim();
                if (i < 2) {
                    out.add(clip(line.raw, 420));
                } else if (text.startsWith("Caused by:")) {
                    out.add(clip(line.raw, 700));
                    frames = 0;
                    framesAllowed = 9;
                } else if (text.startsWith("at ")) {
                    if (frames < framesAllowed && !isPlumbing(text)) {
                        out.add(clip(line.raw, 230));
                        frames++;
                    }
                } else if (!text.startsWith("...")) {
                    out.add(clip(line.raw, 700));
                }
            }
            return out;
        }

        /**
         * Frames of Android's own message and call plumbing, which say nothing about what failed. The
         * build 42 report showed a refusal by Android's user service with only these frames and the
         * container's call stub in the lines it kept, so the frames of Google's code that made the call
         * (the ones worth reading) were never reached.
         */
        private boolean isPlumbing(String frame) {
            return frame.contains("android.app.ActivityThread") || frame.contains("android.os.Handler")
                    || frame.contains("android.os.Looper") || frame.contains("com.android.internal.os")
                    || frame.contains("java.lang.reflect") || frame.contains("android.app.servertransaction")
                    || frame.contains("dalvik.system") || frame.contains("android.os.Parcel.")
                    || frame.contains("jdk.internal.reflect") || frame.contains(" $Proxy") || frame.contains(".$Proxy")
                    || frame.contains("top.niunaijun.blackbox.fake.hook.ClassInvocationStub");
        }

        /**
         * What Google Play services itself logged after it was asked: its errors and warnings, and the
         * lines that mention accounts, permissions or callers. Written by processes of the container,
         * so the phone's log holds them. The process that handles the sign-in request comes first; the
         * screens of Google's sign-in run in another process of the same package, which follows.
         */
        private String googleSide(List<LogLine> window, List<LogLine> steps) {
            int main = authPid > 0 ? authPid : pidFromSteps(steps);

            // Every process of Google Play services that can be named: the one that took the request,
            // the ones running now, and the ones that crashed (they are not running any more).
            Map<Integer, String> processes = new LinkedHashMap<>();
            if (main > 0) {
                processes.put(main, "");
            }
            addNames(processes, namesFromSteps(steps));
            addNames(processes, googleProcesses());
            addNames(processes, crashedGoogleProcesses(window));
            if (processes.isEmpty()) {
                return "Google Play services' own log: its processes could not be identified.\n";
            }

            long askedAt = 0;
            for (LogLine step : steps) {
                if (step.message.startsWith("asking the sign-in service")) {
                    askedAt = step.key;
                    break;
                }
            }
            if (askedAt == 0) {
                askedAt = keyOf(startedWall);
            }

            StringBuilder out = new StringBuilder();
            int sections = 0;
            for (Map.Entry<Integer, String> entry : processes.entrySet()) {
                if (sections >= 5) {
                    out.append("(more processes of Google Play services were left out)\n");
                    break;
                }
                int pid = entry.getKey();
                // The screens of the sign-in run in a process of their own, and "something went wrong"
                // is written there; the build 44 report kept only 45 of its lines, too few to tell why.
                out.append(processLog(window, pid, entry.getValue(), askedAt, pid == main ? 120 : 90,
                        pid == main));
                sections++;
            }
            return out.toString();
        }

        /** Tags of the container's own launch code: they say which screen was started, where, and why not. */
        private boolean isLaunchTag(String tag) {
            String t = tag.toLowerCase(Locale.US);
            return t.equals("activitystack") || t.equals("appinstrumentation") || t.equals("hcallbackstub")
                    || t.equals("proxyactivity") || t.equals("splashscreen") || t.equals("bprocessmanager")
                    || t.equals("bactivitymanagerservice") || t.equals("loadedapkregistry");
        }

        /**
         * What the container's own launch code wrote from the moment the sign-in screen was opened, from
         * every process of the container: its activity manager and screen start-up lines (they say which
         * screen was started, in which process, and whether anything was found for the request), and the
         * warnings and errors of the processes that are not Google's (the account service's process and
         * this app's own), which the section on Google's side does not cover.
         */
        private String launchSide(List<LogLine> window, List<LogLine> steps) {
            long openedKey = 0;
            for (LogLine step : steps) {
                if (step.message.contains("opening the sign-in screen")
                        || step.message.contains("opening Google's sign-in screen")) {
                    openedKey = step.key;
                    break;
                }
            }
            if (openedKey == 0) {
                return "";
            }
            Map<Integer, String> google = new LinkedHashMap<>();
            int main = authPid > 0 ? authPid : pidFromSteps(steps);
            if (main > 0) {
                google.put(main, "");
            }
            addNames(google, namesFromSteps(steps));
            addNames(google, googleProcesses());
            addNames(google, crashedGoogleProcesses(window));

            List<LogLine> picked = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            int left = 0;
            for (LogLine line : window) {
                if (line.key < openedKey - 500 || TAG.equals(line.tag) || "AndroidRuntime".equals(line.tag)) {
                    continue;
                }
                boolean launch = isLaunchTag(line.tag);
                boolean problem = (line.level == 'W' || line.level == 'E' || line.level == 'F')
                        && !google.containsKey(line.pid) && !isLaunchNoise(line);
                if (!launch && !problem) {
                    continue;
                }
                if (!seen.add(line.pid + "|" + line.tag + "|" + clip(line.message, 90))) {
                    continue;
                }
                if (picked.size() < 80) {
                    picked.add(line);
                } else {
                    left++;
                }
            }
            StringBuilder out = new StringBuilder();
            out.append("Container launch lines after the sign-in screen was opened (every process; "
                    + "the container's activity manager and screen start-up, and warnings and errors of the "
                    + "processes that are not Google's):\n");
            for (LogLine line : picked) {
                out.append("  ").append(clip(line.raw, 230)).append('\n');
            }
            if (picked.isEmpty()) {
                out.append("  (nothing: the container wrote no launch line, so the request may never have reached "
                        + "its activity manager)\n");
            }
            if (left > 0) {
                out.append("  (").append(left).append(" more lines were left out)\n");
            }
            return out.toString();
        }

        private boolean isLaunchNoise(LogLine line) {
            String t = line.tag.toLowerCase(Locale.US);
            return t.equals("libc") || t.equals("nativeloader") || t.equals("ziparchive") || t.equals("chromium")
                    || t.equals("dex2oat") || t.startsWith("camera") || t.equals("hookmanager");
        }

        /** Adds the processes of {@code more}; a name that is already known is kept, an empty one is filled in. */
        private void addNames(Map<Integer, String> into, Map<Integer, String> more) {
            for (Map.Entry<Integer, String> entry : more.entrySet()) {
                String known = into.get(entry.getKey());
                if (known == null || known.isEmpty()) {
                    into.put(entry.getKey(), entry.getValue());
                }
            }
        }

        /** One process of Google Play services: its line counts, then the lines that tell something. */
        private String processLog(List<LogLine> window, int pid, String name, long askedAt, int cap,
                boolean takesRequest) {
            List<LogLine> mine = new ArrayList<>();
            int errors = 0;
            int warnings = 0;
            for (LogLine line : window) {
                // Crashes are summarised on their own, further down.
                if (line.pid != pid || line.key < askedAt - 300 || TAG.equals(line.tag)
                        || "AndroidRuntime".equals(line.tag)) {
                    continue;
                }
                mine.add(line);
                if (line.level == 'E' || line.level == 'F') {
                    errors++;
                } else if (line.level == 'W') {
                    warnings++;
                }
            }
            StringBuilder out = new StringBuilder();
            out.append("Google Play services' own log after the request (process ")
                    .append(name == null || name.isEmpty() ? "" : name + ", pid ")
                    .append(pid).append(takesRequest ? ", handles the sign-in request" : "")
                    .append(", ").append(mine.size()).append(" lines, ").append(errors).append(" errors, ")
                    .append(warnings).append(" warnings):\n");
            List<LogLine> candidates = new ArrayList<>();
            for (LogLine line : mine) {
                if (tellsAboutSignIn(line)) {
                    candidates.add(line);
                }
            }
            // Too many to show: keep the ones that matter most (errors first, then warnings, then
            // lines that only mention accounts and the like; a stack frame is worth the least, so a
            // long trace of something harmless cannot crowd out the real errors after it), and print
            // them in the order they were written.
            List<LogLine> chosen = candidates;
            if (candidates.size() > cap) {
                final List<LogLine> source = candidates;
                List<Integer> order = new ArrayList<>();
                for (int i = 0; i < source.size(); i++) {
                    order.add(i);
                }
                Collections.sort(order, new Comparator<Integer>() {
                    @Override
                    public int compare(Integer a, Integer b) {
                        int byWorth = Integer.compare(importance(source.get(b)), importance(source.get(a)));
                        return byWorth != 0 ? byWorth : Integer.compare(a, b);
                    }
                });
                List<Integer> keep = new ArrayList<>(order.subList(0, cap));
                Collections.sort(keep);
                chosen = new ArrayList<>();
                for (int index : keep) {
                    chosen.add(source.get(index));
                }
            }
            for (LogLine line : chosen) {
                out.append("  ").append(clip(line.raw, 260)).append('\n');
            }
            if (candidates.size() > chosen.size()) {
                out.append("  (").append(candidates.size() - chosen.size())
                        .append(" less important lines were left out)\n");
            }
            if (chosen.isEmpty()) {
                out.append("  (nothing worth showing)\n");
            }
            return out.toString();
        }

        /** How much a line of Google's log is worth showing when there are too many of them. */
        private int importance(LogLine line) {
            String text = line.message.trim();
            boolean frame = text.startsWith("at ") || text.startsWith("...");
            if (line.tag.equals("nativeloader") || line.tag.equals("HookManager")) {
                return 0;
            }
            if (line.tag.equals("LoadedApkRegistry")) {
                return 4; // says whether the second-Android-user fix took effect
            }
            if (line.level == 'E' || line.level == 'F') {
                return frame ? 2 : 5;
            }
            if (!frame && namesARefusalOrNetworkFault(text)) {
                return 4; // a refusal or a network fault, whatever level it was logged at
            }
            if (line.level == 'W') {
                return frame ? 1 : 3;
            }
            return 2;
        }

        /** True for a line that says a call was refused, or that the network could not be used. */
        private boolean namesARefusalOrNetworkFault(String text) {
            String t = text.toLowerCase(Locale.US);
            return t.contains("only 0p") || t.contains("does not belong") || t.contains("caller with uid")
                    || t.contains("not allowed") || t.contains("denied") || t.contains("refus")
                    || t.contains("unknownhost") || t.contains("unable to resolve host")
                    || t.contains("timed out") || t.contains("timeout") || t.contains("ssl")
                    || t.contains("certificate") || t.contains("no network") || t.contains("not connected")
                    || t.contains("network error") || t.contains("networkerror") || t.contains("offline");
        }

        /**
         * The process id of Google's sign-in service, read from the lines of the steps when the
         * attempt ended before any of them was looked at. The tap writes its lines from inside that
         * process, so the id of such a line is the one; the account service names it as well.
         */
        private int pidFromSteps(List<LogLine> steps) {
            int named = 0;
            for (LogLine step : steps) {
                if (step.message.startsWith(TAP_PREFIX)) {
                    return step.pid;
                }
                if (named == 0 && step.message.startsWith("connected to the sign-in service")) {
                    Matcher where = PROCESS_PID.matcher(step.message);
                    if (where.find()) {
                        try {
                            named = Integer.parseInt(where.group(1));
                        } catch (NumberFormatException ignored) {
                            // not usable
                        }
                    }
                }
            }
            return named;
        }

        /** Id and name of the processes the sign-in steps themselves name (the account service and the tap do). */
        private Map<Integer, String> namesFromSteps(List<LogLine> steps) {
            Map<Integer, String> found = new LinkedHashMap<>();
            for (LogLine step : steps) {
                Matcher m = PROCESS_NAMED_PID.matcher(step.message);
                if (m.find()) {
                    try {
                        found.put(Integer.parseInt(m.group(2)), m.group(1));
                    } catch (NumberFormatException ignored) {
                        // not usable
                    }
                }
            }
            return found;
        }

        /** Id and name of every process of Google Play services running in the container right now. */
        private Map<Integer, String> googleProcesses() {
            Map<Integer, String> found = new LinkedHashMap<>();
            try {
                RunningAppProcessInfo info = BlackBoxCore.getBActivityManager()
                        .getRunningAppProcesses(GmsCore.GMS_PKG, userId);
                if (info != null && info.mAppProcessInfoList != null) {
                    for (ActivityManager.RunningAppProcessInfo process : info.mAppProcessInfoList) {
                        if (process != null && process.pid > 0) {
                            found.put(process.pid, process.processName == null ? "" : process.processName);
                        }
                    }
                }
            } catch (Throwable ignored) {
                // the report goes on without the names
            }
            return found;
        }

        /** Id and name of every process of Google Play services that crashed during the attempt. */
        private Map<Integer, String> crashedGoogleProcesses(List<LogLine> window) {
            Map<Integer, String> found = new LinkedHashMap<>();
            for (LogLine line : window) {
                if (!"AndroidRuntime".equals(line.tag)) {
                    continue;
                }
                Matcher m = CRASHED_PROCESS.matcher(line.message);
                if (m.find() && m.group(1).startsWith(GmsCore.GMS_PKG)) {
                    try {
                        found.put(Integer.parseInt(m.group(2)), m.group(1));
                    } catch (NumberFormatException ignored) {
                        // not usable
                    }
                }
            }
            return found;
        }

        private boolean tellsAboutSignIn(LogLine line) {
            if (line.level == 'W' || line.level == 'E' || line.level == 'F') {
                return true;
            }
            if (line.tag.equals("nativeloader") || line.tag.equals("HookManager")) {
                // these name every library and hook, and match words like "caller" or "account"
                return false;
            }
            String tag = line.tag.toLowerCase(Locale.US);
            if (tag.contains("auth") || tag.contains("chimera") || tag.contains("account")
                    || tag.contains("binder") || tag.contains("signin") || tag.contains("gsf")
                    || tag.contains("loadedapk")) {
                return true;
            }
            String text = line.message.toLowerCase(Locale.US);
            return text.contains("exception") || text.contains("denied") || text.contains("permission")
                    || text.contains("not allowed") || text.contains("caller") || text.contains("account")
                    || text.contains("sign-in") || text.contains("signin") || text.contains("login")
                    || text.contains("security") || namesARefusalOrNetworkFault(line.message)
                    || text.contains("network") || text.contains("connect") || text.contains("request")
                    || text.contains("server") || text.contains("failed") || text.contains("error");
        }

        private boolean worthALook(LogLine line) {
            String text = line.message;
            if (text.contains("Not allowed to bind") || text.contains("SecurityException")
                    || text.contains("requires Google Play Store") || text.contains("signature is invalid")
                    || text.contains("GoogleSignatureVerifier") || text.contains("GooglePlayServicesUtil")
                    || text.contains("ClassNotFoundException") || text.contains("NoClassDefFoundError")
                    || text.contains("UnsatisfiedLink") || text.contains("Fatal signal")
                    || text.contains("Caught a RuntimeException") || text.contains("Binder call failed")
                    || text.contains("Only 0p apps") || text.contains("does not belong to uid")
                    || text.contains("Caller with uid") || text.contains("UnknownHostException")
                    || text.contains("Unable to resolve host") || text.contains("SSLHandshake")
                    || text.contains("leaves the container")) {
                return true;
            }
            if (line.level == 'W' || line.level == 'E' || line.level == 'F') {
                String tag = line.tag;
                return tag.contains("BlackBox") || tag.contains("BProcess") || tag.contains("ActiveServices")
                        || tag.contains("BActivity") || tag.contains("Hook") || tag.contains("ProxyService");
            }
            return false;
        }
    }

    // ------------------------------------------------------------------------------ log helpers

    private static LogLine parse(String text) {
        if (text == null || text.length() < 20) {
            return null;
        }
        Matcher m = LOG_LINE.matcher(text);
        if (!m.matches()) {
            return null;
        }
        LogLine line = new LogLine();
        try {
            line.key = key(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)),
                    Integer.parseInt(m.group(3)), Integer.parseInt(m.group(4)),
                    Integer.parseInt(m.group(5)), Integer.parseInt(m.group(6)));
            line.pid = Integer.parseInt(m.group(7));
        } catch (NumberFormatException e) {
            return null;
        }
        line.level = m.group(9).charAt(0);
        line.tag = m.group(10);
        line.message = m.group(11);
        line.raw = text;
        return line;
    }

    /** A number that grows with the time of day inside a year, for comparing log lines. */
    private static long key(int month, int day, int hour, int minute, int second, int millis) {
        return ((((month * 32L + day) * 24 + hour) * 60 + minute) * 60 + second) * 1000 + millis;
    }

    private static long keyOf(long epochMillis) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(epochMillis);
        return key(c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH), c.get(Calendar.HOUR_OF_DAY),
                c.get(Calendar.MINUTE), c.get(Calendar.SECOND), c.get(Calendar.MILLISECOND));
    }

    private static String clip(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max) + "...";
    }

    /** Runs a logcat command and returns the last {@code keep} lines, or null when it cannot run. */
    private static List<String> readLog(int keep, String... command) {
        Process process = null;
        BufferedReader reader = null;
        try {
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.redirectErrorStream(true);
            process = builder.start();
            reader = new BufferedReader(new InputStreamReader(process.getInputStream()), 1 << 16);
            ArrayDeque<String> lines = new ArrayDeque<>();
            String line;
            while ((line = reader.readLine()) != null) {
                if (lines.size() >= keep) {
                    lines.removeFirst();
                }
                lines.addLast(line);
            }
            return new ArrayList<>(lines);
        } catch (Throwable e) {
            return null;
        } finally {
            try {
                if (reader != null) {
                    reader.close();
                }
            } catch (Throwable ignored) {
                // nothing to do
            }
            if (process != null) {
                process.destroy();
            }
        }
    }
}
