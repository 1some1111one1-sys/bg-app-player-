package top.niunaijun.blackbox.fake.service;

import android.os.Build;
import android.util.Log;

import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.utils.compat.ContextCompat;

/**
 * Asking a content provider of the real system (Google's providers) as a virtual app.
 *
 * <p>Since Android 12 every call to a content provider carries an {@code AttributionSource}: who is asking,
 * as a uid and a package name. The provider's process compares the uid in it with the uid Android reports
 * as the caller and refuses the call when they differ ("Calling uid: 10493 doesn't match source uid:
 * 10003"). All virtual apps are one real app to Android, so the real uid of the caller is this app's, while
 * the source made by a virtual app's context holds the made-up uid the container tells the app it has
 * (10003 for Google Play Store, 10004 for Google Play services, ...). The container's wrapper around such a
 * provider tried to correct the source, but it looked for fields that do not exist on Android 12 and newer,
 * corrected nothing and then answered the refusal with an empty result.
 *
 * <p>The effect was seen in Google Play Store: its first questions to Google's services provider (the one
 * that holds the device id, "android_id") were all refused, it logged "Unexpected android-id = 0" and
 * "Unable to fetch checkin consistency token", its start-up stopped half way, and the parts of the app that
 * were never set up crashed later ("Cannot cast ... to ...").
 *
 * <p>Here the source of such a call is given the identity Android knows this app by, this app's real uid
 * and package name, with the container's own helper that the wrapper around the settings, media and
 * telephony providers already uses for the same purpose. Only calls to providers of the real system are
 * changed (their wrapper carries this app's package name); providers of virtual apps keep what they get,
 * because the receiving process of a virtual app tolerates the difference (see {@link CallingGuest}).
 * Android 11 and older have no attribution source and are not touched.
 */
public final class RealProviderSource {
    private static final String TAG = "BlackBoxProvider";
    private static final String ATTRIBUTION_SOURCE = "android.content.AttributionSource";

    private static volatile boolean sLogged;

    private RealProviderSource() {
    }

    /**
     * Corrects every AttributionSource among the arguments of a call to a provider. {@code wrapperPkg} is
     * the package name the wrapper around the provider was made with: this app's own for a provider of the
     * real system, the virtual app's for a provider of a virtual app.
     *
     * @return true when at least one source was corrected. Never throws: when the correction fails the
     *         call goes on as it was.
     */
    public static boolean fix(String wrapperPkg, Object[] args) {
        if (Build.VERSION.SDK_INT < 31 || args == null || !isRealSystemProvider(wrapperPkg)) {
            return false;
        }
        boolean changed = false;
        for (int i = 0; i < args.length; i++) {
            Object arg = args[i];
            if (arg == null || !ATTRIBUTION_SOURCE.equals(arg.getClass().getName())) {
                continue;
            }
            try {
                ContextCompat.fixAttributionSourceState(arg, BlackBoxCore.getHostUid());
                changed = true;
            } catch (Throwable e) {
                // leave this source as it is: the provider answers it the way it did before
            }
        }
        if (changed && !sLogged) {
            sLogged = true;
            Log.i(TAG, "calls to providers of the real system now carry this app's uid and package name, "
                    + "the way an ordinary app's calls do");
        }
        return changed;
    }

    private static boolean isRealSystemProvider(String wrapperPkg) {
        try {
            return wrapperPkg != null && wrapperPkg.equals(BlackBoxCore.getHostPkg());
        } catch (Throwable e) {
            return false;
        }
    }
}
