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

import top.niunaijun.blackbox.fake.frameworks.PlayLoginCheck;

/**
 * The boxes of the "Check Play Store login" button: one with the progress while the check runs, and one with
 * the result in plain words that can be photographed (a screenshot is enough) or copied.
 */
public final class PlayLoginDialogs implements PlayLoginCheck.Listener {
    private final Activity activity;
    private final int userId;

    private PlayLoginCheck.Handle handle;
    private AlertDialog progress;
    private AlertDialog result;
    private boolean ended;

    private PlayLoginDialogs(Activity activity, int userId) {
        this.activity = activity;
        this.userId = userId;
    }

    /** Runs the check in the given space and shows what it finds. */
    public static void show(Activity activity, int userId) {
        new PlayLoginDialogs(activity, userId).begin();
    }

    private void begin() {
        showProgress("Starting the check...");
        handle = PlayLoginCheck.start(userId, this);
    }

    // ------------------------------------------------------------------ what the check reports

    @Override
    public void onProgress(String step) {
        if (ended || progress == null || gone()) {
            return;
        }
        progress.setMessage(step);
    }

    @Override
    public void onDone(String report, String firstSentence) {
        if (ended) {
            return;
        }
        ended = true;
        dismiss(progress);
        progress = null;
        showResult(report);
    }

    // ------------------------------------------------------------------ the boxes

    private void showProgress(String text) {
        if (gone()) {
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(activity);
        builder.setTitle("Play Store login check");
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

    /** The result at the top of a scrollable box, so the first screenshot has it; a button copies it all. */
    private void showResult(final String report) {
        if (gone()) {
            return;
        }
        dismiss(result);
        AlertDialog.Builder builder = new AlertDialog.Builder(activity);
        builder.setTitle("Play Store login check");
        Context context = builder.getContext();

        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), 0);

        TextView hint = new TextView(context);
        hint.setText("Send Claude a screenshot of this (scroll for the rest), or tap Copy details and paste it.");
        hint.setTextSize(14);
        box.addView(hint, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        ScrollView scroll = new ScrollView(context);
        TextView body = new TextView(context);
        body.setText(report);
        body.setTypeface(Typeface.MONOSPACE);
        body.setTextSize(10.5f);
        body.setTextIsSelectable(true);
        body.setPadding(0, dp(12), 0, dp(8));
        scroll.addView(body);
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(320));
        scrollParams.topMargin = dp(8);
        box.addView(scroll, scrollParams);
        builder.setView(box);

        // The first button is wired up after show(), so that copying does not close the box.
        builder.setPositiveButton("Copy details", null);
        builder.setNegativeButton("Close", null);
        builder.setNeutralButton("Run again", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                PlayLoginDialogs.show(activity, userId);
            }
        });
        AlertDialog dialog = builder.create();
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                copy(report);
            }
        });
        result = dialog;
    }

    // ------------------------------------------------------------------ helpers

    private void copy(String text) {
        try {
            ClipboardManager clipboard = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard != null) {
                clipboard.setPrimaryClip(ClipData.newPlainText("Play Store login check", text));
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
