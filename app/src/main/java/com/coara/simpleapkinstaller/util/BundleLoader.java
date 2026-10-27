package com.coara.simpleapkinstaller.util;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.net.Uri;

import com.coara.simpleapkinstaller.R;
import com.coara.simpleapkinstaller.model.ApkEntry;
import com.coara.simpleapkinstaller.model.ArchiveKind;
import com.coara.simpleapkinstaller.model.ArchiveMetadata;
import com.coara.simpleapkinstaller.model.SplitCategory;

import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class BundleLoader {
    private static final long SPACE_MARGIN = 32L * 1024L * 1024L;

    public static final class Loaded {
        public File source;
        public String fileName;
        public ArchiveKind kind = ArchiveKind.APK;
        public ArchiveMetadata metadata = new ArchiveMetadata();
        public boolean hasObb;
        public List<String> obbEntries = new ArrayList<>();
        public List<ApkEntry> entries = new ArrayList<>();
        public ApkEntry baseEntry;
        public PackageUtils.AppInfo app;
        public String installedVersionName;
        public long installedVersionCode = -1L;
        public DeviceProfile profile;
    }

    private BundleLoader() {}

    public static Loaded load(Context context, Uri uri, File jobDir, Reporter reporter, CancelToken token) throws UserException {
        try {
            return loadInternal(context, uri, jobDir, reporter, token);
        } catch (InterruptedIOException e) {
            throw new UserException(R.string.status_cancelled);
        } catch (SecurityException e) {
            throw new UserException("file".equals(uri.getScheme()), R.string.error_read, String.valueOf(e.getMessage()));
        } catch (IOException e) {
            String message = String.valueOf(e.getMessage());
            boolean denied = "file".equals(uri.getScheme()) && (message.contains("EACCES") || message.contains("Permission denied"));
            throw new UserException(denied, R.string.error_read, message);
        }
    }

    private static Loaded loadInternal(Context context, Uri uri, File jobDir, Reporter reporter, CancelToken token) throws IOException, UserException {
        String name = FileUtils.displayName(context, uri);
        if (name == null || name.trim().isEmpty()) name = "selected";
        String safeName = FileUtils.sanitize(name, "selected");

        if (!jobDir.isDirectory() && !jobDir.mkdirs() && !jobDir.isDirectory()) throw new IOException("cannot create work directory");

        final long size = FileUtils.querySize(context, uri);
        if (size > 0 && size + SPACE_MARGIN > jobDir.getUsableSpace()) throw new UserException(R.string.error_no_space);

        File source = new File(jobDir, "source.bin");
        reporter.status(R.string.status_reading, 0);
        reporter.progress(0);
        final int[] last = {-1};
        try {
            FileUtils.copyUriToFile(context, uri, source, done -> {
                token.check();
                if (size > 0) {
                    int percent = (int) Math.min(100L, done * 100L / size);
                    if (percent != last[0]) {
                        last[0] = percent;
                        reporter.status(R.string.status_reading, percent);
                        reporter.progress(percent);
                    }
                }
            });
        } catch (InterruptedIOException e) {
            throw e;
        } catch (IOException e) {
            if (!jobDir.isDirectory() || jobDir.getUsableSpace() < SPACE_MARGIN) throw new UserException(R.string.error_no_space);
            throw e;
        }
        token.check();

        Loaded loaded = new Loaded();
        loaded.source = source;
        loaded.fileName = name;
        loaded.profile = PackageUtils.deviceProfile(context);

        reporter.progress(-1);
        reporter.status(R.string.status_analyzing);

        if (ArchiveInspector.isApk(source)) {
            ApkEntry single = new ApkEntry(name, name, source.length(), "", "", SplitCategory.BASE);
            single.file = source;
            single.checked = true;
            loaded.kind = ArchiveKind.APK;
            loaded.entries.add(single);
            loaded.baseEntry = single;
        } else {
            ArchiveInspector.Result inspection;
            try {
                inspection = ArchiveInspector.inspect(source, name);
            } catch (IOException e) {
                throw new UserException(R.string.toast_unsupported);
            }
            token.check();
            loaded.kind = inspection.kind;
            loaded.metadata = inspection.metadata;
            loaded.hasObb = inspection.hasObb;
            loaded.obbEntries = inspection.obbEntries;

            List<ApkEntry> entries = SplitSelector.build(inspection.apkEntries, loaded.kind);
            if (entries.isEmpty()) throw new UserException(R.string.error_no_apk);

            if (needsPackageGrouping(loaded.kind, loaded.metadata, entries)) {
                extractMissing(source, new File(jobDir, "apks"), entries, reporter, token);
                entries = keepBestPackage(context, entries, loaded.metadata);
            }

            SplitSelector.applyDefaults(entries, loaded.profile, loaded.metadata);
            loaded.entries = entries;
            loaded.baseEntry = SplitSelector.selectedBase(entries);
            if (loaded.baseEntry == null) throw new UserException(R.string.error_no_base);

            extractMissing(source, new File(jobDir, "apks"), java.util.Collections.singletonList(loaded.baseEntry), reporter, token);
        }

        reporter.status(R.string.status_analyzing);
        refreshApp(context, loaded);
        return loaded;
    }

    public static void refreshApp(Context context, Loaded loaded) throws UserException {
        ApkEntry base = loaded.baseEntry;
        if (base == null || base.file == null) throw new UserException(R.string.error_no_base);

        PackageUtils.AppInfo app = PackageUtils.readApp(context, base.file);
        if (app == null) throw new UserException(R.string.error_invalid_apk);
        if (app.label.isEmpty()) app.label = loaded.metadata.appName;
        loaded.app = app;

        PackageInfo installed = PackageUtils.installedInfo(context, app.packageName);
        if (installed != null) {
            loaded.installedVersionName = installed.versionName == null ? "" : installed.versionName;
            loaded.installedVersionCode = PackageUtils.versionCode(installed);
        } else {
            loaded.installedVersionName = null;
            loaded.installedVersionCode = -1L;
        }
    }

    private static boolean needsPackageGrouping(ArchiveKind kind, ArchiveMetadata meta, List<ApkEntry> entries) {
        if (entries.size() < 2) return false;
        if (kind == ArchiveKind.APKS_BUNDLETOOL || kind == ArchiveKind.APKM) return false;
        return meta.packageName.isEmpty();
    }

    private static List<ApkEntry> keepBestPackage(Context context, List<ApkEntry> entries, ArchiveMetadata meta) {
        Map<String, List<ApkEntry>> groups = new LinkedHashMap<>();
        List<ApkEntry> unparsed = new ArrayList<>();
        for (ApkEntry entry : entries) {
            PackageInfo info = entry.file == null ? null : PackageUtils.archiveInfo(context, entry.file, false);
            if (info == null || info.packageName == null || info.packageName.isEmpty()) {
                unparsed.add(entry);
                continue;
            }
            List<ApkEntry> list = groups.get(info.packageName);
            if (list == null) {
                list = new ArrayList<>();
                groups.put(info.packageName, list);
            }
            list.add(entry);
        }
        if (groups.isEmpty()) return entries;

        List<ApkEntry> best = null;
        long bestBytes = -1L;
        if (!meta.packageName.isEmpty() && groups.containsKey(meta.packageName)) {
            best = groups.get(meta.packageName);
        } else {
            for (List<ApkEntry> list : groups.values()) {
                long bytes = 0L;
                for (ApkEntry entry : list) bytes += Math.max(0L, entry.size);
                if (best == null || list.size() > best.size() || (list.size() == best.size() && bytes > bestBytes)) {
                    best = list;
                    bestBytes = bytes;
                }
            }
        }
        List<ApkEntry> out = new ArrayList<>(best);
        out.addAll(unparsed);
        return out;
    }

    public static void extractMissing(File source, File dir, List<ApkEntry> entries, Reporter reporter, CancelToken token) throws IOException, UserException {
        Map<String, File> targets = new LinkedHashMap<>();
        long total = 0L;
        int index = 0;
        for (ApkEntry entry : entries) {
            index++;
            if (entry.file != null && entry.file.isFile()) continue;
            if (!dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory()) throw new IOException("cannot create " + dir);
            File target = new File(dir, "apk_" + index + "_" + System.nanoTime() + ".apk");
            targets.put(entry.entryName, target);
            entry.file = target;
            total += Math.max(0L, entry.size);
        }
        if (targets.isEmpty()) return;

        if (total > 0 && total + SPACE_MARGIN > dir.getUsableSpace()) throw new UserException(R.string.error_no_space);

        final long all = total;
        final int[] last = {-1};
        ArchiveInspector.extractEntries(source, targets, done -> {
            token.check();
            if (all > 0) {
                int percent = (int) Math.min(100L, done * 100L / all);
                if (percent != last[0]) {
                    last[0] = percent;
                    reporter.status(R.string.status_extracting, percent);
                    reporter.progress(percent);
                }
            }
        });
    }
}
