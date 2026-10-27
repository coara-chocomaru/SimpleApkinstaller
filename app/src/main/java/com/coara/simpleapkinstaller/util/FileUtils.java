package com.coara.simpleapkinstaller.util;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public final class FileUtils {
    public static final int BUFFER_SIZE = 128 * 1024;

    public interface ProgressListener {
        void onProgress(long done) throws IOException;
    }

    private FileUtils() {}

    public static String displayName(Context context, Uri uri) {
        String scheme = uri.getScheme();
        if ("file".equals(scheme)) {
            String path = uri.getPath();
            return path == null ? null : new File(path).getName();
        }
        try (Cursor cursor = context.getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) {
                    String name = cursor.getString(index);
                    if (name != null && !name.trim().isEmpty()) return name;
                }
            }
        } catch (RuntimeException ignored) {
        }
        return uri.getLastPathSegment();
    }

    public static long querySize(Context context, Uri uri) {
        if ("file".equals(uri.getScheme())) {
            String path = uri.getPath();
            return path == null ? -1L : new File(path).length();
        }
        try (Cursor cursor = context.getContentResolver().query(uri, new String[]{OpenableColumns.SIZE}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.SIZE);
                if (index >= 0 && !cursor.isNull(index)) return cursor.getLong(index);
            }
        } catch (RuntimeException ignored) {
        }
        return -1L;
    }

    public static String sanitize(String name, String fallback) {
        if (name == null) return fallback;
        StringBuilder sb = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            boolean bad = c < 0x20 || c == '/' || c == '\\' || c == ':' || c == '*' || c == '?' || c == '"' || c == '<' || c == '>' || c == '|';
            sb.append(bad ? '_' : c);
        }
        String out = sb.toString().trim();
        if (out.isEmpty() || out.equals(".") || out.equals("..")) return fallback;
        if (out.length() > 120) {
            int dot = out.lastIndexOf('.');
            String ext = dot > 0 && out.length() - dot <= 8 ? out.substring(dot) : "";
            out = out.substring(0, 120 - ext.length()) + ext;
        }
        return out;
    }

    public static long copy(InputStream in, OutputStream out, ProgressListener listener) throws IOException {
        byte[] buffer = new byte[BUFFER_SIZE];
        long total = 0L;
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
            total += read;
            if (listener != null) listener.onProgress(total);
        }
        return total;
    }

    public static void copyUriToFile(Context context, Uri uri, File dest, ProgressListener listener) throws IOException {
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IOException("openInputStream returned null");
            try (OutputStream out = new FileOutputStream(dest)) {
                copy(in, out, listener);
            }
        } catch (IOException | RuntimeException e) {
            deleteRecursive(dest);
            throw e;
        }
    }

    public static boolean deleteRecursive(File file) {
        if (file == null) return true;
        try {
            File parent = file.getParentFile();
            if (parent != null) {
                File resolved = new File(parent.getCanonicalFile(), file.getName());
                if (!resolved.getCanonicalPath().equals(resolved.getAbsolutePath())) return file.delete();
            }
        } catch (IOException ignored) {
        }
        boolean ok = true;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) ok &= deleteRecursive(child);
            }
        }
        return (!file.exists() || file.delete()) && ok;
    }

    public static void clearDirectory(File dir) {
        if (dir == null) return;
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File child : children) deleteRecursive(child);
    }

    public static boolean isInside(File parent, File child) {
        try {
            String p = parent.getCanonicalPath();
            String c = child.getCanonicalPath();
            return c.equals(p) || c.startsWith(p + File.separator);
        } catch (IOException e) {
            return false;
        }
    }
}
