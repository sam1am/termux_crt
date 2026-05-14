package com.termux.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Environment;
import android.system.Os;
import android.view.WindowManager;

import com.termux.shared.errors.Error;
import com.termux.shared.file.FileUtils;
import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.TermuxUtils;
import com.termux.shared.termux.crash.TermuxCrashUtils;

import java.io.File;

/**
 * Replacement for upstream Termux's bootstrap installer. Termux CRT does not ship its own
 * bootstrap; it relies on a separately installed Termux APK (signed with the same dev keystore,
 * declared with {@code sharedUserId="com.termux"}) and reads {@code /data/data/com.termux/files/}
 * directly.
 *
 * <p>This class only verifies that the installed Termux's $PREFIX is present and surfaces a
 * dialog if it isn't.
 */
final class TermuxInstaller {

    private static final String LOG_TAG = "TermuxInstaller";

    private static final String TERMUX_RELEASES_URL = "https://github.com/termux/termux-app/releases";

    /** Verify that the installed Termux's $PREFIX is reachable; otherwise prompt the user to install Termux. */
    static void setupBootstrapIfNeeded(final Activity activity, final Runnable whenDone) {
        if (FileUtils.directoryFileExists(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH, true)) {
            whenDone.run();
            return;
        }

        Logger.logError(LOG_TAG, "Termux $PREFIX not found at " + TermuxConstants.TERMUX_PREFIX_DIR_PATH
            + " — the official Termux app must be installed (from GitHub releases, not F-Droid) first.");

        activity.runOnUiThread(() -> {
            try {
                new AlertDialog.Builder(activity)
                    .setTitle("Termux not installed")
                    .setMessage("Termux CRT is an extension that runs on top of the official Termux app. "
                        + "Install Termux from GitHub releases (the F-Droid build will not work — its "
                        + "signing key does not match), then re-open this app.")
                    .setCancelable(false)
                    .setNegativeButton("Quit", (dialog, which) -> {
                        dialog.dismiss();
                        activity.finish();
                    })
                    .setPositiveButton("Open releases page", (dialog, which) -> {
                        dialog.dismiss();
                        try {
                            activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(TERMUX_RELEASES_URL)));
                        } catch (Exception e) {
                            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to open Termux releases page", e);
                        }
                        activity.finish();
                    })
                    .show();
            } catch (WindowManager.BadTokenException e) {
                // Activity already gone — ignore.
            }
        });
    }

    static void setupStorageSymlinks(final Context context) {
        final String LOG_TAG = "termux-storage";
        final String title = TermuxConstants.TERMUX_APP_NAME + " Setup Storage Error";

        Logger.logInfo(LOG_TAG, "Setting up storage symlinks.");

        new Thread() {
            public void run() {
                try {
                    Error error;
                    File storageDir = TermuxConstants.TERMUX_STORAGE_HOME_DIR;

                    error = FileUtils.clearDirectory("~/storage", storageDir.getAbsolutePath());
                    if (error != null) {
                        Logger.logErrorAndShowToast(context, LOG_TAG, error.getMessage());
                        Logger.logErrorExtended(LOG_TAG, "Setup Storage Error\n" + error.toString());
                        TermuxCrashUtils.sendCrashReportNotification(context, LOG_TAG, title, null,
                            "## " + title + "\n\n" + Error.getErrorMarkdownString(error),
                            true, false, TermuxUtils.AppInfoMode.TERMUX_PACKAGE, true);
                        return;
                    }

                    File sharedDir = Environment.getExternalStorageDirectory();
                    Os.symlink(sharedDir.getAbsolutePath(), new File(storageDir, "shared").getAbsolutePath());

                    File documentsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS);
                    Os.symlink(documentsDir.getAbsolutePath(), new File(storageDir, "documents").getAbsolutePath());

                    File downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                    Os.symlink(downloadsDir.getAbsolutePath(), new File(storageDir, "downloads").getAbsolutePath());

                    File dcimDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM);
                    Os.symlink(dcimDir.getAbsolutePath(), new File(storageDir, "dcim").getAbsolutePath());

                    File picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES);
                    Os.symlink(picturesDir.getAbsolutePath(), new File(storageDir, "pictures").getAbsolutePath());

                    File musicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC);
                    Os.symlink(musicDir.getAbsolutePath(), new File(storageDir, "music").getAbsolutePath());

                    File moviesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES);
                    Os.symlink(moviesDir.getAbsolutePath(), new File(storageDir, "movies").getAbsolutePath());

                    File podcastsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PODCASTS);
                    Os.symlink(podcastsDir.getAbsolutePath(), new File(storageDir, "podcasts").getAbsolutePath());

                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                        File audiobooksDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_AUDIOBOOKS);
                        Os.symlink(audiobooksDir.getAbsolutePath(), new File(storageDir, "audiobooks").getAbsolutePath());
                    }

                    File[] dirs = context.getExternalFilesDirs(null);
                    if (dirs != null && dirs.length > 0) {
                        for (int i = 0; i < dirs.length; i++) {
                            File dir = dirs[i];
                            if (dir == null) continue;
                            String symlinkName = "external-" + i;
                            Os.symlink(dir.getAbsolutePath(), new File(storageDir, symlinkName).getAbsolutePath());
                        }
                    }

                    dirs = context.getExternalMediaDirs();
                    if (dirs != null && dirs.length > 0) {
                        for (int i = 0; i < dirs.length; i++) {
                            File dir = dirs[i];
                            if (dir == null) continue;
                            String symlinkName = "media-" + i;
                            Os.symlink(dir.getAbsolutePath(), new File(storageDir, symlinkName).getAbsolutePath());
                        }
                    }

                    Logger.logInfo(LOG_TAG, "Storage symlinks created successfully.");
                } catch (Exception e) {
                    Logger.logErrorAndShowToast(context, LOG_TAG, e.getMessage());
                    Logger.logStackTraceWithMessage(LOG_TAG, "Setup Storage Error: Error setting up link", e);
                    TermuxCrashUtils.sendCrashReportNotification(context, LOG_TAG, title, null,
                        "## " + title + "\n\n" + Logger.getStackTracesMarkdownString(null, Logger.getStackTracesStringArray(e)),
                        true, false, TermuxUtils.AppInfoMode.TERMUX_PACKAGE, true);
                }
            }
        }.start();
    }

}
