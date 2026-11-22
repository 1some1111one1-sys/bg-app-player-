package top.niunaijun.blackbox.fake.service;

import android.util.Log;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.app.BActivityThread;

/**
 * Asking Android about a network as a virtual app.
 *
 * <p>Chrome's network code (Cronet), which Google Play Store and Google Play services use for everything
 * they send, asks Android about every network with {@code ConnectivityManager.getNetworkInfo(Network)}.
 * That call hands Android the caller's own uid, {@code Process.myUid()}. Inside the container a virtual
 * app is told that its uid is its own made-up one (10003, 10004, ...), but to Android every virtual app
 * is this one app, with this app's real uid. Android 14 and 15 only answer a question about another uid
 * to a caller that holds the permissions of the download manager or the network stack ("Requires one of
 * the following permissions: ACCESS_DOWNLOAD_MANAGER, NETWORK_STACK, MAINLINE_NETWORK_STACK"), so the
 * system threw a SecurityException into Chrome's network setup. Chrome treats an exception in its native
 * start-up as fatal and aborts the process, so Play Store and Play services closed a few seconds after
 * they started, every time, and Google's sign-in screen died with "problem communicating with Google
 * servers".
 *
 * <p>The hook asks with the real uid when the uid in the call is the virtual app's own made-up one, which
 * is the answer an ordinary app gets. A question about any other uid is passed on as it is.
 */
public final class NetworkInfoForUid {
    private static final String TAG = "BlackBoxNetwork";

    private static boolean sLogged;

    private NetworkInfoForUid() {
    }

    /**
     * Passes the call on to the system with the virtual app's own uid replaced by the real one. A refusal
     * of the system is thrown as it is (the SecurityException itself, not the reflection wrapper), so the
     * caller can answer it.
     */
    public static Object ask(Object who, Method method, Object[] args) throws Throwable {
        translateUid(method.getName(), args);
        try {
            return method.invoke(who, args);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            throw cause != null ? cause : e;
        }
    }

    /**
     * Replaces the first int argument (the uid) by the real uid of this app when it is one of the uids a
     * virtual app knows as its own. Returns true when something was replaced.
     */
    public static boolean translateUid(String methodName, Object[] args) {
        if (args == null) {
            return false;
        }
        for (int i = 0; i < args.length; i++) {
            if (!(args[i] instanceof Integer)) {
                continue;
            }
            int uid = ((Integer) args[i]).intValue();
            int real = realUid();
            if (real <= 0 || uid == real || !isOwnUid(uid)) {
                return false;
            }
            args[i] = Integer.valueOf(real);
            if (!sLogged) {
                sLogged = true;
                Log.i(TAG, methodName + ": asking Android as uid " + real + " instead of the virtual app's uid "
                        + uid + ", the way an ordinary app does");
            }
            return true;
        }
        return false;
    }

    private static boolean isOwnUid(int uid) {
        try {
            return uid == BActivityThread.getVisibleUid() || uid == BActivityThread.getBUid()
                    || uid == BActivityThread.getBAppId();
        } catch (Throwable e) {
            return false;
        }
    }

    private static int realUid() {
        try {
            return BlackBoxCore.getHostUid();
        } catch (Throwable e) {
            return -1;
        }
    }
}
