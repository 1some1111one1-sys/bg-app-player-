package top.niunaijun.blackbox.fake.frameworks;

import android.accounts.Account;
import android.net.TrafficStats;
import android.os.Process;

import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Small facts for the sign-in report that hold nothing private: how much the phone's network was used
 * while the sign-in ran, and how many accounts of which type the container holds (counts, never names).
 *
 * <p>The report of build 44 showed Google's sign-in ending in "there was a problem communicating with
 * Google servers" while the phone's data meter stayed at 0 KB/s. Whether the app really sent nothing (the
 * request never left the phone) or sent something that was refused later decides where to look next, so
 * the report now says it in numbers. All virtual apps are one app to Android, so the count is the whole
 * container's (and this app's own screens, which are idle during a sign-in).
 */
public final class SignInDiagnostics {
    /**
     * The number of the build (the release "build-N" the APK came from). The app has no other visible version
     * number, so a pasted report could not say which build wrote it. Raise it with every release.
     */
    public static final int BUILD = 47;

    private SignInDiagnostics() {
    }

    /** {bytes sent, bytes received} by this app so far; -1 for what the phone does not count. */
    public static long[] traffic() {
        long sent = -1;
        long received = -1;
        try {
            int uid = Process.myUid();
            sent = TrafficStats.getUidTxBytes(uid);
            received = TrafficStats.getUidRxBytes(uid);
        } catch (Throwable ignored) {
            // some phones do not allow it: the report says so
        }
        return new long[]{sent, received};
    }

    /** What was sent and received since {@code start} (a result of {@link #traffic()}), in words. */
    public static String trafficSince(long[] start) {
        return trafficBetween(start, traffic());
    }

    public static String trafficBetween(long[] start, long[] now) {
        if (start == null || now == null || start.length < 2 || now.length < 2
                || start[0] < 0 || start[1] < 0 || now[0] < 0 || now[1] < 0) {
            return "network use: this phone does not count it for apps";
        }
        long sent = Math.max(0, now[0] - start[0]);
        long received = Math.max(0, now[1] - start[1]);
        String text = "network use of this app since the attempt began: sent " + sizeOf(sent)
                + ", received " + sizeOf(received);
        return sent == 0 && received == 0 ? text + " (nothing at all)" : text;
    }

    /** A byte count in a few characters: "0 B", "812 B", "3.4 KB", "1.2 MB". */
    public static String sizeOf(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024L * 1024L) {
            return String.format(Locale.US, "%.1f KB", bytes / 1024.0);
        }
        return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    /** "com.google x1, com.xiaomi x2", or "none": how many accounts of which type, never whose. */
    public static String countByType(Account[] accounts) {
        if (accounts == null || accounts.length == 0) {
            return "none";
        }
        Map<String, Integer> counts = new TreeMap<>();
        for (Account account : accounts) {
            if (account == null) {
                continue;
            }
            String type = account.type == null ? "?" : account.type;
            Integer known = counts.get(type);
            counts.put(type, known == null ? 1 : known + 1);
        }
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            if (out.length() > 0) {
                out.append(", ");
            }
            out.append(entry.getKey()).append(" x").append(entry.getValue());
        }
        return out.length() == 0 ? "none" : out.toString();
    }
}
