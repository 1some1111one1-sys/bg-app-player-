package top.niunaijun.blackbox.core.system.accounts;

import android.os.Binder;
import android.os.Bundle;
import android.os.Process;
import android.util.Log;

import top.niunaijun.blackbox.core.system.BProcessManagerService;
import top.niunaijun.blackbox.core.system.ProcessRecord;
import top.niunaijun.blackbox.core.system.pm.BPackageManagerService;

/**
 * Who a sign-in service (Google Play services) is told is asking.
 *
 * <p>When an app asks Android's account manager for a login token or a new account, Android calls the
 * sign-in service of that account type and tells it in the request who the app is: its package name and its
 * uid. Google Play services does not take the app's word for it. It asks its own package manager which
 * packages belong to that uid, and only goes on when the app's package is one of them; for some requests it
 * also demands a first-party app ("Only 0p apps are allowed to call this API.").
 *
 * <p>All virtual apps are one real app to Android, so the container's account service saw every caller
 * under the container's own uid and put that uid in the request. Google Play services then looked up that
 * uid inside the container, found its own packages instead of the app that asked (Google Play Store), and
 * refused: Play Store could neither add an account nor get a login token, and showed its sign-in screen
 * again and again, although the account was in the container.
 *
 * <p>A virtual app has a uid of its own inside the container, the one it sees when it asks for it
 * (10003 for Google Play Store, ...). The service knows which virtual process made the call from the
 * caller's process id, so the request carries that uid. A call from this app's own screens (for example
 * the sign-in started from the settings) comes from no virtual process and keeps the uid it had.
 *
 * <p>One exception, for the "Check Play Store login" button of this app: it asks for the login token that
 * Google Play Store would get, and Google only gives that to a request that comes from Play Store. The
 * button's request names the package it asks as ({@link #ASK_AS_PACKAGE}); a call from this app's own
 * screens (never from a virtual app) then carries the uid that package has inside the container, exactly as
 * a call from Play Store itself does.
 */
public final class AuthenticatorCaller {
    private static final String TAG = "BlackBoxAccounts";

    /**
     * A key of the options of a request for a token: the package the request is made as. Only honoured for a
     * call from this app's own screens, and always removed from the options before they go on.
     */
    public static final String ASK_AS_PACKAGE = "bgRunnerAskAsPackage";

    private static volatile boolean sLogged;
    private static volatile boolean sLoggedTest;

    private AuthenticatorCaller() {
    }

    /**
     * The uid to put into a request for a token, whose options may name a package to ask as (see
     * {@link #ASK_AS_PACKAGE}). A call from a virtual app is told apart by its process as always, and the key is
     * ignored; a call from this app's own screens asks as the package it names if the container has it.
     * Never throws.
     */
    public static int uidOfCaller(Bundle options) {
        String asPackage = null;
        try {
            if (options != null) {
                asPackage = options.getString(ASK_AS_PACKAGE);
                if (asPackage != null) {
                    options.remove(ASK_AS_PACKAGE);
                }
            }
        } catch (Throwable e) {
            asPackage = null;
        }
        int real = Binder.getCallingUid();
        int inside = uidInsideContainer(Binder.getCallingPid());
        if (inside > 0 && inside != real) {
            return uidOfCaller();
        }
        if (asPackage != null && inside <= 0 && real == Process.myUid()) {
            int appId = appIdOf(asPackage);
            if (appId > 0) {
                if (!sLoggedTest) {
                    sLoggedTest = true;
                    Log.i(TAG, "a test request from this app's screens asks as " + asPackage + " (uid " + appId
                            + " inside the container)");
                }
                return appId;
            }
        }
        return uidOfCaller();
    }

    /** The uid a package has inside the container, or -1 if the container does not have it. */
    static int appIdOf(String packageName) {
        try {
            return BPackageManagerService.get().getAppId(packageName);
        } catch (Throwable e) {
            return -1;
        }
    }

    /** The uid to put into the request for the call being handled. Never throws. */
    public static int uidOfCaller() {
        int real = Binder.getCallingUid();
        int inside = uidInsideContainer(Binder.getCallingPid());
        if (inside > 0 && inside != real) {
            if (!sLogged) {
                sLogged = true;
                Log.i(TAG, "requests to sign-in services now carry the uid the calling virtual app has inside "
                        + "the container (" + inside + " instead of " + real + ")");
            }
            return inside;
        }
        return real;
    }

    /** The uid the virtual app that runs the process has inside the container, or -1 for any other caller. */
    static int uidInsideContainer(int pid) {
        try {
            if (pid <= 0) {
                return -1;
            }
            ProcessRecord record = BProcessManagerService.get().findProcessByPid(pid);
            return record == null || record.buid <= 0 ? -1 : record.buid;
        } catch (Throwable e) {
            // the list of processes changed while it was read: the request keeps the real uid
            return -1;
        }
    }
}
