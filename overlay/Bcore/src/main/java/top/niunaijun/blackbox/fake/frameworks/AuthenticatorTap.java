package top.niunaijun.blackbox.fake.frameworks;

import android.accounts.AccountManager;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Looper;
import android.os.Parcel;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Log;

import java.util.concurrent.atomic.AtomicInteger;

import top.niunaijun.blackbox.app.BActivityThread;
import top.niunaijun.blackbox.core.system.BProcessManagerService;
import top.niunaijun.blackbox.core.system.ProcessRecord;

/**
 * Sits in front of the sign-in service of Google Play services inside the container and writes down
 * what happens to each request (log tag {@code BlackBoxAccounts}, lines start with
 * "authenticator tap:").
 *
 * Why: Android's account service talks to a sign-in service with "one-way" calls. A one-way call
 * has no answer, so when Google's service throws an error while it handles the request, nobody is
 * ever told, and from outside it looks as if Google simply does not respond. This class sees the
 * request arrive in Google's process, sees whether it ends with an error, and says where it is stuck
 * when it takes long. When the request ends with an error, it also passes that error on as the
 * answer, so the person sees a reason right away instead of waiting.
 *
 * Everything else goes through unchanged: every call is handed to the real service as it came in,
 * with the caller's identity intact, and anything it throws is thrown again.
 *
 * One exception to that: the service only takes requests from a caller that holds the
 * ACCOUNT_MANAGER permission, which in real Android is the system itself. When it refuses a request
 * for that permission, the request is tried once more with the system as the caller (the container
 * has no real system process to call it), see {@link #onTransact} and {@link #forcedCallingUid()}.
 */
public final class AuthenticatorTap extends Binder {
    private static final String TAG = "BlackBoxAccounts";
    private static final String PREFIX = "authenticator tap: ";

    private static final String GMS_PKG = "com.google.android.gms";
    private static final String AUTHENTICATOR_DESCRIPTOR = "android.accounts.IAccountAuthenticator";
    private static final String RESPONSE_DESCRIPTOR = "android.accounts.IAccountAuthenticatorResponse";

    /** IAccountAuthenticator.addAccount is the first method of the interface. */
    private static final int CALL_ADD_ACCOUNT = IBinder.FIRST_CALL_TRANSACTION;
    /** IAccountAuthenticatorResponse: onResult, onRequestContinued, onError (the third). */
    private static final int RESPONSE_ON_ERROR = IBinder.FIRST_CALL_TRANSACTION + 2;

    /** A request still running after this long gets its stack written to the log. */
    private static final long SLOW_AFTER_MS = 3_000;
    private static final long STILL_SLOW_AFTER_MS = 12_000;

    /** Set on the thread that is running a request again as the system (see {@link #onTransact}). */
    private static final ThreadLocal<Boolean> sAsSystem = new ThreadLocal<>();
    /** How many threads are inside such a retry: lets every other lookup skip the thread check. */
    private static final AtomicInteger sAsSystemCount = new AtomicInteger();

    private final IBinder real;
    private final String descriptor;

    private AuthenticatorTap(IBinder real, String descriptor) {
        this.real = real;
        this.descriptor = descriptor;
    }

    /**
     * Called inside the process that hosts a service of the container, with what the service's
     * {@code onBind} returned. Gives back the binder to hand out: the tap for Google's sign-in
     * service, the unchanged binder for everything else. Never throws.
     */
    public static IBinder wrap(Intent intent, ServiceInfo info, IBinder binder) {
        try {
            if (binder == null || intent == null || info == null) {
                return binder;
            }
            if (!AccountManager.ACTION_AUTHENTICATOR_INTENT.equals(intent.getAction())
                    || !GMS_PKG.equals(info.packageName)) {
                return binder;
            }
            String descriptor = null;
            try {
                descriptor = binder.getInterfaceDescriptor();
            } catch (Throwable ignored) {
                // reported below
            }
            String process = BActivityThread.getAppProcessName();
            Log.i(TAG, PREFIX + "connected in process " + process + " (pid " + Process.myPid()
                    + "), interface " + descriptor + ", class " + binder.getClass().getName());
            if (!AUTHENTICATOR_DESCRIPTOR.equals(descriptor)) {
                Log.w(TAG, PREFIX + "not watching it, its interface is not " + AUTHENTICATOR_DESCRIPTOR);
                return binder;
            }
            return new AuthenticatorTap(binder, descriptor);
        } catch (Throwable e) {
            Log.w(TAG, PREFIX + "could not be put in front of the sign-in service: " + e);
            return binder;
        }
    }

    /**
     * For the container's account service: where the process of a sign-in service runs, as text to
     * append to a log line. Empty when it cannot be found.
     */
    public static String whereIs(String packageName, String processName, int userId) {
        try {
            if (packageName == null) {
                return "";
            }
            ProcessRecord record = BProcessManagerService.get().findProcessRecord(packageName,
                    processName == null ? packageName : processName, userId);
            if (record == null) {
                return ", its process is not in the container's list";
            }
            return ", process " + record.processName + " (pid " + record.pid + ")";
        } catch (Throwable e) {
            return "";
        }
    }

    /**
     * For the engine's replacement of {@code Binder.getCallingUid()}: the uid to report instead of the
     * real caller's, or -1 for no change. Only a request that is being run again as the system (see
     * {@link #onTransact}) gets one, and only on its own thread; every other lookup costs one
     * comparison. Never throws.
     */
    public static int forcedCallingUid() {
        try {
            if (sAsSystemCount.get() > 0 && sAsSystem.get() != null) {
                return Process.SYSTEM_UID;
            }
        } catch (Throwable ignored) {
            // a plain uid lookup must never fail because of this
        }
        return -1;
    }

    // ------------------------------------------------------------------------------ the binder

    @Override
    public String getInterfaceDescriptor() {
        return descriptor;
    }

    @Override
    public IInterface queryLocalInterface(String wanted) {
        return real.queryLocalInterface(wanted);
    }

    @Override
    protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
        if (code < IBinder.FIRST_CALL_TRANSACTION || code > IBinder.LAST_CALL_TRANSACTION) {
            // Android's own housekeeping calls (interface name, dump, ...) go straight through.
            return real.transact(code, data, reply, flags);
        }
        final String call = code == CALL_ADD_ACCOUNT ? "addAccount" : "call " + code;
        final long begun = SystemClock.elapsedRealtime();
        String what = "";
        try {
            what = code == CALL_ADD_ACCOUNT ? describeAddAccount(data) : "";
        } catch (Throwable ignored) {
            // the description is a bonus
        }
        Log.i(TAG, PREFIX + call + " received (pid " + Process.myPid() + ", thread "
                + Thread.currentThread().getName() + ") from uid " + Binder.getCallingUid()
                + ", pid " + Binder.getCallingPid() + what);

        final Watchdog watchdog = new Watchdog(call, Thread.currentThread());
        try {
            watchdog.start();
        } catch (Throwable ignored) {
            // no watching, the request itself still goes ahead
        }
        try {
            boolean handled = real.transact(code, data, reply, flags);
            Log.i(TAG, PREFIX + call + " returned after " + (SystemClock.elapsedRealtime() - begun)
                    + " ms without an error");
            return handled;
        } catch (Throwable first) {
            if (!isPermissionRefusal(first)) {
                reportFailure(call, first, data, begun, "");
                throw first;
            }
            // Refused for the ACCOUNT_MANAGER permission: the service wants the system as its caller.
            Log.w(TAG, PREFIX + call + " was refused for android.permission.ACCOUNT_MANAGER after "
                    + (SystemClock.elapsedRealtime() - begun) + " ms (" + first.getMessage()
                    + "), trying again as the system (uid " + Process.SYSTEM_UID + ")");
            try {
                boolean handled = transactAsSystem(code, data, reply, flags);
                Log.i(TAG, PREFIX + call + " returned after " + (SystemClock.elapsedRealtime() - begun)
                        + " ms without an error (second try, as the system)");
                return handled;
            } catch (Throwable second) {
                reportFailure(call, second, data, begun, " (second try, as the system)");
                throw second;
            }
        } finally {
            watchdog.finish();
        }
    }

    /**
     * Runs the call again with the system (uid 1000) as the caller, on this thread only. The
     * data has been read once already; {@code transact} starts reading it from the beginning.
     */
    private boolean transactAsSystem(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
        sAsSystem.set(Boolean.TRUE);
        sAsSystemCount.incrementAndGet();
        try {
            return real.transact(code, data, reply, flags);
        } finally {
            sAsSystemCount.decrementAndGet();
            sAsSystem.remove();
        }
    }

    /**
     * True for the refusal that Android's own sign-in plumbing (not Google's code) throws when the
     * caller does not hold the ACCOUNT_MANAGER permission: a SecurityException that names the
     * permission and was thrown from {@code android.accounts} or {@code android.os.PermissionEnforcer}.
     * Anything else is not a reason to run the request a second time.
     */
    private static boolean isPermissionRefusal(Throwable error) {
        Throwable t = error;
        for (int depth = 0; t != null && depth < 4; depth++, t = t.getCause()) {
            String text = t.getMessage();
            if (!(t instanceof SecurityException) || text == null || !text.contains("ACCOUNT_MANAGER")) {
                continue;
            }
            StackTraceElement[] frames = t.getStackTrace();
            for (int i = 0; i < frames.length && i < 8; i++) {
                String owner = frames[i].getClassName();
                if (owner.startsWith("android.accounts.") || owner.equals("android.os.PermissionEnforcer")) {
                    return true;
                }
            }
        }
        return false;
    }

    // ------------------------------------------------------------------------------ the details

    /** The names of the options of an addAccount request, and who it says is asking. */
    private String describeAddAccount(Parcel data) {
        try {
            data.setDataPosition(0);
            data.enforceInterface(descriptor);
            data.readStrongBinder();            // where the answer goes
            String type = data.readString();    // account type
            data.readString();                  // auth token type
            data.createStringArray();           // required features
            Bundle options = data.readInt() != 0 ? Bundle.CREATOR.createFromParcel(data) : null;
            return ", " + type + ", options " + summarize(options);
        } catch (Throwable e) {
            return ", options could not be read (" + e + ")";
        } finally {
            try {
                data.setDataPosition(0);
            } catch (Throwable ignored) {
                // nothing to restore
            }
        }
    }

    /** Names of the entries; values only for the three that say who is asking. */
    private static String summarize(Bundle options) {
        if (options == null) {
            return "none";
        }
        StringBuilder s = new StringBuilder("[");
        for (String key : options.keySet()) {
            if (s.length() > 1) {
                s.append(", ");
            }
            s.append(key);
            if (AccountManager.KEY_CALLER_UID.equals(key) || AccountManager.KEY_CALLER_PID.equals(key)) {
                s.append('=').append(options.getInt(key, -1));
            } else if (AccountManager.KEY_ANDROID_PACKAGE_NAME.equals(key)) {
                s.append('=').append(options.getString(key));
            }
        }
        return s.append(']').toString();
    }

    private void reportFailure(String call, Throwable e, Parcel data, long begun, String how) {
        try {
            Log.w(TAG, PREFIX + call + " FAILED after " + (SystemClock.elapsedRealtime() - begun)
                    + " ms" + how + ": " + e);
            Throwable t = e;
            for (int depth = 0; t != null && depth < 4; depth++, t = t.getCause()) {
                if (depth > 0) {
                    Log.w(TAG, PREFIX + "  caused by " + t);
                }
                StackTraceElement[] frames = t.getStackTrace();
                for (int i = 0; i < frames.length && i < 6; i++) {
                    Log.w(TAG, PREFIX + "    at " + frames[i]);
                }
            }
            passErrorOn(data, e);
        } catch (Throwable ignored) {
            // reporting must never make things worse
        }
    }

    /**
     * Android's own sign-in plumbing answers the caller with an error when the service fails while
     * it handles a request; a failure before that point (a refused permission, for one) is never
     * answered because the call is one-way. Do the answering here.
     */
    private void passErrorOn(Parcel data, Throwable e) {
        Parcel out = Parcel.obtain();
        try {
            data.setDataPosition(0);
            data.enforceInterface(descriptor);
            IBinder response = data.readStrongBinder();
            if (response == null) {
                return;
            }
            String text = e.getMessage();
            out.writeInterfaceToken(RESPONSE_DESCRIPTOR);
            out.writeInt(AccountManager.ERROR_CODE_REMOTE_EXCEPTION);
            out.writeString("Google Play services refused the request: " + e.getClass().getSimpleName()
                    + (text == null ? "" : ": " + text));
            response.transact(RESPONSE_ON_ERROR, out, null, IBinder.FLAG_ONEWAY);
        } catch (Throwable t) {
            Log.w(TAG, PREFIX + "the error could not be passed on: " + t);
        } finally {
            out.recycle();
            try {
                data.setDataPosition(0);
            } catch (Throwable ignored) {
                // nothing to restore
            }
        }
    }

    /** Writes where a request is stuck when it takes longer than a few seconds. */
    private static final class Watchdog extends Thread {
        private final String call;
        private final Thread target;
        private volatile boolean finished;

        Watchdog(String call, Thread target) {
            super("BlackBoxTap");
            setDaemon(true);
            this.call = call;
            this.target = target;
        }

        void finish() {
            finished = true;
            interrupt();
        }

        @Override
        public void run() {
            try {
                if (!waitFor(SLOW_AFTER_MS)) {
                    return;
                }
                Log.w(TAG, PREFIX + call + " has not returned after " + (SLOW_AFTER_MS / 1000)
                        + " s, this is where it is:");
                dump("the thread handling it", target, 40);
                Thread main = Looper.getMainLooper().getThread();
                if (main != target) {
                    dump("the main thread of this process", main, 25);
                }
                if (!waitFor(STILL_SLOW_AFTER_MS - SLOW_AFTER_MS)) {
                    return;
                }
                Log.w(TAG, PREFIX + call + " has still not returned after " + (STILL_SLOW_AFTER_MS / 1000)
                        + " s, now it is here:");
                dump("the thread handling it", target, 25);
            } catch (Throwable ignored) {
                // a watcher must never get in the way
            }
        }

        /** Waits up to the given time; false when the request ended meanwhile. */
        private boolean waitFor(long millis) {
            long end = SystemClock.elapsedRealtime() + millis;
            while (!finished) {
                long left = end - SystemClock.elapsedRealtime();
                if (left <= 0) {
                    return true;
                }
                try {
                    Thread.sleep(Math.min(left, 200));
                } catch (InterruptedException e) {
                    return false;
                }
            }
            return false;
        }

        private void dump(String label, Thread thread, int max) {
            StackTraceElement[] frames = thread.getStackTrace();
            Log.w(TAG, PREFIX + "  " + label + " ('" + thread.getName() + "') is " + thread.getState());
            for (int i = 0; i < frames.length && i < max; i++) {
                Log.w(TAG, PREFIX + "    at " + frames[i]);
            }
        }
    }
}
