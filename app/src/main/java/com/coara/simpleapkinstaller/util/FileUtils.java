package com.coara.simpleapkinstaller.util;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.text.TextUtils;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;

public final class FileUtils {
    private FileUtils() {}

    public static String getFileName(Context context, Uri uri) {
        if (context == null || uri == null) return null;
        String scheme = uri.getScheme();
        if ("file".equals(scheme)) {
            String path = uri.getPath();
            if (path != null) {
                return new File(path).getName();
            }
            return null;
        }
        try (Cursor cursor = context.getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (nameIndex >= 0) {
                    String name = cursor.getString(nameIndex);
                    if (!TextUtils.isEmpty(name)) return name;
                }
            }
        } catch (Exception ignored) {
        }
        String lastSegment = uri.getLastPathSegment();
        if (!TextUtils.isEmpty(lastSegment)) {
            return lastSegment;
        }
        return null;
    }

    public static String sanitizeFileName(String name) {
        if (TextUtils.isEmpty(name)) return "selected_file";
        String n = name.replace('\\', '_').replace('/', '_');
        n = n.replace(':', '_').replace('*', '_').replace('?', '_');
        n = n.replace('"', '_').replace('<', '_').replace('>', '_').replace('|', '_');
        if (TextUtils.isEmpty(n.trim())) return "selected_file";
        return n;
    }

    private static final int TRANSFER_BUFFER_SIZE = 64 * 1024;

    public static boolean copyUriToFile(Context context, Uri uri, File dest) {
        if (context == null || uri == null || dest == null) return false;
        InputStream is = null;
        OutputStream os = null;
        try {
            ContentResolver resolver = context.getContentResolver();
            is = resolver.openInputStream(uri);
            if (is == null) return false;

            File parent = dest.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs() && !parent.exists()) {
                return false;
            }

            os = new BufferedOutputStream(new FileOutputStream(dest));
            byte[] buffer = new byte[TRANSFER_BUFFER_SIZE];
            int len;
            while ((len = is.read(buffer)) > 0) os.write(buffer, 0, len);
            os.flush();
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            try { if (is != null) is.close(); } catch (Exception ignored) {}
            try { if (os != null) os.close(); } catch (Exception ignored) {}
        }
    }

    public static boolean copyFile(File src, File dst) {
        if (src == null || dst == null || !src.exists()) return false;
        try {
            File parent = dst.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs() && !parent.exists()) {
                return false;
            }
            try (InputStream fis = new BufferedInputStream(new FileInputStream(src));
                 OutputStream fos = new BufferedOutputStream(new FileOutputStream(dst))) {
                byte[] buffer = new byte[TRANSFER_BUFFER_SIZE];
                int len;
                while ((len = fis.read(buffer)) > 0) fos.write(buffer, 0, len);
                fos.flush();
            }
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    public static boolean moveFile(File src, File dst) {
        if (src == null || dst == null || !src.exists()) return false;
        try {
            File parent = dst.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs() && !parent.exists()) {
                return false;
            }
            if (dst.exists()) deleteRecursive(dst);
            if (src.renameTo(dst)) return true;
            if (copyFile(src, dst)) return src.delete();
            return false;
        } catch (Exception ignored) {
            return false;
        }
    }

    public static String formatFileSize(long size) {
        if (size >= 1024L * 1024L * 1024L) {
            return String.format(Locale.US, "%.1f GB", size / (1024.0 * 1024.0 * 1024.0));
        } else if (size >= 1024L * 1024L) {
            return String.format(Locale.US, "%.1f MB", size / (1024.0 * 1024.0));
        } else if (size >= 1024L) {
            return String.format(Locale.US, "%.1f KB", size / 1024.0);
        } else {
            return size + " B";
        }
    }

    public static String safeLower(String s) {
        return (s == null) ? "" : s.toLowerCase(Locale.US);
    }

    public static long calcTotalSize(java.util.List<File> list) {
        long total = 0L;
        if (list == null) return 0L;
        for (File f : list) if (f != null && f.exists()) total += f.length();
        return total;
    }

    public static void deleteIfUnderCache(Context context, File file) {
        if (context == null || file == null || !file.exists()) return;
        try {
            if (file.getCanonicalPath().startsWith(context.getCacheDir().getCanonicalPath())) {
                deleteRecursive(file);
            }
        } catch (Exception ignored) {}
    }

    public static boolean deleteRecursive(File file) {
        if (file == null || !file.exists()) return true;
        boolean ok = true;
        if (file.isDirectory()) {
            File[] list = file.listFiles();
            if (list != null) {
                for (File child : list) ok &= deleteRecursive(child);
            }
        }
        try {
            if (file.exists() && !file.delete()) ok = false;
        } catch (Exception ignored) {
            ok = false;
        }
        return ok;
    }
}
