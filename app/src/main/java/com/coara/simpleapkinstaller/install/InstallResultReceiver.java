package com.coara.simpleapkinstaller.install;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.os.Build;

import com.coara.simpleapkinstaller.util.FileUtils;

import java.io.File;

public final class InstallResultReceiver extends BroadcastReceiver {
    @Override
    @SuppressWarnings("deprecation")
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        int sessionId = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1);
        String message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
        String packageName = intent.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME);

        Intent confirm = null;
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            confirm = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                    ? intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent.class)
                    : (Intent) intent.getParcelableExtra(Intent.EXTRA_INTENT);
        }
        InstallEvents.Event event = new InstallEvents.Event(sessionId, status, message, packageName, confirm);
        InstallStateStore.recordEvent(context, event);
        boolean delivered = InstallEvents.publish(event);
        if (status != PackageInstaller.STATUS_PENDING_USER_ACTION && !delivered) {
            InstallStateStore.State state = InstallStateStore.read(context);
            String path = intent.getStringExtra(InstallStateStore.EXTRA_JOB_PATH);
            boolean hasObb = intent.getBooleanExtra(InstallStateStore.EXTRA_HAS_OBB, false);
            if (state != null && state.jobDir != null) {
                path = state.jobDir.getAbsolutePath();
                hasObb = state.hasObb;
            }
            if (path != null && !path.isEmpty()) cleanupDetachedJob(new File(path), status == PackageInstaller.STATUS_SUCCESS && hasObb);
        }
    }

    private static void cleanupDetachedJob(File jobDir, boolean preserveSource) {
        if (jobDir == null) return;
        InstallStateStore.clearActiveJob(jobDir);
        if (!jobDir.isDirectory()) return;
        if (!preserveSource) {
            FileUtils.deleteRecursive(jobDir);
            return;
        }
        File[] children = jobDir.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (!"source.bin".equals(child.getName())) FileUtils.deleteRecursive(child);
        }
    }
}
