package com.coara.simpleapkinstaller.install;

import android.content.Context;
import android.content.pm.PackageInfo;

import com.coara.simpleapkinstaller.R;
import com.coara.simpleapkinstaller.model.ApkEntry;
import com.coara.simpleapkinstaller.util.ApkSigner;
import com.coara.simpleapkinstaller.util.BundleLoader;
import com.coara.simpleapkinstaller.util.CancelToken;
import com.coara.simpleapkinstaller.util.FileUtils;
import com.coara.simpleapkinstaller.util.PackageUtils;
import com.coara.simpleapkinstaller.util.Reporter;
import com.coara.simpleapkinstaller.util.UserException;

import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public final class InstallPipeline {
    public static final class Prepared {
        public final List<File> files;
        public final File base;
        public final String packageName;

        Prepared(List<File> files, File base, String packageName) {
            this.files = files;
            this.base = base;
            this.packageName = packageName;
        }
    }

    private InstallPipeline() {}

    public static Prepared prepare(Context context, File source, File jobDir, List<ApkEntry> selected, ApkEntry baseEntry,
                                   Reporter reporter, CancelToken token) throws UserException {
        try {
            return prepareInternal(context, source, jobDir, selected, baseEntry, reporter, token);
        } catch (InterruptedIOException e) {
            throw new UserException(R.string.status_cancelled);
        } catch (IOException e) {
            throw new UserException(R.string.error_read, String.valueOf(e.getMessage()));
        }
    }

    private static Prepared prepareInternal(Context context, File source, File jobDir, List<ApkEntry> selected, ApkEntry baseEntry,
                                            Reporter reporter, CancelToken token) throws IOException, UserException {
        BundleLoader.extractMissing(source, new File(jobDir, "apks"), selected, reporter, token);
        token.check();

        reporter.progress(-1);
        reporter.status(R.string.status_checking);

        boolean allSigned = true;
        for (ApkEntry entry : selected) {
            if (!ApkSigner.isSigned(entry.file)) {
                allSigned = false;
                break;
            }
        }

        List<File> files = new ArrayList<>();
        File base = null;

        if (allSigned) {
            for (ApkEntry entry : selected) {
                files.add(entry.file);
                if (entry == baseEntry) base = entry.file;
            }
        } else {
            File signedDir = new File(jobDir, "signed");
            FileUtils.deleteRecursive(signedDir);
            if (!signedDir.mkdirs() && !signedDir.isDirectory()) throw new IOException("cannot create " + signedDir);

            int index = 0;
            for (ApkEntry entry : selected) {
                token.check();
                index++;
                reporter.status(R.string.status_signing, index, selected.size(), entry.displayName);
                reporter.progress(index * 100 / selected.size());
                File output = new File(signedDir, "signed_" + index + ".apk");
                try {
                    ApkSigner.sign(context, entry.file, output);
                } catch (GeneralSecurityException | IOException | RuntimeException e) {
                    throw new UserException(R.string.error_sign, entry.displayName + " (" + e.getClass().getSimpleName() + ": " + e.getMessage() + ")");
                }
                files.add(output);
                if (entry == baseEntry) base = output;
            }
        }

        if (base == null) throw new UserException(R.string.error_no_base);

        PackageInfo baseInfo = PackageUtils.archiveInfo(context, base, true);
        if (baseInfo == null || baseInfo.packageName == null) throw new UserException(R.string.error_invalid_apk);
        Set<String> baseSigners = PackageUtils.signerDigests(baseInfo);

        if (allSigned && files.size() > 1) {
            for (int i = 0; i < files.size(); i++) {
                File file = files.get(i);
                if (file.equals(base)) continue;
                PackageInfo info = PackageUtils.archiveInfo(context, file, true);
                if (info == null) continue;
                if (!PackageUtils.signersCompatible(baseSigners, PackageUtils.signerDigests(info))) {
                    throw new UserException(R.string.error_signer_mismatch, selected.get(i).displayName);
                }
            }
        }

        PackageInfo installed = PackageUtils.installedInfo(context, baseInfo.packageName);
        if (installed != null) {
            if (!PackageUtils.signersCompatible(PackageUtils.signerDigests(installed), baseSigners)) {
                throw new UserException(R.string.error_conflict_signature);
            }
            long installedCode = PackageUtils.versionCode(installed);
            long newCode = PackageUtils.versionCode(baseInfo);
            if (installedCode >= 0 && newCode >= 0 && newCode < installedCode) {
                throw new UserException(R.string.error_downgrade,
                        baseInfo.versionName == null ? String.valueOf(newCode) : baseInfo.versionName,
                        installed.versionName == null ? String.valueOf(installedCode) : installed.versionName);
            }
        }

        return new Prepared(files, base, baseInfo.packageName);
    }
}
