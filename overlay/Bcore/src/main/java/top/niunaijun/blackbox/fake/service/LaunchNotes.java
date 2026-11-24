package top.niunaijun.blackbox.fake.service;

import android.util.Log;

import java.util.Collection;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * How a launch that leaves the container is written into the log (tag {@code BlackBoxAccounts}, so the
 * sign-in report picks the lines up). Nothing that could identify a person is written: of an address only
 * the scheme, host and a shortened path, with long numbers hidden, and only the names of its parameters,
 * never their values.
 *
 * <p>Why: on a phone with Android 15 Google's sign-in ended on "Something went wrong" while no network
 * traffic was seen. One thing that could not be told from the log was whether a screen of the sign-in was
 * handed to the phone's own browser (Chrome) instead of being shown inside the container.
 */
public final class LaunchNotes {
    private static final String TAG = "BlackBoxAccounts";
    private static final int MAX_LINES = 60;
    private static final int MAX_LENGTH = 420;
    private static final int MAX_PATH = 70;
    private static final int MAX_NAMES = 12;
    private static final Pattern LONG_NUMBER = Pattern.compile("\\d{6,}");

    private static final Set<String> WRITTEN = new HashSet<String>();

    private LaunchNotes() {
    }

    /** Writes the line once (the same text again is dropped), and at most {@value #MAX_LINES} lines in all. */
    public static void note(String text) {
        if (text == null) {
            return;
        }
        String line = clip(text, MAX_LENGTH);
        synchronized (WRITTEN) {
            if (WRITTEN.size() >= MAX_LINES || !WRITTEN.add(line)) {
                return;
            }
        }
        Log.i(TAG, line);
    }

    /** "https://host/path (parameters: a, b)": the address without any value that could identify anyone. */
    public static String describeAddress(String scheme, String host, String path,
            Collection<String> parameterNames) {
        StringBuilder out = new StringBuilder();
        out.append(scheme == null ? "?" : scheme.toLowerCase(Locale.US)).append("://")
                .append(host == null ? "" : host);
        if (path != null && path.length() > 1) {
            out.append(LONG_NUMBER.matcher(clip(path, MAX_PATH)).replaceAll("#"));
        }
        if (parameterNames != null && !parameterNames.isEmpty()) {
            TreeSet<String> sorted = new TreeSet<String>();
            for (String name : parameterNames) {
                if (name != null) {
                    sorted.add(clip(name, 30));
                }
            }
            out.append(" (parameters: ");
            int shown = 0;
            for (String name : sorted) {
                if (shown == MAX_NAMES) {
                    out.append(", ...");
                    break;
                }
                if (shown++ > 0) {
                    out.append(", ");
                }
                out.append(name);
            }
            out.append(')');
        }
        return out.toString();
    }

    static String clip(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max) + "...";
    }
}
