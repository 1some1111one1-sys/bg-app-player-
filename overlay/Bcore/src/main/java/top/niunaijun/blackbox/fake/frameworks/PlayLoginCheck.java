package top.niunaijun.blackbox.fake.frameworks;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.accounts.IAccountManagerResponse;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.database.Cursor;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.core.GmsCore;
import top.niunaijun.blackbox.core.system.ServiceManager;
import top.niunaijun.blackbox.core.system.accounts.AuthenticatorCaller;
import top.niunaijun.blackbox.core.system.accounts.IBAccountManagerService;

/**
 * The "Check Play Store login" button: asks the container for the login token that Google Play Store would
 * get, looks at the phone around it, reads this app's own log, and writes the result in plain words
 * ({@link PlayLoginReport}), so a screenshot of it is enough to see where a login breaks.
 *
 * <p>Why: Play Store ends on "Authentication is required. You need to sign in to your Google Account", and
 * that looks the same from outside whether Google Play services refused the request, wanted a screen, could
 * not reach Google, or Play Store could not read the phone's device id. The check asks for the token as Play
 * Store does (the request names Play Store as the asking package, see {@link AuthenticatorCaller}), so the
 * answer is the one Play Store would have had.
 *
 * <p>What it changes: if the container held a saved Play Store token, it is removed for the test so that
 * Google is really asked, and put back when the test fails. A token Google gave is saved like any other, so a
 * stale saved token is replaced by a fresh one. Nothing else is written: no account name, no token and no
 * device id leaves this class (only counts, lengths and whether it worked).
 *
 * <p>Every callback of {@link Listener} runs on the main thread.
 */
public final class PlayLoginCheck {
    private static final String TAG = "BlackBoxAccounts";
    private static final String ACCOUNT_TYPE = "com.google";
    private static final String GSF_PKG = "com.google.android.gsf";
    /** The login token type Play Store asks for. */
    static final String PLAY_TOKEN_TYPE = "oauth2:https://www.googleapis.com/auth/googleplay";
    private static final String GSERVICES = "content://com.google.android.gsf.gservices";

    /** How long to wait for Google Play services to answer. */
    private static final long PROBE_WAIT_MS = 45_000;
    private static final int LOG_LINES = 30000;
    private static final int CONNECT_TIMEOUT_MS = 6000;
    private static final int READ_TIMEOUT_MS = 6000;

    /** Addresses a login passes: Google in general, the sign-in service, and Play Store's own server. */
    private static final String[] HOSTS = {
            "www.google.com/generate_204",
            "android.googleapis.com/auth",
            "android.clients.google.com/",
    };

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    public interface Listener {
        /** A short sentence about what is being checked now. */
        void onProgress(String step);

        /** The check is over: the whole report, and its first sentence. */
        void onDone(String report, String firstSentence);
    }

    /** Lets the caller stop waiting; no callback is made after this. */
    public static final class Handle {
        private final Run run;

        private Handle(Run run) {
            this.run = run;
        }

        public void cancel() {
            run.cancelled = true;
        }
    }

    private PlayLoginCheck() {
    }

    public static Handle start(final int userId, final Listener listener) {
        final Run run = new Run(userId, listener);
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                run.go();
            }
        }, "BlackBoxPlayLoginCheck");
        thread.setDaemon(true);
        thread.start();
        return new Handle(run);
    }

    private static final class Run {
        final int userId;
        final Listener listener;
        volatile boolean cancelled;
        private Account firstAccount;

        Run(int userId, Listener listener) {
            this.userId = userId;
            this.listener = listener;
        }

        void go() {
            final PlayLoginReport.Facts facts = new PlayLoginReport.Facts();
            try {
                facts.build = SignInDiagnostics.BUILD;
                facts.phone = Build.MANUFACTURER + " " + Build.MODEL + ", Android " + Build.VERSION.RELEASE
                        + " (SDK " + Build.VERSION.SDK_INT + ")";
                facts.space = userId;
                facts.time = new SimpleDateFormat("HH:mm", Locale.US).format(new Date());
                Log.i(TAG, "Play Store login check started in space " + userId);

                progress("Looking at the Google apps and the account...");
                apps(facts);
                IBAccountManagerService service = account(facts);
                if (cancelled) {
                    return;
                }

                if (facts.gmsVersion == null) {
                    facts.probe.why = "Google Play services are not installed in this space";
                } else if (service == null || firstAccount == null) {
                    facts.probe.why = facts.googleAccounts == 0 ? "there is no Google account"
                            : "the container's accounts could not be read";
                } else {
                    progress("Asking Google Play services for a Play Store login. This can take up to "
                            + (PROBE_WAIT_MS / 1000) + " seconds...");
                    probe(service, firstAccount, facts.probe);
                }
                if (cancelled) {
                    return;
                }

                progress("Testing the connection to Google...");
                connection(facts);
                if (cancelled) {
                    return;
                }

                progress("Reading the phone's Google device id...");
                deviceId(facts);

                progress("Reading the log...");
                facts.log = PlayLoginLog.read(LOG_LINES);
            } catch (Throwable e) {
                Log.e(TAG, "Play Store login check failed", e);
                facts.probe.why = facts.probe.why.isEmpty() ? "the check itself failed: " + e : facts.probe.why;
            }
            if (cancelled) {
                return;
            }
            final String report = PlayLoginReport.report(facts);
            List<String> sentences = PlayLoginReport.verdict(facts);
            final String first = sentences.isEmpty() ? "" : sentences.get(0);
            Log.i(TAG, "Play Store login check finished: " + first);
            MAIN.post(new Runnable() {
                @Override
                public void run() {
                    if (!cancelled) {
                        listener.onDone(report, first);
                    }
                }
            });
        }

        private void progress(final String step) {
            MAIN.post(new Runnable() {
                @Override
                public void run() {
                    if (!cancelled) {
                        listener.onProgress(step);
                    }
                }
            });
        }

        // ------------------------------------------------------------------ the apps and the account

        private void apps(PlayLoginReport.Facts f) {
            f.gmsVersion = installedVersion(GmsCore.GMS_PKG);
            f.vendingVersion = installedVersion(GmsCore.VENDING_PKG);
            try {
                f.gsfInstalled = BlackBoxCore.get().isInstalled(GSF_PKG, userId);
            } catch (Throwable e) {
                f.gsfInstalled = false;
            }
        }

        /** The version of a package in the space, "?" if it cannot be read, null if it is not installed. */
        private String installedVersion(String packageName) {
            boolean installed;
            try {
                installed = BlackBoxCore.get().isInstalled(packageName, userId);
            } catch (Throwable e) {
                installed = true; // not known: do not claim it is missing
            }
            if (!installed) {
                return null;
            }
            try {
                PackageInfo info = BlackBoxCore.getBPackageManager().getPackageInfo(packageName, 0, userId);
                return info == null || info.versionName == null ? "?" : info.versionName;
            } catch (Throwable e) {
                return "?";
            }
        }

        private IBAccountManagerService account(PlayLoginReport.Facts f) {
            try {
                IBinder binder = BlackBoxCore.get().getService(ServiceManager.ACCOUNT_MANAGER);
                if (binder == null) {
                    f.accountProblem = "the container's account service is not running; open any app in the "
                            + "container once and try again";
                    return null;
                }
                IBAccountManagerService service = IBAccountManagerService.Stub.asInterface(binder);
                Account[] google = service.getAccountsAsUser(ACCOUNT_TYPE, userId);
                f.googleAccounts = google == null ? 0 : google.length;
                Account[] every = service.getAccountsAsUser(null, userId);
                f.accountTypes = SignInDiagnostics.countByType(every);
                if (google != null && google.length > 0) {
                    firstAccount = google[0];
                    try {
                        String saved = service.getPassword(google[0], userId);
                        f.masterTokenLength = saved == null ? 0 : saved.length();
                    } catch (Throwable e) {
                        f.masterTokenLength = -1;
                    }
                }
                return service;
            } catch (Throwable e) {
                f.accountProblem = e.getClass().getSimpleName();
                return null;
            }
        }

        // ------------------------------------------------------------------ the login request

        /** Asks the container's account service for the token Play Store asks for, as Play Store, and waits. */
        private void probe(IBAccountManagerService service, Account account, PlayLoginReport.Probe p) {
            String saved = null;
            try {
                saved = service.peekAuthToken(account, PLAY_TOKEN_TYPE, userId);
                p.savedBefore = Boolean.valueOf(saved != null && !saved.isEmpty());
                if (saved != null && saved.isEmpty()) {
                    saved = null;
                }
            } catch (Throwable e) {
                p.savedBefore = null;
                saved = null;
            }
            if (saved != null) {
                try {
                    // Without this the container would answer from the saved token and Google would not be asked.
                    service.invalidateAuthToken(account.type, saved, userId);
                    p.savedRemoved = true;
                } catch (Throwable e) {
                    p.savedRemoved = false;
                }
            }

            final CountDownLatch done = new CountDownLatch(1);
            final Bundle[] answer = new Bundle[1];
            final int[] errorCode = new int[1];
            final String[] errorMessage = new String[1];
            IAccountManagerResponse response = new IAccountManagerResponse.Stub() {
                @Override
                public void onResult(Bundle value) {
                    answer[0] = value;
                    done.countDown();
                }

                @Override
                public void onError(int code, String message) {
                    errorCode[0] = code;
                    errorMessage[0] = message == null ? "" : message;
                    done.countDown();
                }
            };

            Bundle options = new Bundle();
            options.putString(AccountManager.KEY_ANDROID_PACKAGE_NAME, GmsCore.VENDING_PKG);
            options.putString(AuthenticatorCaller.ASK_AS_PACKAGE, GmsCore.VENDING_PKG);

            long[] trafficBefore = SignInDiagnostics.traffic();
            long started = SystemClock.elapsedRealtime();
            boolean answered = false;
            try {
                service.getAuthToken(response, account, PLAY_TOKEN_TYPE, false, false, options, userId);
                answered = done.await(PROBE_WAIT_MS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                p.kind = PlayLoginReport.PROBE_NOT_SENT;
                p.why = "the check was interrupted";
            } catch (Throwable e) {
                p.kind = PlayLoginReport.PROBE_NOT_SENT;
                p.why = e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + shorten(e.getMessage(), 100));
            }
            p.millis = SystemClock.elapsedRealtime() - started;
            long[] trafficAfter = SignInDiagnostics.traffic();
            if (trafficBefore[0] >= 0 && trafficBefore[1] >= 0 && trafficAfter[0] >= 0 && trafficAfter[1] >= 0) {
                p.sentBytes = Math.max(0, trafficAfter[0] - trafficBefore[0]);
                p.receivedBytes = Math.max(0, trafficAfter[1] - trafficBefore[1]);
            }

            if (p.kind != PlayLoginReport.PROBE_NOT_SENT) {
                classify(p, answered, answer[0], errorCode[0], errorMessage[0]);
            }
            Log.i(TAG, "Play Store login check: request as Play Store ended after " + p.millis + " ms: "
                    + PlayLoginReport.probeLine(p));

            if (p.savedRemoved && p.kind != PlayLoginReport.PROBE_TOKEN) {
                try {
                    service.setAuthToken(account, PLAY_TOKEN_TYPE, saved, userId);
                    p.savedRestored = true;
                } catch (Throwable e) {
                    p.savedRestored = false;
                }
            }
        }

        private void classify(PlayLoginReport.Probe p, boolean answered, Bundle answer, int errorCode,
                String errorMessage) {
            if (!answered) {
                p.kind = PlayLoginReport.PROBE_TIMEOUT;
                p.waitedSeconds = PROBE_WAIT_MS / 1000;
                return;
            }
            if (errorMessage != null) {
                p.kind = PlayLoginReport.PROBE_ERROR;
                p.errorCode = errorCode;
                p.errorMessage = shorten(errorMessage, 160);
                return;
            }
            try {
                String token = answer == null ? null : answer.getString(AccountManager.KEY_AUTHTOKEN);
                if (token != null && !token.isEmpty()) {
                    p.kind = PlayLoginReport.PROBE_TOKEN;
                    p.tokenLength = token.length();
                    return;
                }
                Intent screen = answer == null ? null : (Intent) answer.getParcelable(AccountManager.KEY_INTENT);
                if (screen != null) {
                    p.kind = PlayLoginReport.PROBE_SCREEN;
                    ComponentName component = screen.getComponent();
                    p.screen = component != null ? component.flattenToShortString()
                            : screen.getAction() == null ? "" : screen.getAction();
                    return;
                }
                p.kind = PlayLoginReport.PROBE_ERROR;
                p.errorCode = answer == null ? 0 : answer.getInt(AccountManager.KEY_ERROR_CODE, 0);
                p.errorMessage = answer == null ? "nothing came back"
                        : "nothing usable came back (keys " + answer.keySet() + ")";
            } catch (Throwable e) {
                p.kind = PlayLoginReport.PROBE_ERROR;
                p.errorCode = 0;
                p.errorMessage = "the answer could not be read: " + e.getClass().getSimpleName();
            }
        }

        // ------------------------------------------------------------------ the phone's connection

        private void connection(PlayLoginReport.Facts f) {
            try {
                describeConnection(f);
            } catch (Throwable e) {
                f.connection = "could not be read (" + e.getClass().getSimpleName() + ")";
            }
            for (String address : HOSTS) {
                if (cancelled) {
                    return;
                }
                f.hosts.add(testHost(address, f));
            }
        }

        private void describeConnection(PlayLoginReport.Facts f) {
            Context context = BlackBoxCore.getContext();
            ConnectivityManager manager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (manager == null) {
                f.connection = "not read";
                return;
            }
            StringBuilder out = new StringBuilder();
            if (Build.VERSION.SDK_INT >= 23) {
                Network network = manager.getActiveNetwork();
                if (network == null) {
                    out.append("no active network");
                } else {
                    NetworkCapabilities caps = manager.getNetworkCapabilities(network);
                    if (caps != null) {
                        out.append(caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ? "Wi-Fi"
                                : caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ? "mobile data"
                                : caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ? "ethernet" : "other network");
                        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                            out.append(" + VPN");
                        }
                        out.append(", Android says the internet works: ")
                                .append(caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) ? "yes" : "NO");
                    }
                    if (Build.VERSION.SDK_INT >= 28) {
                        LinkProperties link = manager.getLinkProperties(network);
                        if (link != null) {
                            out.append(", private DNS: ").append(link.isPrivateDnsActive() ? "on" : "off");
                        }
                    }
                }
            }
            if (Build.VERSION.SDK_INT >= 24) {
                int status = manager.getRestrictBackgroundStatus();
                if (out.length() > 0) {
                    out.append(", ");
                }
                if (status == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED) {
                    out.append("Data Saver: ON (this app is not exempt)");
                    f.dataSaverOn = true;
                } else if (status == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_WHITELISTED) {
                    out.append("Data Saver: on, this app is exempt");
                } else {
                    out.append("Data Saver: off");
                }
            }
            f.connection = out.length() == 0 ? "not read" : out.toString();
        }

        /** One request to an address of Google; any HTTP answer at all means it was reached. */
        private PlayLoginReport.HostTest testHost(String address, PlayLoginReport.Facts f) {
            String host = address.indexOf('/') > 0 ? address.substring(0, address.indexOf('/')) : address;
            long started = SystemClock.elapsedRealtime();
            long startedWall = System.currentTimeMillis();
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL("https://" + address).openConnection();
                connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
                connection.setReadTimeout(READ_TIMEOUT_MS);
                connection.setInstanceFollowRedirects(false);
                connection.setUseCaches(false);
                connection.setRequestProperty("User-Agent", "BGAppRunner-check");
                int status = connection.getResponseCode();
                long elapsed = SystemClock.elapsedRealtime() - started;
                long serverTime = connection.getDate();
                if (serverTime > 0 && f.clockSkewSeconds == PlayLoginReport.NO_SKEW) {
                    // the phone's clock at the middle of the request against the time Google put on its answer
                    long phoneTime = startedWall + elapsed / 2;
                    f.clockSkewSeconds = Math.round((phoneTime - serverTime) / 1000.0);
                }
                return new PlayLoginReport.HostTest(host, true, status, elapsed, "");
            } catch (Throwable e) {
                long elapsed = SystemClock.elapsedRealtime() - started;
                String message = e.getMessage();
                return new PlayLoginReport.HostTest(host, false, 0, elapsed, e.getClass().getSimpleName()
                        + (message == null || message.isEmpty() ? "" : ": " + shorten(message, 70)));
            } finally {
                if (connection != null) {
                    try {
                        connection.disconnect();
                    } catch (Throwable ignored) {
                        // nothing more to do
                    }
                }
            }
        }

        // ------------------------------------------------------------------ the phone's Google device id

        /**
         * Reads the device id from Google's services provider the way Play Store does (it is the phone's own
         * provider; the container hands calls to it through). Only whether it was read is kept, never the id.
         */
        private void deviceId(PlayLoginReport.Facts f) {
            Cursor cursor = null;
            try {
                Context context = BlackBoxCore.getContext();
                cursor = context.getContentResolver().query(Uri.parse(GSERVICES), null, null,
                        new String[]{"android_id"}, null);
                if (cursor == null) {
                    f.deviceId = PlayLoginReport.ID_NO_PROVIDER;
                    return;
                }
                String value = null;
                while (cursor.moveToNext()) {
                    if (cursor.getColumnCount() >= 2 && "android_id".equals(cursor.getString(0))) {
                        value = cursor.getString(1);
                        break;
                    }
                }
                if (value == null || value.trim().isEmpty() || isZero(value.trim())) {
                    f.deviceId = PlayLoginReport.ID_ZERO;
                } else {
                    f.deviceId = PlayLoginReport.ID_PRESENT;
                    f.deviceIdDigits = value.trim().length();
                }
            } catch (SecurityException e) {
                f.deviceId = PlayLoginReport.ID_REFUSED;
                f.deviceIdProblem = "SecurityException";
            } catch (Throwable e) {
                f.deviceId = PlayLoginReport.ID_ERROR;
                f.deviceIdProblem = e.getClass().getSimpleName();
            } finally {
                if (cursor != null) {
                    try {
                        cursor.close();
                    } catch (Throwable ignored) {
                        // nothing more to do
                    }
                }
            }
        }

        private boolean isZero(String value) {
            try {
                return Long.parseLong(value) == 0L;
            } catch (NumberFormatException e) {
                return false; // not a number: it is something, so it counts as read
            }
        }

        private String shorten(String text, int max) {
            if (text == null) {
                return "";
            }
            String single = text.replace('\n', ' ').trim();
            return single.length() <= max ? single : single.substring(0, max) + "...";
        }
    }
}
