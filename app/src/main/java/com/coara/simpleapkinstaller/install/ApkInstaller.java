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
        default void onSessionCreated(int sessionId) {}
        default void onCommitStarted() {}
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
        progress.onSessionCreated(sessionId);
        PackageInstaller.Session session = null;
        try {
            session = installer.openSession(sessionId);

            long written = 0L;
            int index = 0;
            for (File apk : apks) {
                String name = apk.equals(base) ? "base.apk" : "split_" + (index++) + ".apk";
                long length = apk.length();
                try (InputStream in = new FileInputStream(apk);
                     OutputStream out = session.openWrite(name, 0, length)) {
                    final long offset = written;
                    final long all = Math.max(1L, total);
                    FileUtils.copy(in, out, done -> {
                        int percent = (int) ((offset + done) * 100L / all);
                        progress.onProgress(Math.min(percent, 100));
                    });
                    session.fsync(out);
                }
                written += length;
                session.setStagingProgress(Math.min(1f, (float) written / Math.max(1L, total)));
            }

            Intent callback = new Intent(context, InstallResultReceiver.class);
            callback.setPackage(context.getPackageName());
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) flags |= PendingIntent.FLAG_MUTABLE;
            PendingIntent pending = PendingIntent.getBroadcast(context, sessionId, callback, flags);

            session.commit(pending.getIntentSender());
            session.close();
            session = null;
            progress.onCommitStarted();
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
