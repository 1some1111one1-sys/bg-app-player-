package top.niunaijun.blackbox.fake.frameworks;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What this app's own log says about the login of Play Store: the lines of the container's account service
 * (which app asked for a login token, what Google Play services answered), Play Store's own errors, and the
 * few lines of Google Play services about logins. Every virtual app is part of this app, so its log lines
 * are in this app's log, and the app may read its own log.
 *
 * <p>The reading and the sorting are apart, so that the sorting ({@link Parser}) can be checked on a
 * computer with lines of a real log. Nothing private is kept: e-mail addresses, long numbers (device ids) and
 * long strings that look like a token are replaced before a line is stored.
 */
public final class PlayLoginLog {
    private static final String ACCOUNTS_TAG = "BlackBoxAccounts";
    private static final String PLAY_STORE = "com.android.vending";
    private static final int MAX_ACCOUNT_LINES = 14;
    private static final int MAX_OTHER_LINES = 5;
    private static final int MAX_LINE = 150;
    /** More than this many lines of the log are never read. */
    private static final int MAX_READ = 40000;

    /** One line of "logcat -v threadtime": date, time, process, thread, level, tag, message. */
    private static final Pattern LINE = Pattern.compile(
            "^\\d\\d-\\d\\d (\\d\\d:\\d\\d:\\d\\d)\\.\\d+\\s+\\d+\\s+\\d+\\s+([VDIWEF])\\s+(.*?)\\s*:\\s?(.*)$");
    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    private static final Pattern LONG_NUMBER = Pattern.compile("\\d{12,}");
    /** A token: 40 or more letters, digits, "-" or "_" in a row, with the start Google puts before it. */
    private static final Pattern LONG_WORD = Pattern.compile("(?:ya29\\.|aas_et/|1//)?[A-Za-z0-9_-]{40,}");

    private PlayLoginLog() {
    }

    /** What the log says, sorted. */
    public static final class Findings {
        public int linesRead;
        public String firstTime = "";
        public String lastTime = "";

        /** "Unexpected android-id = 0": Play Store could not read the phone's device id. */
        public int androidIdZero;
        public String androidIdZeroLast = "";

        /** "AuthFailureError" or "Authentication is required". */
        public int authFailures;
        public String authFailureLast = "";

        /** Lines about a caller that Android or Google refused (uid or package mismatch). */
        public int callerRefusals;

        /** Requests of Play Store for a login token that the container's account service wrote down. */
        public int playStoreRequests;
        public int playStoreTokens;
        public int playStoreScreens;
        public int playStoreErrors;

        /** The container's account service, oldest first. */
        public List<String> accountLines = new ArrayList<String>();
        /** Play Store's own warnings and errors about logins, oldest first. */
        public List<String> playStoreLines = new ArrayList<String>();
        /** Google Play services' warnings and errors about logins, oldest first. */
        public List<String> googleLines = new ArrayList<String>();
    }

    /** Reads the app's own log (the most recent lines) and sorts it; null if the log cannot be read. */
    public static Findings read(int lines) {
        Process process = null;
        try {
            int wanted = Math.max(1000, Math.min(lines, MAX_READ));
            ProcessBuilder builder = new ProcessBuilder("logcat", "-d", "-v", "threadtime", "-t", String.valueOf(wanted));
            builder.redirectErrorStream(true);
            process = builder.start();
            Parser parser = new Parser();
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            try {
                String line;
                int count = 0;
                while ((line = reader.readLine()) != null && count < MAX_READ) {
                    count++;
                    parser.accept(line);
                }
            } finally {
                try {
                    reader.close();
                } catch (Throwable ignored) {
                    // nothing more to do
                }
            }
            return parser.findings();
        } catch (Throwable e) {
            return null;
        } finally {
            if (process != null) {
                try {
                    process.destroy();
                } catch (Throwable ignored) {
                    // nothing more to do
                }
            }
        }
    }

    /** Sorts the lines of a log; it is fed one line at a time, oldest first. */
    public static final class Parser {
        private final Findings found = new Findings();
        private String lastAccountText = "";

        public void accept(String line) {
            if (line == null) {
                return;
            }
            Matcher m = LINE.matcher(line);
            if (!m.matches()) {
                return;
            }
            String time = m.group(1);
            char level = m.group(2).charAt(0);
            String tag = m.group(3);
            String message = m.group(4);

            found.linesRead++;
            if (found.firstTime.isEmpty()) {
                found.firstTime = time;
            }
            found.lastTime = time;

            if (message.contains("Unexpected android-id")) {
                found.androidIdZero++;
                found.androidIdZeroLast = time;
                return;
            }
            if (message.contains("AuthFailureError") || message.contains("Authentication is required")) {
                found.authFailures++;
                found.authFailureLast = time;
            }
            if (message.contains("Only 0p apps") || message.contains("does not belong to uid")
                    || (message.contains("Calling uid") && message.contains("match source uid"))
                    || (message.contains("Caller with uid") && message.contains(" is not "))) {
                found.callerRefusals++;
            }

            if (ACCOUNTS_TAG.equals(tag)) {
                accountLine(time, message);
            } else if ("Finsky".equals(tag) && (level == 'E' || level == 'W') && aboutLogin(message)) {
                keepDistinct(found.playStoreLines, time + " " + clean(message));
            } else if ((level == 'E' || level == 'W') && isGoogleLoginTag(tag) && aboutLogin(message)) {
                keepDistinct(found.googleLines, time + " " + tag + ": " + clean(message));
            }
        }

        public Findings findings() {
            return found;
        }

        private void accountLine(String time, String message) {
            if (message.startsWith("token request: app " + PLAY_STORE)) {
                found.playStoreRequests++;
            } else if (message.startsWith("token request of " + PLAY_STORE + " answered: ")) {
                if (message.contains("answered: a token of")) {
                    found.playStoreTokens++;
                } else if (message.contains("a screen to show instead")) {
                    found.playStoreScreens++;
                } else {
                    found.playStoreErrors++;
                }
            }
            String text = clean(message);
            if (text.equals(lastAccountText)) {
                return;
            }
            lastAccountText = text;
            found.accountLines.add(time + " " + text);
            while (found.accountLines.size() > MAX_ACCOUNT_LINES) {
                found.accountLines.remove(0);
            }
        }
    }

    private static boolean isGoogleLoginTag(String tag) {
        return tag.startsWith("GLS") || tag.startsWith("Auth") || tag.startsWith("GoogleAuth")
                || tag.contains("Authenticator");
    }

    private static boolean aboutLogin(String message) {
        String low = message.toLowerCase(Locale.US);
        return low.contains("auth") || low.contains("token") || low.contains("account") || low.contains("login")
                || low.contains("sign in") || low.contains("sign-in");
    }

    /** Adds a line at the end; an equal line that was there is moved to the end instead. Keeps the newest few. */
    private static void keepDistinct(List<String> lines, String text) {
        String withoutTime = text.length() > 9 ? text.substring(9) : text;
        for (int i = 0; i < lines.size(); i++) {
            String old = lines.get(i);
            if ((old.length() > 9 ? old.substring(9) : old).equals(withoutTime)) {
                lines.remove(i);
                break;
            }
        }
        lines.add(text);
        while (lines.size() > MAX_OTHER_LINES) {
            lines.remove(0);
        }
    }

    /** A line with nothing private in it, cut to a readable length. */
    static String clean(String text) {
        if (text == null) {
            return "";
        }
        String out = EMAIL.matcher(text).replaceAll("<email>");
        out = LONG_NUMBER.matcher(out).replaceAll("<number>");
        out = LONG_WORD.matcher(out).replaceAll("<long text>");
        out = out.trim();
        return out.length() <= MAX_LINE ? out : out.substring(0, MAX_LINE) + "...";
    }
}
