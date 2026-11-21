package top.niunaijun.blackbox.fake.service;

import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.os.Process;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;

import black.android.app.BRActivityThread;
import black.android.os.BRUserHandle;
import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.utils.Slog;

/**
 * Keeps a virtual app's code in one piece when this app runs in a second Android user.
 *
 * Android keeps one "LoadedApk" (the app's code, class loader and Application) per package in the
 * process, and finds it again by package name. The engine tells Android that a virtual app's uid
 * is only its app id, which Android reads as belonging to user 0. In a process that really runs in
 * another user (a phone's second space, a work profile, user 10), Android decides "this app is
 * from a different user" and refuses to find or keep the LoadedApk at all: every request builds a
 * brand new one, with no Application. What that did to Google Play services on such a phone:
 *  - its services were created with a context that had no Application, so its own start-up code hit
 *    a null pointer, the account service never answered and "Opening Google sign-in" never ended;
 *  - its sign-in screen was launched on a second copy of the code, which made the Application a
 *    second time, which Google's code forbids, so that process crashed again and again.
 *
 * Two small steps put it right, and only when the user parts differ (on the primary user, where
 * Android already caches it, nothing here does anything):
 *  1. {@link #register} files the LoadedApk the engine built for the app under the package name,
 *     so every later lookup (services, other contexts) gets this one.
 *  2. {@link #forThisUser} hands Android the screen's info with the uid in this process's user, so
 *     the lookup made while launching a screen is not rejected as "different user" either.
 */
public final class LoadedApkRegistry {
    private static final String TAG = "LoadedApkRegistry";
    private static final int PER_USER_RANGE = 100000;

    private LoadedApkRegistry() {
    }

    /** The Android user this process runs in, read the same way Android reads it when it looks up an app. */
    private static int myUser() {
        try {
            return BRUserHandle.get().myUserId();
        } catch (Throwable ignored) {
            // fall through
        }
        return Process.myUid() / PER_USER_RANGE;
    }

    /**
     * Files {@code loadedApk} (the engine's LoadedApk of the virtual app, the one that owns its
     * Application) as the package's code in this process. Never throws.
     */
    public static void register(String packageName, Object loadedApk, ApplicationInfo info) {
        try {
            if (packageName == null || loadedApk == null || info == null) {
                return;
            }
            int me = myUser();
            int appUser = info.uid / PER_USER_RANGE;
            if (appUser == me) {
                return; // Android finds and keeps it by itself
            }
            Map<String, WeakReference<?>> packages = packages();
            if (packages == null) {
                Slog.w(TAG, "could not reach the package table, " + packageName + " keeps separate copies of its code"
                        + " (app uid user " + appUser + ", this process user " + me + ")");
                return;
            }
            Object lock = lock(packages);
            WeakReference<?> previous;
            synchronized (lock) {
                previous = packages.put(packageName, new WeakReference<Object>(loadedApk));
            }
            Object old = previous == null ? null : previous.get();
            Slog.i(TAG, "registered the code of " + packageName + " for user " + me + " (app uid user " + appUser
                    + (old == null || old == loadedApk ? ")" : ", replaced another copy)"));
        } catch (Throwable e) {
            Slog.w(TAG, "could not register the code of " + packageName + ": " + e);
        }
    }

    /**
     * The same screen info with the app's uid in this process's user, or the info itself when it
     * already is. A copy is returned; the engine's own object is never changed. Never throws.
     */
    public static ActivityInfo forThisUser(ActivityInfo info) {
        try {
            if (info == null || info.applicationInfo == null) {
                return info;
            }
            int me = myUser();
            int uid = info.applicationInfo.uid;
            if (uid / PER_USER_RANGE == me) {
                return info;
            }
            ActivityInfo copy = new ActivityInfo(info);
            ApplicationInfo app = new ApplicationInfo(info.applicationInfo);
            app.uid = me * PER_USER_RANGE + uid % PER_USER_RANGE;
            copy.applicationInfo = app;
            Slog.d(TAG, "launching " + info.name + " with uid " + uid + " as " + app.uid + " (this process runs in user "
                    + me + ")");
            return copy;
        } catch (Throwable e) {
            Slog.w(TAG, "could not adjust the uid for " + (info == null ? null : info.name) + ": " + e);
            return info;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, WeakReference<?>> packages() {
        Object thread = BlackBoxCore.mainThread();
        try {
            Map<String, WeakReference<?>> map = BRActivityThread.get(thread).mPackages();
            if (map != null) {
                return map;
            }
        } catch (Throwable e) {
            Slog.w(TAG, "reading ActivityThread.mPackages the usual way failed: " + e);
        }
        try {
            Class<?> type = thread.getClass();
            while (type != null) {
                try {
                    Field field = type.getDeclaredField("mPackages");
                    field.setAccessible(true);
                    return (Map<String, WeakReference<?>>) field.get(thread);
                } catch (NoSuchFieldException notHere) {
                    type = type.getSuperclass();
                }
            }
        } catch (Throwable e) {
            Slog.w(TAG, "reading ActivityThread.mPackages directly failed: " + e);
        }
        return null;
    }

    /** Android guards the package table with its resources manager; do the same when it can be reached. */
    private static Object lock(Object fallback) {
        try {
            Class<?> manager = Class.forName("android.app.ResourcesManager");
            Method getInstance = manager.getDeclaredMethod("getInstance");
            getInstance.setAccessible(true);
            Object instance = getInstance.invoke(null);
            if (instance != null) {
                return instance;
            }
        } catch (Throwable ignored) {
            // the table itself is the next best thing to lock
        }
        return fallback;
    }
}
