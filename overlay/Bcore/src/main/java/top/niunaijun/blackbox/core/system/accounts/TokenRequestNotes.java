package top.niunaijun.blackbox.core.system.accounts;

import android.accounts.Account;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Lines in the log (tag {@code BlackBoxAccounts}) for a request for a login token, which is what Google Play
 * Store and other apps ask the container's account service for: who asked, for what, what the container
 * holds for the account, and what Google Play services answered.
 *
 * <p>Why: Play Store ends on "Authentication is required. You need to sign in to your Google Account", and
 * from the outside that screen looks the same whether Google refused the request, answered with a sign-in
 * screen, or was never asked. These lines tell the three apart. Nothing private is written: no account name,
 * no token (only its length), no value of the account's data (only the names of its keys).
 */
final class TokenRequestNotes {
    private static final String TAG = "BlackBoxAccounts";
    private static final int MAX_KEYS = 200;

    private static final Map<String, Integer> SEEN = new HashMap<String, Integer>();

    private TokenRequestNotes() {
    }

    /** A request for a token arrived: written for the first 3 of each kind, then every 25th. */
    static void request(String callerPackage, String tokenType, int uidSent, boolean customTokens,
            BUserAccounts accounts, Account account) {
        try {
            String type = clip(tokenType, 90);
            boolean saved = false;
            String health;
            try {
                health = health(accounts, account);
                if (accounts != null && account != null && tokenType != null) {
                    synchronized (accounts.lock) {
                        saved = accounts.getAuthToken(account).get(tokenType) != null;
                    }
                }
            } catch (Throwable e) {
                health = "account state not readable (" + e + ")";
            }
            if (!shouldWrite("request|" + callerPackage + "|" + type + "|" + customTokens + "|" + saved)) {
                return;
            }
            Log.i(TAG, describeRequest(callerPackage, type, uidSent, customTokens, saved, health));
        } catch (Throwable ignored) {
            // only a line in the log
        }
    }

    /** Google Play services answered a request for a token. */
    static void answered(String callerPackage, int tokenLength, String screenComponent, String screenAction,
            int errorCode, String errorMessage, Collection<String> keys) {
        try {
            String text = describeAnswer(callerPackage, tokenLength, screenComponent, screenAction, errorCode,
                    errorMessage, keys);
            if (!shouldWrite("answer|" + text.replaceAll("\\d+", "#"))) {
                return;
            }
            Log.i(TAG, text);
        } catch (Throwable ignored) {
            // only a line in the log
        }
    }

    // ---------------------------------------------------------------------------- the text of the lines

    static String describeRequest(String callerPackage, String tokenType, int uidSent, boolean customTokens,
            boolean savedToken, String health) {
        return "token request: app " + callerPackage + ", token type " + tokenType + ", uid sent to Google "
                + uidSent + ", " + (customTokens ? "Google keeps its own tokens" : "the container keeps the tokens")
                + ", saved token for this type: " + (savedToken ? "yes" : "no") + "; " + health;
    }

    static String describeAnswer(String callerPackage, int tokenLength, String screenComponent,
            String screenAction, int errorCode, String errorMessage, Collection<String> keys) {
        String who = "token request of " + callerPackage + " answered: ";
        if (tokenLength > 0) {
            return who + "a token of " + tokenLength + " characters";
        }
        if (screenComponent != null || screenAction != null) {
            return who + "no token, a screen to show instead (" + (screenComponent == null ? "no component"
                    : clip(screenComponent, 100)) + ", action " + clip(screenAction, 80) + ")";
        }
        if (errorCode > 0 || errorMessage != null) {
            return who + "no token, error " + errorCode + ": " + clip(errorMessage, 120);
        }
        return who + "nothing usable (" + (keys == null ? "no answer" : "keys " + new TreeSet<String>(keys)) + ")";
    }

    /** What the container holds for the account, without a name or a value. */
    static String describeAccount(boolean found, String password, Collection<String> userDataKeys,
            Collection<String> tokenTypes, long lastAuthenticatedMillis, long nowMillis) {
        if (!found) {
            return "the account is not in the container";
        }
        StringBuilder out = new StringBuilder("account: ");
        out.append(password == null || password.isEmpty() ? "NO master token"
                : "master token of " + password.length() + " characters");
        out.append(", ").append(userDataKeys == null ? 0 : userDataKeys.size()).append(" data keys");
        if (userDataKeys != null && !userDataKeys.isEmpty()) {
            out.append(' ').append(firstOf(userDataKeys, 12, 40));
        }
        out.append(", ").append(tokenTypes == null ? 0 : tokenTypes.size()).append(" saved tokens");
        if (tokenTypes != null && !tokenTypes.isEmpty()) {
            // a token type is a short address such as the Play Store one: shown whole, it is what we look for
            out.append(' ').append(firstOf(tokenTypes, 6, 90));
        }
        if (lastAuthenticatedMillis > 0 && nowMillis >= lastAuthenticatedMillis) {
            out.append(", last signed in ").append((nowMillis - lastAuthenticatedMillis) / 60000).append(" min ago");
        } else {
            out.append(", last sign-in time not recorded");
        }
        return out.toString();
    }

    static String health(BUserAccounts accounts, Account account) {
        if (accounts == null || account == null) {
            return "no account";
        }
        synchronized (accounts.lock) {
            BAccount stored = accounts.getAccount(account);
            if (stored == null) {
                return describeAccount(false, null, null, null, 0, 0);
            }
            return describeAccount(true, stored.password, new ArrayList<String>(stored.accountUserData.keySet()),
                    new ArrayList<String>(stored.authTokens.keySet()), stored.updateLastAuthenticatedTime,
                    System.currentTimeMillis());
        }
    }

    // ---------------------------------------------------------------------------- limits

    /** True for the first 3 times a kind of line comes up, and then for every 25th. */
    static boolean shouldWrite(String kind) {
        int count;
        synchronized (SEEN) {
            if (SEEN.size() >= MAX_KEYS && !SEEN.containsKey(kind)) {
                SEEN.clear();
            }
            Integer known = SEEN.get(kind);
            count = known == null ? 1 : known + 1;
            SEEN.put(kind, count);
        }
        return count <= 3 || count % 25 == 0;
    }

    /** The first {@code max} names in order, each cut to {@code maxLength} characters: "[a, b, ...]". */
    private static String firstOf(Collection<String> names, int max, int maxLength) {
        TreeSet<String> unique = new TreeSet<String>();
        for (String name : names) {
            if (name != null) {
                unique.add(name);
            }
        }
        List<String> sorted = new ArrayList<String>(unique);
        StringBuilder out = new StringBuilder("[");
        for (int i = 0; i < sorted.size() && i < max; i++) {
            if (i > 0) {
                out.append(", ");
            }
            out.append(clip(sorted.get(i), maxLength));
        }
        if (sorted.size() > max) {
            out.append(", ...");
        }
        return out.append(']').toString();
    }

    private static String clip(String text, int max) {
        if (text == null) {
            return "null";
        }
        return text.length() <= max ? text : text.substring(0, max) + "...";
    }
}
