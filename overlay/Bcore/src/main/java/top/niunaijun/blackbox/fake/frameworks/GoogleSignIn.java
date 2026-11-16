package top.niunaijun.blackbox.fake.frameworks;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.accounts.IAccountManagerResponse;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
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
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.core.GmsCore;
import top.niunaijun.blackbox.core.system.ServiceManager;
import top.niunaijun.blackbox.core.system.accounts.IBAccountManagerService;

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

    /** Without an answer after this long, Google's sign-in screen is tried directly as well. */
    private static final long DIRECT_AFTER_MS = 25_000;
    /** Without any result after this long, the person gets the report. */
    private static final long GIVE_UP_MS = 60_000;
    /** How long to watch for the account after a sign-in screen was opened. */
    private static final long WATCH_MS = 5 * 60_000;
    /** How long a new account has to be there before the sign-in counts as done. */
    private static final long ACCOUNT_SETTLE_MS = 20_000;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /** Sign-in screens of Google Play services, tried one by one when the direct way is used. */
    private static final String[] DIRECT_SCREENS = {
            "com.google.android.gms.auth.uiflows.minutemaid.MinuteMaidActivity",
            "com.google.android.gms.auth.uiflows.addaccount.AccountIntroActivity",
            "com.google.android.gms.auth.uiflows.addaccount.AddAccountActivity",
    };

    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");

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

    private static final class Attempt {
        final int userId;
        final Listener listener;
        final String id = Long.toString(System.currentTimeMillis() % 100_000_000L, 36);
        final long startedAt = SystemClock.elapsedRealtime();
        final long startedWall = System.currentTimeMillis();

        private final StringBuilder trace = new StringBuilder();
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
        private int polls;
        private String lastProgress = "";
        private int accountsBefore;
        private long accountSeenAt;
        private IBAccountManagerService service;

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
            String line = String.format(Locale.US, "%5.1f s  %s",
                    (SystemClock.elapsedRealtime() - startedAt) / 1000.0, text);
            synchronized (trace) {
                trace.append(line).append('\n');
            }
            Log.i(TAG, "sign-in attempt " + id + ": " + text);
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
                Intent probe = new Intent(AccountManager.ACTION_AUTHENTICATOR_INTENT);
                probe.setPackage(GmsCore.GMS_PKG);
                ResolveInfo found = BlackBoxCore.getBPackageManager().resolveService(probe, 0, null, userId);
                fact("Sign-in service found by the container: "
                        + (found != null && found.serviceInfo != null ? found.serviceInfo.name : "none"));
            } catch (Throwable e) {
                fact("Sign-in service found by the container: could not be read (" + e + ")");
            }
            try {
                Account[] accounts = service.getAccountsAsUser(ACCOUNT_TYPE, userId);
                accountsBefore = accounts == null ? 0 : accounts.length;
                fact("Google accounts already in this space: " + accountsBefore);
            } catch (Throwable e) {
                fact("Google accounts already in this space: could not be read");
            }
        }

        private void request() throws Exception {
            Bundle options = new Bundle();
            options.putString("androidPackageName", BlackBoxCore.getHostPkg());

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
                Log.i(TAG, "Google sign-in from settings, answer holds " + value.keySet());

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
            failOrTryDirect(friendlyError(errorCode, errorMessage));
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
                screen.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                // Start it from this app (which is on screen) rather than through a running container app.
                screen.putExtra("bg_runner_from_host", true);
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

        private ComponentName findDirectScreen() {
            BPackageManager pm = BlackBoxCore.getBPackageManager();
            List<String> names = new ArrayList<>();
            try {
                PackageInfo info = pm.getPackageInfo(GmsCore.GMS_PKG, PackageManager.GET_ACTIVITIES, userId);
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
                if (pm.getActivityInfo(component, 0, userId) != null) {
                    return component;
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

        private void raise(Stage next) {
            if (next.rank >= stage.rank) {
                stage = next;
            }
        }

        // ------------------------------------------------------------------ the report

        String buildReport(String headline) {
            StringBuilder r = new StringBuilder();
            try {
                r.append("BG App Runner, Google sign-in report\n");
                synchronized (facts) {
                    r.append(facts);
                }
                r.append("Result: ").append(headline).append('\n');
                r.append("Stopped at: ").append(stage.report).append('\n');
                r.append("Timeline:\n");
                synchronized (trace) {
                    r.append(trace);
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
            List<String> raw = readLog(15000, "logcat", "-d", "-v", "threadtime", "-t", "15000");
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

            // 2. The last three crashes.
            List<List<LogLine>> crashes = new ArrayList<>();
            for (int i = 0; i < window.size(); i++) {
                LogLine line = window.get(i);
                if (!"AndroidRuntime".equals(line.tag) || !line.message.startsWith("FATAL EXCEPTION")) {
                    continue;
                }
                List<LogLine> block = new ArrayList<>();
                block.add(line);
                for (int j = i + 1; j < window.size() && block.size() < 14; j++) {
                    LogLine next = window.get(j);
                    if (next.pid != line.pid || !"AndroidRuntime".equals(next.tag)) {
                        break;
                    }
                    block.add(next);
                }
                crashes.add(block);
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
                if (otherSeen.add(key) && other.size() < 25) {
                    other.add(line);
                }
            }

            StringBuilder out = new StringBuilder();
            out.append("Container log, sign-in steps:\n");
            if (steps.isEmpty()) {
                out.append("  (none; ").append(logReadable ? "the container wrote nothing"
                        : "the phone's log does not show them").append(")\n");
            }
            int limit = Math.min(steps.size(), 70);
            for (int i = steps.size() - limit; i < steps.size(); i++) {
                out.append("  ").append(clip(steps.get(i).raw, 230)).append('\n');
            }
            if (!crashes.isEmpty()) {
                out.append(crashes.size() <= 3
                        ? "Crashes in the container (" + crashes.size() + "):\n"
                        : "Crashes in the container (" + crashes.size() + " in all, the last 3):\n");
                for (int i = Math.max(0, crashes.size() - 3); i < crashes.size(); i++) {
                    for (LogLine line : crashes.get(i)) {
                        out.append("  ").append(clip(line.raw, 230)).append('\n');
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

        private boolean worthALook(LogLine line) {
            String text = line.message;
            if (text.contains("Not allowed to bind") || text.contains("SecurityException")
                    || text.contains("requires Google Play Store") || text.contains("signature is invalid")
                    || text.contains("GoogleSignatureVerifier") || text.contains("GooglePlayServicesUtil")
                    || text.contains("ClassNotFoundException") || text.contains("NoClassDefFoundError")
                    || text.contains("UnsatisfiedLink") || text.contains("Fatal signal")) {
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
