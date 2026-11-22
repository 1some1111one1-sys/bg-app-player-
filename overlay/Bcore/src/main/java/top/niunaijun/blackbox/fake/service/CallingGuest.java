package top.niunaijun.blackbox.fake.service;

import android.os.Binder;
import android.os.Build;
import android.os.Process;
import android.util.Log;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import top.niunaijun.blackbox.core.system.am.IBActivityManagerService;
import top.niunaijun.blackbox.core.system.user.BUserHandle;
import top.niunaijun.blackbox.fake.frameworks.BActivityManager;

/**
 * Who is calling: the virtual app behind a call that came from another virtual app.
 *
 * <p>All virtual apps are one real app to Android, so a process that receives a call from another one
 * (Google Play Store asking Google Play services, Google Play services asking itself from another of its
 * processes) is told by the kernel that the caller has this app's real uid. The container replaces
 * {@code Binder.getCallingUid()} by the uid the calling virtual app is supposed to have, and it used the
 * uid of the app that happened to start the receiving process, for every call that ever arrives there.
 * When Play Store opened first and started Play services, Play services then saw every caller, its own
 * other processes included, as Play Store ("Package com.google.android.gms does not belong to
 * uid=10003"), and refused them.
 *
 * <p>The container's service knows every virtual process by its real process id, and Android tells the
 * receiver the real process id of the caller ({@code Binder.getCallingPid()}), so the receiver can ask the
 * service who that is. The answer is remembered for a while per process id, so a call costs one lookup in
 * a map. When the service does not know the caller (the container app's own screens, a process that is
 * still starting), the old answer is used.
 *
 * <p>Second part: since Android 12 a call that carries an {@code AttributionSource} (every content
 * provider call does) is checked against the caller's uid while the call is read, and the system's own
 * uid always passes the check. The uid in an attribution source is either the real uid or the virtual
 * one, depending on when the calling app made it, and neither matches what the receiver says the caller
 * is, so the providers of one virtual app answered the calls of another one (Play services answering Play
 * Store) with "Calling uid: 10003 doesn't match source uid: 10493". While the check runs on a binder
 * thread, the caller is reported as the system. Providers of the real system are not reached by this (their
 * process is not a virtual one); {@link RealProviderSource} corrects the calls that go to them.
 */
public final class CallingGuest {
    private static final String TAG = "BlackBoxCaller";

    private static final long KNOWN_FOR_MS = 60_000L;
    private static final long UNKNOWN_FOR_MS = 3_000L;
    private static final int MAX_REMEMBERED = 256;
    private static final int STACK_FRAMES_LOOKED_AT = 14;
    private static final String ATTRIBUTION_SOURCE = "android.content.AttributionSource";

    /** process id -> {app id of the virtual app or -1, time until which the answer is good}. */
    private static final Map<Integer, long[]> REMEMBERED = new ConcurrentHashMap<Integer, long[]>();
    private static final ThreadLocal<Boolean> ASKING = new ThreadLocal<Boolean>();

    private static volatile boolean sLogged;

    private CallingGuest() {
    }

    /**
     * The app id of the virtual app whose process is making the call being handled, or -1 when the caller
     * is not a virtual app's process (or is not known yet). Never throws.
     */
    public static int uidOfCaller() {
        try {
            int pid = Binder.getCallingPid();
            if (pid <= 0 || pid == Process.myPid()) {
                return -1;
            }
            return lookUp(pid, System.currentTimeMillis());
        } catch (Throwable e) {
            return -1;
        }
    }

    /** The remembered answer for the process, or one asked of the container's service. */
    private static int lookUp(int pid, long now) {
        long[] known = REMEMBERED.get(Integer.valueOf(pid));
        if (known != null && known[1] > now) {
            return (int) known[0];
        }
        if (ASKING.get() != null) {
            // asking the service made another lookup on this thread: do not go round in a circle
            return -1;
        }
        ASKING.set(Boolean.TRUE);
        int appId = -1;
        try {
            IBActivityManagerService service = BActivityManager.get().getService();
            if (service != null) {
                int buid = service.getBUidByPid(pid);
                if (buid > 0) {
                    appId = BUserHandle.getAppId(buid);
                }
            }
        } catch (Throwable e) {
            appId = -1;
        } finally {
            ASKING.remove();
        }
        remember(pid, appId, now);
        return appId;
    }

    private static void remember(int pid, int appId, long now) {
        if (REMEMBERED.size() >= MAX_REMEMBERED) {
            REMEMBERED.clear();
        }
        REMEMBERED.put(Integer.valueOf(pid), new long[] {appId, now + (appId > 0 ? KNOWN_FOR_MS : UNKNOWN_FOR_MS)});
        if (appId > 0 && !sLogged) {
            sLogged = true;
            Log.i(TAG, "calls from other virtual apps are now told apart by the caller's process (pid " + pid
                    + " is app id " + appId + ")");
        }
    }

    /**
     * True while the thread is reading the AttributionSource of a call that arrived on a binder thread
     * (Android 12 and newer). Android compares the uid in it with the caller's uid there.
     */
    public static boolean insideAttributionCheck() {
        if (Build.VERSION.SDK_INT < 31) {
            return false;
        }
        Thread thread = Thread.currentThread();
        String name = thread.getName();
        if (name == null || !name.regionMatches(true, 0, "binder:", 0, 7)) {
            return false;
        }
        StackTraceElement[] stack = thread.getStackTrace();
        int limit = Math.min(stack.length, STACK_FRAMES_LOOKED_AT);
        for (int i = 0; i < limit; i++) {
            if (ATTRIBUTION_SOURCE.equals(stack[i].getClassName())) {
                return true;
            }
        }
        return false;
    }

    /** For tests: forget everything remembered. */
    static void forgetAll() {
        REMEMBERED.clear();
    }
}
