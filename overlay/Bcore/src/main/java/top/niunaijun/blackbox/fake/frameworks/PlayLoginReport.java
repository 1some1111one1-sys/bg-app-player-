package top.niunaijun.blackbox.fake.frameworks;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The words of the "Check Play Store login" result: what was found, what it most likely means, and what to
 * try. It holds no Android classes, so the rules can be checked on a computer.
 *
 * <p>Play Store ends on "Authentication is required. You need to sign in to your Google Account" when its
 * request for a login token fails, and from the outside that looks the same whether Google refused the
 * request, wanted a screen, was never reached, or Play Store could not read the phone's device id. The check
 * asks for the token the way Play Store does and looks at the phone around it; this class turns what came
 * back into a few plain sentences first, and the details after them. Nothing private is in it: no account
 * name (counts only), no token (its length only), no device id (whether it was read).
 */
public final class PlayLoginReport {
    private PlayLoginReport() {
    }

    // ------------------------------------------------------------------------------ what the test request did

    public static final int PROBE_NOT_RUN = 0;
    /** Google Play services gave a token. */
    public static final int PROBE_TOKEN = 1;
    /** Google Play services wants to show a screen instead of giving a token. */
    public static final int PROBE_SCREEN = 2;
    /** The container's account service answered with an error. */
    public static final int PROBE_ERROR = 3;
    /** Nothing came back in time. */
    public static final int PROBE_TIMEOUT = 4;
    /** The request could not even be sent (an exception). */
    public static final int PROBE_NOT_SENT = 5;

    /** How a read of the phone's Google device id ended. */
    public static final int ID_UNKNOWN = 0;
    public static final int ID_PRESENT = 1;
    public static final int ID_ZERO = 2;
    public static final int ID_REFUSED = 3;
    public static final int ID_NO_PROVIDER = 4;
    public static final int ID_ERROR = 5;

    /** The clock was not compared. */
    public static final long NO_SKEW = Long.MIN_VALUE;

    /** More than this many seconds between the phone's clock and Google's breaks secure connections. */
    static final long CLOCK_LIMIT_SECONDS = 300;

    /** An answer that came faster than this was a saved token: Google was not asked. */
    static final long SAVED_TOKEN_MILLIS = 150;

    /** One of the connection tests to a Google address. */
    public static final class HostTest {
        public String host = "";
        public boolean reached;
        /** The HTTP status; any status at all means the address was reached. 0 if none. */
        public int status;
        public long millis;
        /** Why it was not reached, in a few words. */
        public String problem = "";

        public HostTest() {
        }

        public HostTest(String host, boolean reached, int status, long millis, String problem) {
            this.host = host;
            this.reached = reached;
            this.status = status;
            this.millis = millis;
            this.problem = problem == null ? "" : problem;
        }
    }

    /** The request for a Play Store login token that the check sends. */
    public static final class Probe {
        public int kind = PROBE_NOT_RUN;
        /** Why it was not run, or what stopped it from being sent. */
        public String why = "";
        public int tokenLength;
        /** The screen Google wants to show: a component or an action. */
        public String screen = "";
        public int errorCode;
        public String errorMessage = "";
        public long millis;
        public long waitedSeconds;
        /** Whether the container had a saved token of this type before the test; null if it could not be told. */
        public Boolean savedBefore;
        /** The saved token was removed so that Google would really be asked. */
        public boolean savedRemoved;
        /** The test failed, so the saved token was put back. */
        public boolean savedRestored;
        /** What the phone's meter counted for this app during the test; -1 if it does not count. */
        public long sentBytes = -1;
        public long receivedBytes = -1;
    }

    /** Everything the check found. */
    public static final class Facts {
        public int build;
        public String phone = "";
        public int space;
        public String time = "";

        // apps
        /** The version of Google Play services in the space; null if it is not installed. */
        public String gmsVersion;
        public String vendingVersion;
        public boolean gsfInstalled;

        // the account
        /** How many Google accounts the space holds; -1 if that could not be read. */
        public int googleAccounts = -1;
        /** "com.google x1, com.xiaomi x1": how many of which type, never whose. */
        public String accountTypes = "";
        /** The length of the saved sign-in of the first account; 0 if there is none, -1 if unknown. */
        public int masterTokenLength = -1;
        public String accountProblem = "";

        public Probe probe = new Probe();

        // the phone's connection
        public String connection = "";
        public List<HostTest> hosts = new ArrayList<HostTest>();
        public long clockSkewSeconds = NO_SKEW;
        /** Data Saver is on and this app is not exempt from it. */
        public boolean dataSaverOn;

        // the phone's Google device id
        public int deviceId = ID_UNKNOWN;
        public int deviceIdDigits;
        public String deviceIdProblem = "";

        /** What the app's own log says; null if it could not be read. */
        public PlayLoginLog.Findings log;
    }

    // ------------------------------------------------------------------------------ the sentences

    /** The result in a few sentences: what happened, and what it means or what to try. */
    public static List<String> verdict(Facts f) {
        List<String> out = new ArrayList<String>();
        if (f.gmsVersion == null) {
            out.add("Google Play services are not installed in this space. Turn on \"Google Play services in the "
                    + "container\" first, then sign in.");
            return out;
        }
        if (f.googleAccounts == 0) {
            out.add("There is no Google account in this space yet, so Play Store has nothing to log in with.");
            out.add("Tap \"Sign in to Google Play\" first.");
            return out;
        }
        Probe p = f.probe;
        switch (p.kind) {
            case PROBE_TOKEN:
                if (p.millis < SAVED_TOKEN_MILLIS && !p.savedRemoved) {
                    out.add("The container answered with a saved login token (" + p.tokenLength + " characters) "
                            + "within " + p.millis + " ms, so Google was not asked this time.");
                    out.add("That says little about Google. If Play Store still shows \"Authentication is "
                            + "required\", the saved token may be old: run the check again.");
                } else {
                    out.add("Google Play services gave Play Store a login token (" + p.tokenLength + " characters)"
                            + (p.savedRemoved ? ", and it replaced an older saved one" : "") + ".");
                    out.add("So the container does hand the login out. If Play Store still shows \"Authentication is "
                            + "required\", the refusal comes after this step. "
                            + (warnings(f).isEmpty() ? "The log below may say more." : "See the lines marked !! and the log below."));
                }
                break;
            case PROBE_SCREEN:
                out.add("Google Play services did not give a token. It wants to show a screen first"
                        + (p.screen.isEmpty() ? "" : " (" + p.screen + ")") + ".");
                out.add("Play Store cannot show that screen itself, so it ends on \"Authentication is required\". "
                        + "Open Play Store and finish any sign-in screen it shows. If none appears, the account "
                        + "probably has to be removed and added again.");
                break;
            case PROBE_ERROR:
                out.addAll(errorSentences(p));
                break;
            case PROBE_TIMEOUT:
                out.add("Google Play services did not answer within " + p.waitedSeconds + " seconds.");
                out.add("Its sign-in service probably did not start inside the container, or it started and then "
                        + "stopped without answering. The log lines below may say which.");
                break;
            case PROBE_NOT_SENT:
                out.add("The login request could not be sent: " + (p.why.isEmpty() ? "unknown reason" : p.why) + ".");
                break;
            default:
                out.add("The login request was not tested" + (p.why.isEmpty() ? "" : " (" + p.why + ")") + ".");
                break;
        }
        return out;
    }

    private static List<String> errorSentences(Probe p) {
        List<String> out = new ArrayList<String>();
        String message = p.errorMessage == null ? "" : p.errorMessage;
        String low = message.toLowerCase(Locale.US);
        String detail = " (error " + p.errorCode + ", " + errorName(p.errorCode)
                + (message.isEmpty() ? "" : ": " + message) + ")";
        if (low.contains("bind failure") || low.contains("no sign-in service")) {
            out.add("Google Play services' sign-in service could not be started or found inside the container" + detail + ".");
            out.add("Turn \"Google Play services in the container\" off and on again, then try once more.");
        } else if (low.contains("timeout")) {
            out.add("The container gave up waiting for Google Play services" + detail + ".");
            out.add("Its sign-in service is probably slow to start or stuck. Try once more; the log lines below may say more.");
        } else if (low.contains("disconnected")) {
            out.add("Google Play services' sign-in service stopped while it was answering" + detail + ".");
            out.add("It probably crashed inside the container. The log lines below may say where.");
        } else if (low.contains("invalid intent")) {
            out.add("Google answered with a screen that this container could not find, so the answer was dropped" + detail + ".");
        } else if (low.contains("only 0p") || low.contains("does not belong to uid") || low.contains("not allowed")) {
            out.add("Google Play services did not accept who was asking" + detail + ".");
            out.add("This is the caller check of Google's sign-in service. It is a bug of the container, not of your account.");
        } else if (p.errorCode == 3 || low.contains("network")) {
            out.add("Google Play services could not reach Google" + detail + ".");
            out.add("See the connection lines below: they show whether the phone itself can reach Google.");
        } else if (p.errorCode == 9 || low.contains("badauthentication") || low.contains("bad authentication")) {
            out.add("Google refused the saved sign-in of this account" + detail + ".");
            out.add("The account probably has to be removed and added again.");
        } else {
            out.add("Google Play services answered with an error" + detail + ".");
        }
        return out;
    }

    /** The name of an account manager error code. */
    static String errorName(int code) {
        switch (code) {
            case 1:
                return "remote exception";
            case 3:
                return "network error";
            case 4:
                return "cancelled";
            case 5:
                return "invalid response";
            case 6:
                return "unsupported operation";
            case 7:
                return "bad arguments";
            case 8:
                return "bad request";
            case 9:
                return "bad authentication";
            case 100:
                return "user restricted";
            case 101:
                return "management disabled";
            default:
                return "code " + code;
        }
    }

    /** Things that can make Play Store refuse to log in whatever the test request did. */
    public static List<String> warnings(Facts f) {
        List<String> out = new ArrayList<String>();
        PlayLoginLog.Findings log = f.log;
        if (log != null && log.androidIdZero > 0) {
            out.add("Play Store could not read the phone's Google device id: it wrote \"Unexpected android-id = 0\" "
                    + log.androidIdZero + " time" + (log.androidIdZero == 1 ? "" : "s")
                    + (log.androidIdZeroLast.isEmpty() ? "" : ", the last at " + log.androidIdZeroLast)
                    + ". Without a device id Play Store cannot log in.");
        }
        if (f.deviceId == ID_ZERO || f.deviceId == ID_REFUSED || f.deviceId == ID_ERROR) {
            out.add("This app could not read the phone's Google device id (" + deviceIdLine(f) + ").");
        }
        if (!f.hosts.isEmpty()) {
            int reached = 0;
            for (HostTest host : f.hosts) {
                if (host.reached) {
                    reached++;
                }
            }
            if (reached == 0) {
                out.add("This app could not reach any Google address (see the connection lines). Check the phone's "
                        + "internet, VPN and private DNS, and whether this app is allowed to use data.");
            } else if (reached < f.hosts.size()) {
                out.add("Some Google addresses could not be reached (see the connection lines). A filter, VPN or "
                        + "private DNS on the phone may be blocking them.");
            }
        }
        if (f.clockSkewSeconds != NO_SKEW && Math.abs(f.clockSkewSeconds) > CLOCK_LIMIT_SECONDS) {
            out.add("The phone's clock is about " + minutes(f.clockSkewSeconds) + " minutes away from Google's. "
                    + "Secure connections and logins fail with a wrong clock. Turn on automatic date and time.");
        }
        if (f.dataSaverOn) {
            out.add("Data Saver is on and this app is not exempt from it, so Google Play services may get no "
                    + "internet while it works in the background.");
        }
        if (log != null && log.authFailures > 0) {
            out.add("Play Store logged an authentication failure " + log.authFailures + " time"
                    + (log.authFailures == 1 ? "" : "s")
                    + (log.authFailureLast.isEmpty() ? "" : ", the last at " + log.authFailureLast) + ".");
        }
        if (log != null && log.callerRefusals > 0) {
            out.add("The log has " + log.callerRefusals + " line" + (log.callerRefusals == 1 ? "" : "s")
                    + " about a caller that Android or Google refused (uid or package mismatch).");
        }
        if (log != null && f.probe.kind == PROBE_TOKEN && log.playStoreRequests == 0 && log.linesRead > 0) {
            out.add("The log shows no login request from Play Store. Open Play Store, wait for its error, then run "
                    + "this check again so the log has it.");
        }
        return out;
    }

    private static long minutes(long seconds) {
        return Math.round(Math.abs(seconds) / 60.0);
    }

    // ------------------------------------------------------------------------------ the details

    /** The whole text: the result first, then what was checked, then the log lines. */
    public static String report(Facts f) {
        StringBuilder out = new StringBuilder();
        out.append("PLAY STORE LOGIN CHECK (build ").append(f.build).append(")\n");
        out.append(f.phone).append(", space ").append(f.space);
        if (!f.time.isEmpty()) {
            out.append(", ").append(f.time);
        }
        out.append("\n\nRESULT\n");
        for (String line : verdict(f)) {
            out.append(line).append('\n');
        }
        List<String> warnings = warnings(f);
        if (!warnings.isEmpty()) {
            out.append('\n');
            for (String warning : warnings) {
                out.append("!! ").append(warning).append('\n');
            }
        }

        out.append("\nWHAT WAS CHECKED\n");
        out.append("1. Apps in this space: ").append(appsLine(f)).append('\n');
        out.append("2. Google account: ").append(accountLine(f)).append('\n');
        out.append("3. Login request as Play Store: ").append(probeLine(f.probe)).append('\n');
        for (String extra : probeExtras(f.probe)) {
            out.append("   ").append(extra).append('\n');
        }
        out.append("4. The phone's connection: ").append(f.connection.isEmpty() ? "not read" : f.connection).append('\n');
        for (HostTest host : f.hosts) {
            out.append("   ").append(hostLine(host)).append('\n');
        }
        out.append("   ").append(clockLine(f)).append('\n');
        out.append("5. The phone's Google device id: ").append(deviceIdLine(f)).append('\n');
        appendLog(out, f.log);
        return out.toString();
    }

    static String appsLine(Facts f) {
        return "Google Play services " + (f.gmsVersion == null ? "NOT INSTALLED" : f.gmsVersion)
                + ", Play Store " + (f.vendingVersion == null ? "NOT INSTALLED" : f.vendingVersion)
                + ", Services Framework " + (f.gsfInstalled ? "yes" : "NO");
    }

    static String accountLine(Facts f) {
        if (f.googleAccounts < 0) {
            return "could not be read" + (f.accountProblem.isEmpty() ? "" : " (" + f.accountProblem + ")");
        }
        StringBuilder out = new StringBuilder();
        out.append(f.googleAccounts).append(" Google account").append(f.googleAccounts == 1 ? "" : "s");
        if (!f.accountTypes.isEmpty()) {
            out.append(" (all accounts: ").append(f.accountTypes).append(')');
        }
        if (f.googleAccounts > 0) {
            out.append("; saved sign-in: ");
            if (f.masterTokenLength < 0) {
                out.append("unknown");
            } else if (f.masterTokenLength == 0) {
                out.append("NONE");
            } else {
                out.append("yes (").append(f.masterTokenLength).append(" characters)");
            }
        }
        return out.toString();
    }

    static String probeLine(Probe p) {
        switch (p.kind) {
            case PROBE_TOKEN:
                return "a token of " + p.tokenLength + " characters, after " + seconds(p.millis);
            case PROBE_SCREEN:
                return "no token; Google wants to show a screen" + (p.screen.isEmpty() ? "" : ": " + p.screen)
                        + ", after " + seconds(p.millis);
            case PROBE_ERROR:
                return "error " + p.errorCode + " (" + errorName(p.errorCode) + ")"
                        + (p.errorMessage == null || p.errorMessage.isEmpty() ? "" : ": " + p.errorMessage)
                        + ", after " + seconds(p.millis);
            case PROBE_TIMEOUT:
                return "no answer within " + p.waitedSeconds + " seconds";
            case PROBE_NOT_SENT:
                return "could not be sent: " + (p.why.isEmpty() ? "unknown reason" : p.why);
            default:
                return "not run" + (p.why.isEmpty() ? "" : " (" + p.why + ")");
        }
    }

    static List<String> probeExtras(Probe p) {
        List<String> out = new ArrayList<String>();
        if (p.kind == PROBE_NOT_RUN) {
            return out;
        }
        if (p.savedBefore != null) {
            String text = "A saved Play Store token before the test: " + (p.savedBefore.booleanValue() ? "yes" : "no");
            if (p.savedBefore.booleanValue()) {
                text += p.savedRestored ? " (removed for the test, then put back)"
                        : p.savedRemoved ? " (removed for the test, so that Google was asked)" : " (kept)";
            }
            out.add(text);
        }
        if (p.sentBytes >= 0 && p.receivedBytes >= 0) {
            out.add("This app's network use during the test: sent " + SignInDiagnostics.sizeOf(p.sentBytes)
                    + ", received " + SignInDiagnostics.sizeOf(p.receivedBytes)
                    + (p.sentBytes == 0 && p.receivedBytes == 0 ? " (nothing at all)" : ""));
        }
        return out;
    }

    static String hostLine(HostTest host) {
        if (host.reached) {
            return host.host + ": reached (HTTP " + host.status + ", " + seconds(host.millis) + ")";
        }
        return host.host + ": NOT reached" + (host.problem.isEmpty() ? "" : " (" + host.problem + ")");
    }

    static String clockLine(Facts f) {
        if (f.clockSkewSeconds == NO_SKEW) {
            return "Phone clock compared with Google's: not measured";
        }
        long s = Math.abs(f.clockSkewSeconds);
        if (s <= CLOCK_LIMIT_SECONDS) {
            return "Phone clock compared with Google's: " + s + " s apart (fine)";
        }
        return "Phone clock compared with Google's: " + minutes(f.clockSkewSeconds) + " minutes apart (TOO MUCH)";
    }

    static String deviceIdLine(Facts f) {
        switch (f.deviceId) {
            case ID_PRESENT:
                return "read from this app, " + f.deviceIdDigits + " digits";
            case ID_ZERO:
                return "this app reads 0 or nothing";
            case ID_REFUSED:
                return "refused" + (f.deviceIdProblem.isEmpty() ? "" : " (" + f.deviceIdProblem + ")");
            case ID_NO_PROVIDER:
                return "the phone has no Google Services Framework provider";
            case ID_ERROR:
                return "could not be read" + (f.deviceIdProblem.isEmpty() ? "" : " (" + f.deviceIdProblem + ")");
            default:
                return "not checked";
        }
    }

    private static void appendLog(StringBuilder out, PlayLoginLog.Findings log) {
        out.append('\n');
        if (log == null) {
            out.append("FROM THE LOG\nThe log could not be read.\n");
            return;
        }
        out.append("FROM THE LOG (").append(log.linesRead).append(" lines");
        if (!log.firstTime.isEmpty()) {
            out.append(", ").append(log.firstTime).append(" to ").append(log.lastTime);
        }
        out.append(")\n");
        out.append("Login requests from Play Store seen: ").append(log.playStoreRequests)
                .append(" (answered with a token ").append(log.playStoreTokens)
                .append(", a screen ").append(log.playStoreScreens)
                .append(", an error ").append(log.playStoreErrors).append(")\n");
        if (log.accountLines.isEmpty()) {
            out.append("No lines from the container's account service.\n");
        } else {
            out.append("Account service (newest last):\n");
            for (String line : log.accountLines) {
                out.append("  ").append(line).append('\n');
            }
        }
        if (!log.playStoreLines.isEmpty()) {
            out.append("Play Store's own errors:\n");
            for (String line : log.playStoreLines) {
                out.append("  ").append(line).append('\n');
            }
        }
        if (!log.googleLines.isEmpty()) {
            out.append("Google Play services, about logins:\n");
            for (String line : log.googleLines) {
                out.append("  ").append(line).append('\n');
            }
        }
    }

    private static String seconds(long millis) {
        if (millis < 1000) {
            return millis + " ms";
        }
        return String.format(Locale.US, "%.1f s", millis / 1000.0);
    }
}
