package com.coara.simpleapkinstaller.install;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.os.Build;

import com.coara.simpleapkinstaller.util.FileUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

public final class ApkInstaller {
    public interface Progress {
        void onProgress(int percent) throws IOException;
    }

    private ApkInstaller() {}

    public static int commit(Context context, List<File> apks, File base, String packageName, Progress progress) throws IOException {
        PackageInstaller installer = context.getPackageManager().getPackageInstaller();

        long total = 0L;
        for (File apk : apks) total += apk.length();

        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setSize(total);
        if (packageName != null && !packageName.isEmpty()) params.setAppPackageName(packageName);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            params.setPackageSource(PackageInstaller.PACKAGE_SOURCE_LOCAL_FILE);
        }

        int sessionId = installer.createSession(params);
        PackageInstaller.Session session = null;
        try {
            final PackageInstaller.Session active = installer.openSession(sessionId);
            session = active;

            long written = 0L;
            int index = 0;
            for (File apk : apks) {
                String name = apk.equals(base) ? "base.apk" : "split_" + (index++) + ".apk";
                long length = apk.length();
                try (InputStream in = new FileInputStream(apk);
                     OutputStream out = active.openWrite(name, 0, length)) {
                    final long offset = written;
                    final long all = Math.max(1L, total);
                    final int[] last = {-1};
                    FileUtils.copy(in, out, done -> {
                        int percent = (int) Math.min(100L, (offset + done) * 100L / all);
                        if (percent != last[0]) {
                            last[0] = percent;
                            active.setStagingProgress(percent / 100f);
                            progress.onProgress(percent);
                        }
                    });
                    active.fsync(out);
                }
                written += length;
                active.setStagingProgress(Math.min(1f, (float) written / Math.max(1L, total)));
            }

            Intent callback = new Intent(context, InstallResultReceiver.class);
            callback.setPackage(context.getPackageName());
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) flags |= PendingIntent.FLAG_MUTABLE;
            PendingIntent pending = PendingIntent.getBroadcast(context, sessionId, callback, flags);

            active.commit(pending.getIntentSender());
            return sessionId;
        } catch (IOException | RuntimeException e) {
            if (session != null) {
                try {
                    session.abandon();
                } catch (RuntimeException ignored) {
                }
            } else {
                try {
                    installer.abandonSession(sessionId);
                } catch (RuntimeException ignored) {
                }
            }
            throw e;
        } finally {
            if (session != null) session.close();
        }
    }
}
