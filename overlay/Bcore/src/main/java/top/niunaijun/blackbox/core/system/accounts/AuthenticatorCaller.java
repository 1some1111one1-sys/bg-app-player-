package top.niunaijun.blackbox.core.system.accounts;

import android.os.Binder;
import android.util.Log;

import top.niunaijun.blackbox.core.system.BProcessManagerService;
import top.niunaijun.blackbox.core.system.ProcessRecord;

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
 */
public final class AuthenticatorCaller {
    private static final String TAG = "BlackBoxAccounts";

    private static volatile boolean sLogged;

    private AuthenticatorCaller() {
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
