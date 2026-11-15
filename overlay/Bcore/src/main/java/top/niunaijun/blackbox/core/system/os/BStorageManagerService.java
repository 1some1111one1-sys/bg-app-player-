package top.niunaijun.blackbox.core.system.os;

import android.net.Uri;
import android.os.Process;
import android.os.RemoteException;
import android.os.storage.StorageVolume;
import android.util.Log;

import java.io.File;

import black.android.os.storage.BRStorageManager;
import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.core.env.ExternalVolumes;
import top.niunaijun.blackbox.core.system.ISystemService;
import top.niunaijun.blackbox.core.system.user.BUserHandle;
import top.niunaijun.blackbox.fake.provider.FileProvider;
import top.niunaijun.blackbox.proxy.ProxyManifest;


public class BStorageManagerService extends IBStorageManagerService.Stub implements ISystemService {
    private static final String TAG = "BStorageManagerService";
    private static final BStorageManagerService sService = new BStorageManagerService();

    public static BStorageManagerService get() {
        return sService;
    }

    public BStorageManagerService() {
    }

    @Override
    public StorageVolume[] getVolumeList(int uid, String packageName, int flags, int userId) throws RemoteException {
        try {
            // Ask for the volumes of the Android user this app really runs in. The engine used to ask
            // for user 0 first as a check. Anywhere but the phone's first user (a second user, a work
            // profile, Xiaomi's Second space) Android refuses that, the whole call gave up, and the
            // container handed apps the phone's real storage paths instead of its own folder.
            StorageVolume[] storageVolumes = BRStorageManager.get()
                    .getVolumeList(BUserHandle.getUserId(Process.myUid()), 0);
            if (storageVolumes == null || storageVolumes.length == 0) {
                return null;
            }
            return ExternalVolumes.redirect(storageVolumes, userId);
        } catch (Throwable e) {
            // The caller then asks the system itself and points the answer at the container folder.
            Log.w(TAG, "could not list the storage volumes", e);
        }
        return null;
    }

    @Override
    public Uri getUriForFile(String file) throws RemoteException {
        return FileProvider.getUriForFile(BlackBoxCore.getContext(), ProxyManifest.getProxyFileProvider(), new File(file));
    }

    @Override
    public void systemReady() {

    }
}
