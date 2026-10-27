package com.coara.simpleapkinstaller.util;

import android.os.Environment;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class ObbInstaller {
    private ObbInstaller() {}

    @SuppressWarnings("deprecation")
    private static File obbRoot(String packageName) {
        return new File(Environment.getExternalStorageDirectory(), "Android/obb/" + packageName);
    }

    public static void install(File archive, List<String> obbEntries, String packageName, FileUtils.ProgressListener listener) throws IOException {
        if (packageName == null || packageName.isEmpty() || packageName.contains("/") || packageName.contains("..")) {
            throw new IOException("invalid package name");
        }
        File root = obbRoot(packageName);
        if (!root.isDirectory() && !root.mkdirs() && !root.isDirectory()) {
            throw new IOException("cannot create " + root);
        }

        try (ZipFile zip = new ZipFile(archive)) {
            long total = 0L;
            for (String name : obbEntries) {
                ZipEntry entry = zip.getEntry(name);
                if (entry != null && entry.getSize() > 0) total += entry.getSize();
            }
            if (total > 0 && root.getUsableSpace() > 0 && root.getUsableSpace() < total) {
                throw new IOException("not enough space");
            }

            long done = 0L;
            for (String name : obbEntries) {
                ZipEntry entry = zip.getEntry(name);
                if (entry == null || entry.isDirectory()) continue;

                File dest = destination(root, name);
                if (dest == null) continue;
                File parent = dest.getParentFile();
                if (parent != null && !parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) {
                    throw new IOException("cannot create " + parent);
                }

                File part = new File(dest.getPath() + ".part");
                final long base = done;
                try (InputStream in = zip.getInputStream(entry);
                     OutputStream out = new FileOutputStream(part)) {
                    done += FileUtils.copy(in, out, listener == null ? null : d -> listener.onProgress(base + d));
                } catch (IOException | RuntimeException e) {
                    FileUtils.deleteRecursive(part);
                    throw e;
                }
                if (dest.exists() && !dest.delete()) {
                    FileUtils.deleteRecursive(part);
                    throw new IOException("cannot replace " + dest);
                }
                if (!part.renameTo(dest)) {
                    FileUtils.deleteRecursive(part);
                    throw new IOException("cannot rename " + part);
                }
            }
        }
    }

    static File destination(File root, String entryName) {
        String path = entryName.replace('\\', '/');
        while (path.startsWith("/")) path = path.substring(1);

        String lower = path.toLowerCase(Locale.ROOT);
        int marker = lower.indexOf("android/obb/");
        String relative;
        if (marker >= 0) {
            relative = path.substring(marker + "android/obb/".length());
            int slash = relative.indexOf('/');
            relative = slash >= 0 ? relative.substring(slash + 1) : relative;
        } else {
            int slash = path.lastIndexOf('/');
            relative = slash >= 0 ? path.substring(slash + 1) : path;
        }

        String[] segments = relative.split("/");
        File dest = root;
        int used = 0;
        for (String segment : segments) {
            if (segment.isEmpty() || segment.equals(".")) continue;
            if (segment.equals("..")) return null;
            dest = new File(dest, segment);
            used++;
        }
        if (used == 0 || !FileUtils.isInside(root, dest)) return null;
        return dest;
    }
}
