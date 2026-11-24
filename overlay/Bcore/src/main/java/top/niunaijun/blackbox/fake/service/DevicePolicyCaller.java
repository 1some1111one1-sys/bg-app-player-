package top.niunaijun.blackbox.fake.service;

import android.util.Log;

import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import top.niunaijun.blackbox.utils.MethodParameterUtils;

/**
 * Questions of virtual apps to Android's device policy service ("is account management turned off?").
 *
 * <p>Since Android 15 many of these questions carry the asking app's package name, and the service checks
 * that the package belongs to the caller: "Caller with uid 10493 is not com.google.android.gms". Inside the
 * container the caller is this app (its uid) while the package name is the virtual app's, so the check
 * failed for every such question. Google Play services asks one of them, whether account management is
 * turned off, before it adds or chooses a Google account, and its account chooser died with the refusal
 * (the same question sits on the way into its sign-in screen). Android 14 and older do not ask for the
 * package, which is why the same container worked on a phone with Android 14.
 *
 * <p>Here every package name of a virtual app among the arguments is replaced by this app's own, which is
 * the package the caller really is. A question that is still refused with a SecurityException is answered
 * with "nothing" (no, none, zero, an empty list) when it only asks (get..., is..., has..., can...), because
 * nobody manages the phone inside the container. A change (set..., add..., remove...) stays refused.
 */
public final class DevicePolicyCaller {
    private static final String TAG = "BlackBoxAccounts";

    private static final Set<String> LOGGED = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

    private DevicePolicyCaller() {
    }

    /** Replaces the package name of a virtual app in the arguments by this app's package name. */
    public static void useHostPackage(Object[] args) {
        try {
            MethodParameterUtils.replaceAllAppPkg(args);
        } catch (Throwable e) {
            // the call goes on as it was; the system's answer decides
        }
    }

    /** True when the system refused a question that only asks. */
    public static boolean canAnswerHarmlessly(Method method, Throwable error) {
        return method != null && UserServiceAnswers.isQuery(method) && UserServiceAnswers.refusalIn(error) != null;
    }

    /** "Nothing" in the type the method returns: an empty array or list, 0, false, or null. */
    public static Object harmlessAnswer(Method method, Throwable error) {
        if (LOGGED.size() < 40 && LOGGED.add(method.getName())) {
            SecurityException refusal = UserServiceAnswers.refusalIn(error);
            Log.w(TAG, "device policy: Android refused " + method.getName() + " ("
                    + (refusal == null ? "?" : refusal.getMessage()) + "), answering with nothing");
        }
        Class<?> type = method.getReturnType();
        if (type.isArray()) {
            return Array.newInstance(type.getComponentType(), 0);
        }
        if (type == List.class) {
            return new ArrayList<Object>();
        }
        return ActivityServiceAnswers.defaultFor(method);
    }
}
