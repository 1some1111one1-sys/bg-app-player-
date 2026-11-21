package top.niunaijun.blackbox.fake.service;

import android.os.Bundle;
import android.util.Log;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import black.android.content.pm.BRUserInfo;
import top.niunaijun.blackbox.BlackBoxCore;

/**
 * What the container answers when Android's user service refuses a question of a virtual app.
 *
 * <p>Google Play services is a system app on a real phone, so its code asks Android's user service
 * things an ordinary app is not allowed to ask: "who is this user" ({@code getUserInfo}: "You either
 * need MANAGE_USERS, CREATE_USERS, or QUERY_USERS permission to: query user"), and, on a phone whose
 * container app runs in a second Android user, questions about user 0 that the system only answers
 * across users ({@code getProfileType}: "You need INTERACT_ACROSS_USERS, MANAGE_USERS, or QUERY_USERS
 * permission to: check getProfileType"). Inside the container those calls come from this app, which
 * holds none of those permissions, so the system threw a SecurityException into Google's code. Google's
 * sign-in screen (AccountIntroActivity) died with it while it was starting, again and again.
 *
 * <p>The engine's {@code IUserManagerProxy} asks the system first, as it always did, and when the system
 * refuses a question (get..., is..., has..., can...) with a SecurityException it answers here instead,
 * with what a phone nobody manages gives for the user: an ordinary, unrestricted, full user that is not
 * a work profile, not a guest and not a child account. Nothing changes for a question the system
 * answers, and a refused change (set..., create..., remove...) stays refused. A refusal is remembered
 * per method and argument, so the system is not asked again for the same thing.
 */
public final class UserServiceAnswers {
    private static final String TAG = "BlackBoxAccounts";

    // Flags of UserInfo. These values are the same on every Android version since 10; the flags that
    // Android 11 added (system, profile, ...) are left out on purpose, because UserInfo's constructor
    // works out a user type from the flags and refuses some of them.
    private static final int FLAG_PRIMARY = 0x1;
    private static final int FLAG_ADMIN = 0x2;
    private static final int FLAG_INITIALIZED = 0x10;
    private static final int FLAG_FULL = 0x400;

    private static final String TYPE_SYSTEM = "android.os.usertype.full.SYSTEM";
    private static final String TYPE_SECONDARY = "android.os.usertype.full.SECONDARY";
    private static final String OWNER = "Owner";

    private static final int MAX_REMEMBERED = 256;
    private static final int MAX_LOGGED = 40;

    private static final Set<String> REFUSED = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
    private static final Set<String> LOGGED = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

    private UserServiceAnswers() {
    }

    /** The SecurityException in {@code error} or in its causes, or null when the call failed another way. */
    public static SecurityException refusalIn(Throwable error) {
        Throwable current = error;
        for (int depth = 0; current != null && depth < 4; depth++) {
            if (current instanceof SecurityException) {
                return (SecurityException) current;
            }
            current = current.getCause();
        }
        return null;
    }

    /**
     * True for a question (get..., is..., has..., can..., userExists, someUser...) and false for a change
     * (set..., create..., remove..., start..., switch...). Only questions are answered from here: a
     * refused change stays refused, so an app is never told that something happened that did not.
     */
    public static boolean isQuery(Method method) {
        String name = method.getName();
        return name.startsWith("get") || name.startsWith("is") || name.startsWith("has")
                || name.startsWith("can") || name.startsWith("user") || name.startsWith("some");
    }

    /** Name and simple arguments of a call: what a refusal is remembered under. */
    public static String keyOf(Method method, Object[] args) {
        StringBuilder key = new StringBuilder(method.getName());
        if (args != null) {
            for (Object arg : args) {
                if (arg instanceof Number || arg instanceof Boolean || arg instanceof CharSequence) {
                    key.append('|').append(arg);
                }
            }
        }
        return key.length() > 200 ? key.substring(0, 200) : key.toString();
    }

    /** True when the system already refused this very call, so it is not asked again. */
    public static boolean isRefused(String key) {
        return REFUSED.contains(key);
    }

    /** Remembers a refusal and writes one log line per method, so the report can show it. */
    public static void noteRefused(String key, Method method, SecurityException refusal) {
        if (REFUSED.size() < MAX_REMEMBERED) {
            REFUSED.add(key);
        }
        String name = method.getName();
        if (LOGGED.size() < MAX_LOGGED && LOGGED.add(name)) {
            Log.w(TAG, "user service: the system refused " + name + " (" + clip(refusal.getMessage())
                    + "), the container answers it itself");
        }
    }

    /** The answer of a phone nobody manages to this question, in the type the method returns. */
    public static Object answer(Method method, Object[] args) {
        String name = method.getName();
        Class<?> type = method.getReturnType();
        int user = userOf(args);
        try {
            if (type == Void.TYPE) {
                return null;
            }
            if (type == Boolean.TYPE) {
                return Boolean.valueOf(booleanAnswer(name, args));
            }
            if (type == Integer.TYPE) {
                return Integer.valueOf(intAnswer(name, user));
            }
            if (type == Long.TYPE) {
                return Long.valueOf(0L);
            }
            if (type == Short.TYPE) {
                return Short.valueOf((short) 0);
            }
            if (type == Byte.TYPE) {
                return Byte.valueOf((byte) 0);
            }
            if (type == Float.TYPE) {
                return Float.valueOf(0f);
            }
            if (type == Double.TYPE) {
                return Double.valueOf(0d);
            }
            if (type == Character.TYPE) {
                return Character.valueOf((char) 0);
            }
            if (type == String.class) {
                return stringAnswer(name, user);
            }
            if (type == int[].class) {
                return new int[] {user};
            }
            if (type == Bundle.class) {
                return new Bundle();
            }
            if ("android.content.pm.UserInfo".equals(type.getName())) {
                return userInfo(user);
            }
            if (type == List.class) {
                List<Object> list = new ArrayList<Object>();
                if ("getProfiles".equals(name)) {
                    // the user's own profile group: only the user itself
                    Object info = userInfo(user);
                    if (info != null) {
                        list.add(info);
                    }
                }
                return list;
            }
        } catch (Throwable e) {
            Log.w(TAG, "user service: could not answer " + name + " itself (" + e + ")");
        }
        return null;
    }

    private static boolean booleanAnswer(String name, Object[] args) {
        if ("isUserUnlocked".equals(name) || "isUserUnlockingOrUnlocked".equals(name)
                || "isUserRunning".equals(name) || "isUserForeground".equals(name)
                || "isUserVisible".equals(name)) {
            return true;
        }
        if ("isSameProfileGroup".equals(name) && args != null && args.length >= 2
                && args[0] instanceof Integer && args[1] instanceof Integer) {
            return ((Integer) args[0]).intValue() == ((Integer) args[1]).intValue();
        }
        // managed profile, restricted, demo, guest-like, quiet mode, "has restriction ...": none of them
        return false;
    }

    private static int intAnswer(String name, int user) {
        if ("getCredentialOwnerProfile".equals(name) || "getUserSerialNumber".equals(name)
                || "getUserHandle".equals(name)) {
            return user;
        }
        return 0;
    }

    private static String stringAnswer(String name, int user) {
        if ("getProfileType".equals(name)) {
            return user == 0 ? TYPE_SYSTEM : TYPE_SECONDARY;
        }
        if ("getUserName".equals(name)) {
            return OWNER;
        }
        return null;
    }

    /** The user the question is about: its first int argument, or the user this app really runs in. */
    private static int userOf(Object[] args) {
        if (args != null) {
            for (Object arg : args) {
                if (arg instanceof Integer) {
                    return ((Integer) arg).intValue();
                }
            }
        }
        try {
            return BlackBoxCore.getHostUserId();
        } catch (Throwable e) {
            return 0;
        }
    }

    /**
     * A record of an ordinary full user, the owner of the phone, with the given id. UserInfo's
     * constructor works out the user type from the flags and refuses some combinations on some Android
     * versions, so the richest set of flags is tried first and a plainer one after it.
     */
    public static Object userInfo(int id) {
        int[] attempts = {
                FLAG_PRIMARY | FLAG_ADMIN | FLAG_INITIALIZED | FLAG_FULL,
                FLAG_PRIMARY | FLAG_ADMIN | FLAG_INITIALIZED,
                FLAG_PRIMARY
        };
        for (int flags : attempts) {
            Object info = newUserInfo(id, flags);
            if (info != null) {
                return info;
            }
        }
        if (LOGGED.size() < MAX_LOGGED && LOGGED.add("userInfo")) {
            Log.w(TAG, "user service: could not build a user record, answering with none");
        }
        return null;
    }

    private static Object newUserInfo(int id, int flags) {
        try {
            Object made = BRUserInfo.get()._new(id, OWNER, flags);
            if (made != null) {
                return made;
            }
        } catch (Throwable ignored) {
            // the plain way below
        }
        try {
            Constructor<?> constructor = Class.forName("android.content.pm.UserInfo")
                    .getDeclaredConstructor(Integer.TYPE, String.class, Integer.TYPE);
            constructor.setAccessible(true);
            return constructor.newInstance(Integer.valueOf(id), OWNER, Integer.valueOf(flags));
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String clip(String text) {
        if (text == null) {
            return "no message";
        }
        return text.length() > 140 ? text.substring(0, 140) + "..." : text;
    }
}
