package top.niunaijun.blackbox.fake.service;

import java.lang.reflect.Method;

/**
 * What the container answers when Android's activity service refuses a call of a virtual app.
 *
 * <p>The engine already caught the SecurityException that Android throws when an app asks for something
 * it may not (for example to send a broadcast to another Android user) and answered "no" instead, but it
 * had three flaws. It only recognised the bare exception: when one of the container's own hooks made the
 * call, the refusal came wrapped, the engine took it for an unknown error, ran the whole call a second
 * time and then let the app crash with it (Google Play services did, right after it started, on a phone
 * whose container app runs in a second Android user). Its answers did not always have the type the method
 * returns, for example "false" for a method that returns a number, which Java turns into a crash of its
 * own. And a method that returns a primitive got "null", which is a crash as well.
 *
 * <p>Here a refusal, bare or wrapped, is answered with the "nothing happened" value of the type the method
 * returns (0, false, null), and any answer that does not fit the type is replaced by it.
 */
public final class ActivityServiceAnswers {
    private ActivityServiceAnswers() {
    }

    /** True when the error is, or has as one of its causes, a SecurityException (Android's "not allowed"). */
    public static boolean isRefusal(Throwable error) {
        return UserServiceAnswers.refusalIn(error) != null;
    }

    /** The "nothing happened" answer in the type the method returns. */
    public static Object defaultFor(Method method) {
        Class<?> type = method.getReturnType();
        if (type == Void.TYPE) {
            return null;
        }
        if (type == Boolean.TYPE) {
            // stopping or unbinding something that is not there is a success, anything else is not
            String name = method.getName();
            return Boolean.valueOf(name.startsWith("stop") || name.startsWith("unbind"));
        }
        if (type == Integer.TYPE) {
            return Integer.valueOf(0);
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
        return null;
    }

    /** The result itself when it fits the type the method returns, otherwise the default answer. */
    public static Object fit(Method method, Object result) {
        Class<?> type = method.getReturnType();
        if (type == Void.TYPE) {
            return null;
        }
        if (type.isPrimitive()) {
            return fitsPrimitive(type, result) ? result : defaultFor(method);
        }
        return result == null || type.isInstance(result) ? result : null;
    }

    private static boolean fitsPrimitive(Class<?> type, Object value) {
        if (type == Boolean.TYPE) {
            return value instanceof Boolean;
        }
        if (type == Integer.TYPE) {
            return value instanceof Integer;
        }
        if (type == Long.TYPE) {
            return value instanceof Long;
        }
        if (type == Short.TYPE) {
            return value instanceof Short;
        }
        if (type == Byte.TYPE) {
            return value instanceof Byte;
        }
        if (type == Float.TYPE) {
            return value instanceof Float;
        }
        if (type == Double.TYPE) {
            return value instanceof Double;
        }
        if (type == Character.TYPE) {
            return value instanceof Character;
        }
        return false;
    }
}
