package top.niunaijun.blackbox.core.env;

import android.os.storage.StorageVolume;
import android.util.Log;

import java.io.File;

import black.android.os.storage.BRStorageVolume;
import top.niunaijun.blackbox.utils.compat.BuildCompat;

/**
 * Points the "phone storage" an app is told about at the container's own copy of it.
 *
 * Every path an Android app uses for its files on shared storage (getExternalFilesDir, getObbDir,
 * Environment.getExternalStorageDirectory, ...) comes from the list of storage volumes the system
 * returns. The container is meant to swap the path in that list for its own folder, so a container
 * app only ever sees paths it is allowed to use. When that swap did not happen the app was handed the
 * phone's real path (".../Android/data/<app>/files"). Java file calls were still redirected, but
 * code that opens files itself, such as the native code of games, was not, and Android refuses it
 * access to another app's data folder. GTA San Andreas then crashed right after loading, because the
 * first file it wanted to write did not open.
 *
 * Used in two places: by the container's storage service, and as a fallback inside the app process
 * when the service could not answer.
 */
public final class ExternalVolumes {
    private static final String TAG = "ExternalVolumes";
    private static boolean sLogged;

    private ExternalVolumes() {
    }

    /**
     * @param volumes       what the system's StorageManager returned
     * @param virtualUserId the container space the app runs in
     * @return the same volumes with their paths changed; anything that is not a volume list is returned untouched
     */
    public static Object redirect(Object volumes, int virtualUserId) {
        if (volumes instanceof StorageVolume[]) {
            return redirect((StorageVolume[]) volumes, virtualUserId);
        }
        return volumes;
    }

    public static StorageVolume[] redirect(StorageVolume[] volumes, int virtualUserId) {
        if (volumes == null) {
            return null;
        }
        try {
            File root = BEnvironment.getExternalUserDir(virtualUserId);
            for (StorageVolume volume : volumes) {
                if (volume == null) {
                    continue;
                }
                BRStorageVolume.get(volume)._set_mPath(root);
                if (BuildCompat.isPie()) {
                    BRStorageVolume.get(volume)._set_mInternalPath(root);
                }
            }
            if (!sLogged) {
                sLogged = true;
                Log.i(TAG, "storage volumes now point at the container folder: " + root
                        + " (" + volumes.length + " volume(s), space " + virtualUserId + ")");
            }
        } catch (Throwable t) {
            Log.w(TAG, "could not point the storage volumes at the container folder", t);
        }
        return volumes;
    }
}
