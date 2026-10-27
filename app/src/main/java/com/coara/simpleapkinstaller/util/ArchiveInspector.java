package com.coara.simpleapkinstaller.util;

import com.coara.simpleapkinstaller.model.ArchiveKind;
import com.coara.simpleapkinstaller.model.ArchiveMetadata;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class ArchiveInspector {
    private static final int MAX_META_BYTES = 2 * 1024 * 1024;

    public static final class Result {
        public ArchiveKind kind = ArchiveKind.ZIP_UNKNOWN;
        public final ArchiveMetadata metadata = new ArchiveMetadata();
        public final Map<String, Long> apkEntries = new LinkedHashMap<>();
        public final List<String> obbEntries = new ArrayList<>();
        public boolean hasObb;
    }

    private ArchiveInspector() {}

    public static String normalize(String entryName) {
        return entryName.replace('\\', '/').toLowerCase(Locale.ROOT);
    }

    public static boolean isObbEntry(String normalizedName) {
        return normalizedName.endsWith(".obb")
                || normalizedName.startsWith("android/obb/")
                || normalizedName.contains("/android/obb/");
    }

    public static Result inspect(File archive, String fileName) throws IOException {
        Result result = new Result();
        boolean hasSaiMeta = false;
        boolean hasBundletool = false;
        boolean hasApkm = false;
        boolean hasXapk = false;
        ZipEntry metaEntry = null;
        int metaRank = Integer.MAX_VALUE;

        try (ZipFile zip = new ZipFile(archive)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) continue;
                String name = normalize(entry.getName());

                if (isObbEntry(name)) {
                    result.hasObb = true;
                    result.obbEntries.add(entry.getName());
                    continue;
                }
                if (name.endsWith(".apk")) {
                    result.apkEntries.put(entry.getName(), entry.getSize());
                }

                int rank = metaRank(name);
                if (rank < metaRank) {
                    metaRank = rank;
                    metaEntry = entry;
                }
                if (name.equals("meta.sai_v1.json") || name.equals("meta.sai_v2.json")) hasSaiMeta = true;
                if (name.equals("toc.pb") || name.equals("toc.json") || name.startsWith("splits/") || name.startsWith("standalones/")) hasBundletool = true;
                if (name.equals("info.json") || name.equals("apkm_installer.url")) hasApkm = true;
                if (name.equals("manifest.json")) hasXapk = true;
            }

            String lowerName = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
            if (result.hasObb) hasXapk = true;

            if (hasBundletool) result.kind = ArchiveKind.APKS_BUNDLETOOL;
            else if (hasSaiMeta) result.kind = ArchiveKind.APKS_SAI;
            else if (hasApkm) result.kind = ArchiveKind.APKM;
            else if (hasXapk) result.kind = ArchiveKind.XAPK;
            else if (lowerName.endsWith(".apks")) result.kind = ArchiveKind.APKS_BUNDLETOOL;
            else if (lowerName.endsWith(".apkm")) result.kind = ArchiveKind.APKM;
            else if (lowerName.endsWith(".xapk")) result.kind = ArchiveKind.XAPK;
            else result.kind = ArchiveKind.ZIP_UNKNOWN;

            if (metaEntry != null && metaEntry.getSize() <= MAX_META_BYTES) {
                String json = readText(zip, metaEntry);
                if (json != null) parseMetadata(json, result.metadata);
            }
        }
        return result;
    }

    private static int metaRank(String name) {
        switch (name) {
            case "meta.sai_v2.json":
                return 0;
            case "meta.sai_v1.json":
                return 1;
            case "manifest.json":
                return 2;
            case "info.json":
                return 3;
            default:
                return Integer.MAX_VALUE;
        }
    }

    private static String readText(ZipFile zip, ZipEntry entry) {
        try (InputStream in = zip.getInputStream(entry);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            long total = 0;
            while ((read = in.read(buffer)) != -1) {
                total += read;
                if (total > MAX_META_BYTES) return null;
                out.write(buffer, 0, read);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    private static void parseMetadata(String json, ArchiveMetadata meta) {
        try {
            String text = json.startsWith("﻿") ? json.substring(1) : json;
            JSONObject obj = new JSONObject(text);
            meta.packageName = firstString(obj, "package_name", "package", "packageName", "pname");
            meta.appName = firstString(obj, "name", "label", "app_name", "appName");
            meta.versionName = firstString(obj, "version_name", "versionName", "release_version");
            meta.versionCode = firstLong(obj, "version_code", "versionCode", "versioncode");
            meta.baseApkHint = firstString(obj, "base_apk", "baseApk", "base_apk_file", "base_apk_path");
            if (meta.baseApkHint.isEmpty()) meta.baseApkHint = baseFromSplitList(obj.optJSONArray("split_apks"));
        } catch (Exception ignored) {
        }
    }

    private static String baseFromSplitList(JSONArray array) {
        if (array == null) return "";
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.optJSONObject(i);
            if (item == null) continue;
            String id = item.optString("id", "");
            if ("base".equalsIgnoreCase(id)) {
                String file = firstString(item, "file", "path", "name", "apk");
                if (!file.isEmpty()) return file;
            }
        }
        return "";
    }

    private static String firstString(JSONObject obj, String... keys) {
        for (String key : keys) {
            Object value = obj.opt(key);
            if (value instanceof String && !((String) value).trim().isEmpty()) return ((String) value).trim();
        }
        return "";
    }

    private static long firstLong(JSONObject obj, String... keys) {
        for (String key : keys) {
            Object value = obj.opt(key);
            if (value instanceof Number) return ((Number) value).longValue();
            if (value instanceof String) {
                try {
                    return Long.parseLong(((String) value).trim());
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return -1L;
    }

    public static boolean isApk(File file) {
        try (ZipFile zip = new ZipFile(file)) {
            ZipEntry entry = zip.getEntry("AndroidManifest.xml");
            return entry != null && !entry.isDirectory();
        } catch (IOException e) {
            return false;
        }
    }

    public static void extractEntries(File archive, Map<String, File> targets, FileUtils.ProgressListener listener) throws IOException {
        try (ZipFile zip = new ZipFile(archive)) {
            long done = 0L;
            for (Map.Entry<String, File> target : targets.entrySet()) {
                ZipEntry entry = zip.getEntry(target.getKey());
                if (entry == null || entry.isDirectory()) throw new IOException("entry not found: " + target.getKey());
                final long base = done;
                try (InputStream in = zip.getInputStream(entry);
                     OutputStream out = new FileOutputStream(target.getValue())) {
                    done += FileUtils.copy(in, out, listener == null ? null : d -> listener.onProgress(base + d));
                }
            }
        }
    }
}
