package top.niunaijun.blackboxa.view.gms;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Typeface;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.fake.frameworks.GoogleSignIn;

/**
 * The boxes shown while Google sign-in runs: one with the progress, one when Google's screen is
 * open, and, when something goes wrong, one with the reason and a report to copy. Both places that
 * start a sign-in use this, so they behave the same.
 */
public final class GoogleSignInDialogs implements GoogleSignIn.Listener {
    private static final String PLAY_STORE = "com.android.vending";

    private final Activity activity;
    private final int userId;
    private final Runnable afterSignIn;

    private GoogleSignIn.Handle handle;
    private AlertDialog progress;
    private AlertDialog other;
    private boolean ended;

    private GoogleSignInDialogs(Activity activity, int userId, Runnable afterSignIn) {
        this.activity = activity;
        this.userId = userId;
        this.afterSignIn = afterSignIn;
    }

    /**
     * Starts a Google sign-in in the given space and shows its progress.
     *
     * @param afterSignIn runs once the account was added (may be null)
     */
    public static void show(Activity activity, int userId, Runnable afterSignIn) {
        new GoogleSignInDialogs(activity, userId, afterSignIn).begin();
    }

    private void begin() {
        showProgress("Starting the sign-in...");
        handle = GoogleSignIn.start(userId, this);
    }

    // ------------------------------------------------------------------ what the sign-in reports

    @Override
    public void onProgress(String step) {
        if (ended || progress == null || gone()) {
            return;
        }
        progress.setMessage(step);
    }

    @Override
    public void onScreenOpened() {
        if (ended) {
            return;
        }
        dismissAll();
        showOpened();
    }

    @Override
    public void onSignedIn(String accountName) {
        if (ended) {
            return;
        }
        ended = true;
        dismissAll();
        Toast.makeText(activity, "Signed in as " + accountName, Toast.LENGTH_LONG).show();
        if (afterSignIn != null) {
            afterSignIn.run();
        }
    }

    @Override
    public void onFailed(String message, String details) {
        if (ended) {
            return;
        }
        ended = true;
        dismissAll();
        showReport("Google sign-in did not finish", message, details);
    }

    // ------------------------------------------------------------------ the boxes

    private void showProgress(String text) {
        if (gone()) {
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(activity);
        builder.setTitle("Google sign-in");
        builder.setMessage(text);
        builder.setCancelable(false);
        builder.setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                ended = true;
                if (handle != null) {
                    handle.cancel();
                }
            }
        });
        progress = builder.create();
        progress.show();
    }

    /** Google's screen is in front. If it never shows, the person can still get the report. */
    private void showOpened() {
        if (gone()) {
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(activity);
        builder.setTitle("Google sign-in");
        builder.setMessage("Google's sign-in screen was opened. Finish signing in there.\n\n"
                + "If you cannot see it, or it closes by itself, tap Show report.");
        builder.setPositiveButton("Show report", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                collectReport();
            }
        });
        // Closing the box does not stop the sign-in: the app keeps watching for the account.
        builder.setNegativeButton("Close", null);
        other = builder.create();
        other.show();
    }

    private void collectReport() {
        if (handle == null) {
            return;
        }
        Toast.makeText(activity, "Collecting the report...", Toast.LENGTH_SHORT).show();
        handle.requestReport(new GoogleSignIn.ReportListener() {
            @Override
            public void onReport(String report) {
                if (ended) {
                    return;
                }
                showReport("Google sign-in report",
                        "Send this to Claude if the sign-in screen did not appear or closed by itself.",
                        report);
            }
        });
    }

    /** A reason on top, the report below it in a scrollable box, and a button that copies it. */
    private void showReport(String title, String message, final String details) {
        if (gone()) {
            return;
        }
        dismiss(other);
        AlertDialog.Builder builder = new AlertDialog.Builder(activity);
        builder.setTitle(title);
        Context context = builder.getContext();

        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), 0);

        TextView reason = new TextView(context);
        reason.setText(message + "\n\nTap Copy details and paste them into your chat with Claude.");
        reason.setTextSize(15);
        box.addView(reason, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        ScrollView scroll = new ScrollView(context);
        TextView body = new TextView(context);
        body.setText(details);
        body.setTypeface(Typeface.MONOSPACE);
        body.setTextSize(10.5f);
        body.setTextIsSelectable(true);
        body.setPadding(0, dp(12), 0, dp(8));
        scroll.addView(body);
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(220));
        scrollParams.topMargin = dp(8);
        box.addView(scroll, scrollParams);
        builder.setView(box);

        // The first button is wired up after show(), so that copying does not close the box.
        builder.setPositiveButton("Copy details", null);
        builder.setNegativeButton("Close", null);
        if (playStoreInstalled()) {
            builder.setNeutralButton("Open Play Store", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    try {
                        BlackBoxCore.get().launchApk(PLAY_STORE, userId);
                    } catch (Throwable ignored) {
                        // nothing more to do here
                    }
                }
            });
        }
        AlertDialog dialog = builder.create();
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                copy(details);
            }
        });
        other = dialog;
    }

    // ------------------------------------------------------------------ helpers

    private boolean playStoreInstalled() {
        try {
            return BlackBoxCore.get().isInstalled(PLAY_STORE, userId);
        } catch (Throwable e) {
            return false;
        }
    }

    private void copy(String text) {
        try {
            ClipboardManager clipboard = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard != null) {
                clipboard.setPrimaryClip(ClipData.newPlainText("Google sign-in report", text));
                Toast.makeText(activity, "Copied. Paste it into your chat with Claude.", Toast.LENGTH_LONG).show();
                return;
            }
        } catch (Throwable ignored) {
            // falls through to the message below
        }
        Toast.makeText(activity, "Could not copy. Press and hold the text to select it.", Toast.LENGTH_LONG).show();
    }

    private boolean gone() {
        return activity.isFinishing() || activity.isDestroyed();
    }

    private void dismissAll() {
        dismiss(progress);
        progress = null;
        dismiss(other);
        other = null;
    }

    private static void dismiss(AlertDialog dialog) {
        try {
            if (dialog != null && dialog.isShowing()) {
                dialog.dismiss();
            }
        } catch (Throwable ignored) {
            // the screen behind it may already be gone
        }
    }

    private int dp(int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}
