package com.coara.simpleapkinstaller.util;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.text.TextUtils;
import android.os.Build;
import android.content.res.Resources;

import com.coara.simpleapkinstaller.model.ArchiveKind;
import com.coara.simpleapkinstaller.model.ArchiveMetadata;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class ArchiveUtils {
    private static volatile java.util.Set<String> KNOWN_LANGUAGE_CODES;

    private static final class ArchiveLayoutScan {
        boolean hasSaiMeta;
        boolean hasBundletoolLayout;
        boolean hasApkmLayout;
        boolean hasXapkLayout;
        boolean hasApk;
        boolean hasJson;
        boolean hasObb;
        ZipEntry metaEntry;
    }

    private ArchiveUtils() {}

    public static final class ArchiveInspection {
        public final ArchiveKind kind;
        public final ArchiveMetadata metadata;
        public final boolean containsObb;

        public ArchiveInspection(ArchiveKind kind, ArchiveMetadata metadata, boolean containsObb) {
            this.kind = kind;
            this.metadata = metadata != null ? metadata : new ArchiveMetadata();
            this.containsObb = containsObb;
        }
    }


    private enum SplitCategory {
        BASE,
        LANGUAGE,
        ARCHITECTURE,
        DENSITY,
        FEATURE
    }

    private static boolean isConfigStyleSplitName(String apkName) {
        if (TextUtils.isEmpty(apkName)) return false;
        String name = normalizeSplitName(apkName);
        if (!name.endsWith(".apk")) return false;
        return name.startsWith("config.") || name.startsWith("split-config.");
    }

    private static String extractConfigQualifier(String apkName) {
        if (TextUtils.isEmpty(apkName)) return "";
        String name = normalizeSplitName(apkName);
        if (!name.endsWith(".apk")) return "";
        String stem = name.substring(0, name.length() - 4);
        if (stem.startsWith("split-config.")) {
            return stem.substring("split-config.".length());
        }
        if (stem.startsWith("config.")) {
            return stem.substring("config.".length());
        }
        return "";
    }

    private static boolean looksLikeLanguageQualifier(String qualifier) {
        if (TextUtils.isEmpty(qualifier)) return false;
        String normalized = normalizeLocaleQualifier(qualifier);
        if (TextUtils.isEmpty(normalized)) return false;

        String[] tokens = normalized.split("[._\\-+]+");
        for (int i = 0; i < tokens.length; i++) {
            String token = tokens[i];
            if (TextUtils.isEmpty(token)) continue;

            if ("b".equals(token)) {
                if (i + 1 < tokens.length && isKnownLanguageCode(tokens[i + 1])) {
                    return true;
                }
                continue;
            }

            if (isKnownLanguageCode(token)) {
                return true;
            }
        }
        return false;
    }

    private static boolean looksLikeAbiQualifier(String qualifier) {
        if (TextUtils.isEmpty(qualifier)) return false;
        String lower = FileUtils.safeLower(normalizeSplitName(qualifier));
        String[] abiTokens = {
                "arm64_v8a", "armeabi_v7a", "armeabi",
                "arm64", "armv8", "v8a", "v7a",
                "x86_64", "x86"
        };
        for (String token : abiTokens) {
            if (lower.contains(token)) return true;
        }
        return false;
    }

    private static boolean looksLikeDensityQualifier(String qualifier) {
        if (TextUtils.isEmpty(qualifier)) return false;
        String lower = FileUtils.safeLower(normalizeSplitName(qualifier));
        return containsAnyDensityBucket(lower) || containsNumericDensityQualifier(lower) || lower.contains("dpi");
    }

    private static SplitCategory classifySplitApkName(String apkName, ArchiveKind kind) {
        if (TextUtils.isEmpty(apkName)) return SplitCategory.FEATURE;

        String name = normalizeSplitName(apkName);
        if (isCoreBaseApkName(name)) return SplitCategory.BASE;

        String qualifier = extractConfigQualifier(name);
        if (!TextUtils.isEmpty(qualifier)) {
            if (looksLikeLanguageQualifier(qualifier)) return SplitCategory.LANGUAGE;
            if (looksLikeAbiQualifier(qualifier)) return SplitCategory.ARCHITECTURE;
            if (looksLikeDensityQualifier(qualifier)) return SplitCategory.DENSITY;
            return SplitCategory.FEATURE;
        }

        if (kind == ArchiveKind.XAPK || kind == ArchiveKind.APKM) {
            return SplitCategory.FEATURE;
        }

        if (looksLikeLanguageSplit(name) || containsCurrentLocale(name)) {
            return SplitCategory.LANGUAGE;
        }
        if (looksLikeAbiSplit(name) || containsExactSupportedAbi(name)) {
            return SplitCategory.ARCHITECTURE;
        }
        if (containsAnyDensityBucket(name) || name.contains("dpi")) {
            return SplitCategory.DENSITY;
        }
        return SplitCategory.FEATURE;
    }

    private static boolean isTargetedSplitApk(String apkName, ArchiveKind kind) {
        SplitCategory category = classifySplitApkName(apkName, kind);
        return category == SplitCategory.LANGUAGE
                || category == SplitCategory.ARCHITECTURE
                || category == SplitCategory.DENSITY;
    }

    private static boolean matchesTargetedDeviceSplit(String apkName, ArchiveKind kind) {
        return matchesTargetedDeviceSplit(null, apkName, kind);
    }

    private static boolean matchesTargetedDeviceSplit(Context context, String apkName, ArchiveKind kind) {
        SplitCategory category = classifySplitApkName(apkName, kind);
        if (category == SplitCategory.BASE || category == SplitCategory.FEATURE) return true;

        String name = FileUtils.safeLower(apkName);
        switch (category) {
            case LANGUAGE:
                return containsCurrentLocale(name);
            case ARCHITECTURE:
                return containsExactSupportedAbi(name);
            case DENSITY:
                return matchesCurrentDensity(context, name);
            default:
                return true;
        }
    }

    public static ArchiveMetadata readArchiveMetadataFromZip(File archiveFile) {
        return inspectArchive(archiveFile).metadata;
    }

    private static long parseLongSafely(Object value, long fallback) {
        try {
            if (value instanceof Number) return ((Number) value).longValue();
            String s = String.valueOf(value);
            if (TextUtils.isEmpty(s)) return fallback;
            return Long.parseLong(s.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private static String readBaseApkHint(JSONObject obj) {
        if (obj == null) return "";
        String[] keys = {"base_apk", "baseApk", "base_apk_file", "base_apk_path", "apk", "apk_file"};
        for (String key : keys) {
            String value = obj.optString(key, "");
            if (!TextUtils.isEmpty(value)) return value;
        }
        return "";
    }

    private static List<String> readSplitHints(JSONObject obj) {
        List<String> hints = new ArrayList<>();
        if (obj == null) return hints;
        try {
            JSONArray arr = obj.optJSONArray("split_apks");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) addSplitHintFromJsonItem(hints, arr.opt(i));
            }
        } catch (Exception ignored) {}
        return hints;
    }

    private static void addSplitHintFromJsonItem(List<String> hints, Object item) {
        if (item == null) return;
        if (item instanceof String) {
            String s = ((String) item).trim();
            if (!TextUtils.isEmpty(s)) hints.add(s);
        } else if (item instanceof JSONObject) {
            JSONObject part = (JSONObject) item;
            String[] keys = {"path", "file", "name", "apk", "split"};
            for (String key : keys) {
                String value = part.optString(key, "");
                if (!TextUtils.isEmpty(value)) {
                    hints.add(value);
                    break;
                }
            }
        }
    }

    private static String readStringFromJson(JSONObject obj, String... keys) {
        if (obj == null || keys == null) return "";
        for (String key : keys) {
            if (TextUtils.isEmpty(key)) continue;
            String value = obj.optString(key, "");
            if (!TextUtils.isEmpty(value)) return value;
        }
        return "";
    }

    public static ArchiveKind detectArchiveKind(File archiveFile) {
        return inspectArchive(archiveFile).kind;
    }

    public static ArchiveInspection inspectArchive(File archiveFile) {
        if (archiveFile == null || !archiveFile.exists()) {
            return new ArchiveInspection(ArchiveKind.ZIP_UNKNOWN, new ArchiveMetadata(), false);
        }

        String lowerName = FileUtils.safeLower(archiveFile.getName());
        ArchiveLayoutScan scan = new ArchiveLayoutScan();
        try (ZipFile zipFile = new ZipFile(archiveFile)) {
            Enumeration<? extends ZipEntry> entries = zipFile.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry == null || entry.isDirectory()) continue;

                String name = FileUtils.safeLower(entry.getName().replace('\\', '/'));
                if (name.endsWith(".apk")) scan.hasApk = true;
                if (name.endsWith(".json")) scan.hasJson = true;
                if (name.endsWith(".obb") || name.startsWith("android/obb/") || name.contains("/android/obb/")) scan.hasObb = true;

                if (scan.metaEntry == null && ("meta.sai_v1.json".equals(name) || "meta.sai_v2.json".equals(name)
                        || "info.json".equals(name) || "manifest.json".equals(name))) {
                    scan.metaEntry = entry;
                }
                if ("meta.sai_v1.json".equals(name) || "meta.sai_v2.json".equals(name)) scan.hasSaiMeta = true;
                if ("toc.pb".equals(name) || "toc.json".equals(name) || name.startsWith("splits/") || name.startsWith("standalones/")) {
                    scan.hasBundletoolLayout = true;
                }
                if ("info.json".equals(name) || "apkm_installer.url".equals(name)) scan.hasApkmLayout = true;
                if ("manifest.json".equals(name) || name.startsWith("android/obb/") || name.contains("/android/obb/")) scan.hasXapkLayout = true;
            }

            if (scan.hasObb) scan.hasXapkLayout = true;

            ArchiveKind kind;
            if (scan.hasBundletoolLayout) kind = ArchiveKind.APKS_BUNDLETOOL;
            else if (scan.hasSaiMeta) kind = ArchiveKind.APKS_SAI;
            else if (scan.hasApkmLayout) kind = ArchiveKind.APKM;
            else if (scan.hasXapkLayout) kind = ArchiveKind.XAPK;
            else if (lowerName.endsWith(".apks")) kind = ArchiveKind.APKS_BUNDLETOOL;
            else if (lowerName.endsWith(".apkm")) kind = ArchiveKind.APKM;
            else if (lowerName.endsWith(".xapk")) kind = ArchiveKind.XAPK;
            else if (lowerName.endsWith(".zip") && scan.hasApk && scan.hasJson) kind = ArchiveKind.ZIP_UNKNOWN;
            else kind = ArchiveKind.ZIP_UNKNOWN;

            ArchiveMetadata meta = new ArchiveMetadata();
            if (scan.metaEntry != null) {
                String json = readZipEntryAsString(zipFile, scan.metaEntry);
                if (!TextUtils.isEmpty(json)) {
                    try {
                        JSONObject obj = new JSONObject(json);
                        meta.packageName = readStringFromJson(obj, "package_name", "package", "packageName");
                        meta.appName = readStringFromJson(obj, "name", "label", "app_name", "appName");
                        meta.versionName = readStringFromJson(obj, "version_name", "versionName");
                        meta.baseApkHint = readBaseApkHint(obj);
                        meta.splitHints.addAll(readSplitHints(obj));
                        if (obj.has("version_code")) {
                            meta.versionCode = parseLongSafely(obj.opt("version_code"), meta.versionCode);
                        } else if (obj.has("versionCode")) {
                            meta.versionCode = parseLongSafely(obj.opt("versionCode"), meta.versionCode);
                        }
                    } catch (Exception ignored) {
                    }
                }
            }
            return new ArchiveInspection(kind, meta, scan.hasObb);
        } catch (Exception e) {
            return new ArchiveInspection(ArchiveKind.ZIP_UNKNOWN, new ArchiveMetadata(), false);
        }
    }

    private static ArchiveLayoutScan scanArchiveLayout(ZipFile zipFile) {
        ArchiveLayoutScan scan = new ArchiveLayoutScan();
        if (zipFile == null) return scan;

        Enumeration<? extends ZipEntry> entries = zipFile.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            if (entry == null || entry.isDirectory()) continue;

            String name = FileUtils.safeLower(entry.getName().replace('\\', '/'));
            if (name.endsWith(".apk")) scan.hasApk = true;
            if (name.endsWith(".json")) scan.hasJson = true;
            if (name.endsWith(".obb") || name.startsWith("android/obb/") || name.contains("/android/obb/")) scan.hasObb = true;

            if ("meta.sai_v1.json".equals(name) || "meta.sai_v2.json".equals(name)) {
                scan.hasSaiMeta = true;
            }
            if ("toc.pb".equals(name) || "toc.json".equals(name) || name.startsWith("splits/") || name.startsWith("standalones/")) {
                scan.hasBundletoolLayout = true;
            }
            if ("info.json".equals(name) || "apkm_installer.url".equals(name)) {
                scan.hasApkmLayout = true;
            }
            if ("manifest.json".equals(name) || name.startsWith("android/obb/") || name.contains("/android/obb/")) {
                scan.hasXapkLayout = true;
            }
        }

        if (scan.hasObb) scan.hasXapkLayout = true;
        return scan;
    }

    private static ZipEntry findEntryByExactName(ZipFile zipFile, String exactName) {
        if (zipFile == null || TextUtils.isEmpty(exactName)) return null;
        String wanted = FileUtils.safeLower(exactName);
        Enumeration<? extends ZipEntry> entries = zipFile.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            if (entry == null || entry.isDirectory()) continue;
            if (FileUtils.safeLower(entry.getName()).equals(wanted)) return entry;
        }
        return null;
    }

    private static ZipEntry findEntryByPrefix(ZipFile zipFile, String prefix) {
        if (zipFile == null || TextUtils.isEmpty(prefix)) return null;
        String lowerPrefix = FileUtils.safeLower(prefix);
        Enumeration<? extends ZipEntry> entries = zipFile.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            if (entry == null || entry.isDirectory()) continue;
            String name = FileUtils.safeLower(entry.getName());
            if (name.startsWith(lowerPrefix) || name.contains("/" + lowerPrefix)) return entry;
        }
        return null;
    }

    public static boolean safeUnzip(File zipFile, File targetDir) {
        if (zipFile == null || targetDir == null || !zipFile.exists()) return false;
        try (ZipFile zf = new ZipFile(zipFile)) {
            String targetCanonical = targetDir.getCanonicalPath() + File.separator;
            Enumeration<? extends ZipEntry> entries = zf.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry == null) continue;
                String normalized = FileUtils.safeLower(entry.getName().replace('\\', '/'));
                if (normalized.startsWith("android/obb/") || normalized.contains("/android/obb/") || normalized.endsWith(".obb")) continue;
                File outFile = new File(targetDir, entry.getName());
                if (!outFile.getCanonicalPath().startsWith(targetCanonical)) continue;
                if (entry.isDirectory()) {
                    if (!outFile.exists()) outFile.mkdirs();
                } else {
                    File parent = outFile.getParentFile();
                    if (parent != null && !parent.exists()) parent.mkdirs();
                    try (InputStream is = new BufferedInputStream(zf.getInputStream(entry));
                         java.io.OutputStream os = new BufferedOutputStream(new FileOutputStream(outFile))) {
                        byte[] buffer = new byte[64 * 1024];
                        int len;
                        while ((len = is.read(buffer)) > 0) os.write(buffer, 0, len);
                    }
                }
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean safeUnzipApkOnly(File zipFile, File targetDir) {
        if (zipFile == null || targetDir == null || !zipFile.exists()) return false;
        try (ZipFile zf = new ZipFile(zipFile)) {
            String targetCanonical = targetDir.getCanonicalPath() + File.separator;
            Enumeration<? extends ZipEntry> entries = zf.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry == null || entry.isDirectory()) continue;

                String normalizedName = FileUtils.safeLower(entry.getName().replace('\\', '/'));
                if (normalizedName.startsWith("android/obb/") || normalizedName.contains("/android/obb/") || normalizedName.endsWith(".obb")) continue;
                if (!normalizedName.endsWith(".apk") && !normalizedName.endsWith(".json")) continue;
                if (normalizedName.endsWith(".apks") || normalizedName.endsWith(".apkm") || normalizedName.endsWith(".xapk")) continue;

                File outFile = new File(targetDir, entry.getName());
                if (!outFile.getCanonicalPath().startsWith(targetCanonical)) continue;

                File parent = outFile.getParentFile();
                if (parent != null && !parent.exists()) parent.mkdirs();

                try (InputStream is = new BufferedInputStream(zf.getInputStream(entry));
                     java.io.OutputStream os = new BufferedOutputStream(new FileOutputStream(outFile))) {
                    byte[] buffer = new byte[64 * 1024];
                    int len;
                    while ((len = is.read(buffer)) > 0) os.write(buffer, 0, len);
                }
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean safeUnzipRelevant(File zipFile, File targetDir, ArchiveKind kind) {
        return safeUnzipRelevant(zipFile, targetDir, kind, kind == ArchiveKind.XAPK || kind == ArchiveKind.ZIP_UNKNOWN);
    }

    public static boolean safeUnzipRelevant(File zipFile, File targetDir, ArchiveKind kind, boolean keepObb) {
        if (zipFile == null || targetDir == null || !zipFile.exists()) return false;
        try (ZipFile zf = new ZipFile(zipFile)) {
            String targetCanonical = targetDir.getCanonicalPath() + File.separator;
            Enumeration<? extends ZipEntry> entries = zf.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry == null || entry.isDirectory()) continue;

                String normalizedName = FileUtils.safeLower(entry.getName().replace('\\', '/'));
                if (normalizedName.endsWith(".apks") || normalizedName.endsWith(".apkm") || normalizedName.endsWith(".xapk")) continue;
                if (normalizedName.startsWith("meta-inf/")) {
                    if (!normalizedName.endsWith(".json") && !normalizedName.endsWith(".apk")) continue;
                }

                boolean keep = normalizedName.endsWith(".apk")
                        || normalizedName.endsWith(".json")
                        || "toc.pb".equals(normalizedName)
                        || "toc.json".equals(normalizedName)
                        || "info.json".equals(normalizedName)
                        || "manifest.json".equals(normalizedName)
                        || normalizedName.endsWith("apkm_installer.url")
                        || normalizedName.endsWith("icon.png")
                        || normalizedName.equals("meta.sai_v1.json")
                        || normalizedName.equals("meta.sai_v2.json");

                if (!keep && keepObb) {
                    keep = normalizedName.startsWith("android/obb/")
                            || normalizedName.contains("/android/obb/")
                            || normalizedName.endsWith(".obb");
                }
                if (!keep) continue;

                File outFile = new File(targetDir, entry.getName());
                if (!outFile.getCanonicalPath().startsWith(targetCanonical)) continue;

                File parent = outFile.getParentFile();
                if (parent != null && !parent.exists()) parent.mkdirs();

                try (InputStream is = new BufferedInputStream(zf.getInputStream(entry));
                     java.io.OutputStream os = new BufferedOutputStream(new FileOutputStream(outFile))) {
                    byte[] buffer = new byte[64 * 1024];
                    int len;
                    while ((len = is.read(buffer)) > 0) os.write(buffer, 0, len);
                }
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean containsObbEntries(File archiveFile) {
        if (archiveFile == null || !archiveFile.exists()) return false;
        try (ZipFile zipFile = new ZipFile(archiveFile)) {
            Enumeration<? extends ZipEntry> entries = zipFile.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry == null || entry.isDirectory()) continue;
                String name = FileUtils.safeLower(entry.getName().replace('\\', '/'));
                if (name.startsWith("android/obb/") || name.contains("/android/obb/")) {
                    return true;
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    public static List<File> findInstallableApkFiles(File dir, ArchiveKind kind) {
        List<File> apks = new ArrayList<>();
        if (dir == null || !dir.exists()) return apks;
        File[] files = dir.listFiles();
        if (files == null) return apks;
        for (File f : files) {
            if (f == null) continue;
            if (f.isDirectory()) {
                if (kind == ArchiveKind.APKS_BUNDLETOOL && "META-INF".equalsIgnoreCase(f.getName())) continue;
                apks.addAll(findInstallableApkFiles(f, kind));
            } else if (shouldKeepInstallableApk(f, kind)) {
                apks.add(f);
            }
        }
        return apks;
    }

    private static boolean shouldKeepInstallableApk(File file, ArchiveKind kind) {
        if (file == null || !file.exists()) return false;
        String name = FileUtils.safeLower(file.getName());
        String path = FileUtils.safeLower(file.getPath().replace('\\', '/'));

        if (!name.endsWith(".apk")) return false;
        if (name.endsWith(".apkm") || name.endsWith(".apks") || name.endsWith(".xapk")) return false;
        if (name.equals("toc.pb") || name.equals("toc.json") || name.equals("info.json")
                || name.equals("manifest.json") || name.equals("icon.png")
                || name.equals("meta.sai_v1.json") || name.equals("meta.sai_v2.json")
                || name.equals("apkm_installer.url")) {
            return false;
        }
        if (path.contains("/meta-inf/")) return false;

        if (kind == ArchiveKind.APKS_BUNDLETOOL) {
            if (path.contains("/splits/") || path.contains("/standalones/")) {
                return matchesDeviceTargeting(name);
            }
            if (name.equals("base.apk") || name.equals("universal.apk") || name.startsWith("base-")) return true;
        } else if (kind == ArchiveKind.APKM) {
            if (path.contains("/splits/") || path.contains("/standalones/") || path.endsWith("/base.apk")
                    || path.endsWith("/universal.apk") || path.endsWith("/base-master.apk")) {
                return true;
            }
        } else if (kind == ArchiveKind.APKS_SAI) {
            return true;
        }

        return true;
    }

    private static boolean matchesDeviceTargeting(String apkName) {
        return matchesDeviceTargeting(null, apkName);
    }

    private static boolean matchesDeviceTargeting(Context context, String apkName) {
        if (TextUtils.isEmpty(apkName)) return false;
        String name = normalizeSplitName(apkName.replace('\\', '/'));

        if (isCoreBaseApkName(name)) return true;

        if (containsAnySupportedAbiToken(name) && deviceSupportsAbiQualifier(name)) return true;

        if (matchesCurrentDensity(context, name)) return true;

        if (looksLikeLanguageSplit(name)) {
            return containsCurrentLocale(name);
        }

        return isCoreSplitName(name) || name.startsWith("base-") || name.startsWith("split-") || name.startsWith("split_") || name.startsWith("config.");
    }

    private static boolean matchesCurrentDensity(String name) {
        return matchesCurrentDensity(null, name);
    }

    private static boolean matchesCurrentDensity(Context context, String name) {
        Integer densityDpi = getDensityDpiFromName(name);
        if (densityDpi != null) {
            int currentDensity = currentDensityDpi(context);
            return currentDensity > 0 && densityDpi.intValue() == currentDensity;
        }

        String bucket = getDensityBucketFromName(name);
        if (TextUtils.isEmpty(bucket)) return false;
        String currentBucket = currentDensityBucket(context);
        if (TextUtils.isEmpty(currentBucket)) return false;
        return bucket.equals(currentBucket);
    }

    private static String getDensityBucketFromName(String name) {
        if (TextUtils.isEmpty(name)) return null;
        String lower = FileUtils.safeLower(name);
        String[] buckets = {"xxxhdpi", "xxhdpi", "xhdpi", "hdpi", "tvdpi", "mdpi", "ldpi"};
        for (String bucket : buckets) {
            if (lower.contains(bucket)) return bucket;
        }
        return null;
    }

    private static Integer getDensityDpiFromName(String name) {
        if (TextUtils.isEmpty(name)) return null;
        String lower = FileUtils.safeLower(normalizeSplitName(name));
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(^|[._\\-+])([0-9]{2,4})dpi($|[._\\-+])").matcher(lower);
        if (matcher.find()) {
            try {
                return Integer.valueOf(Integer.parseInt(matcher.group(2)));
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private static boolean containsNumericDensityQualifier(String name) {
        return getDensityDpiFromName(name) != null;
    }

    private static int currentDensityDpi(Context context) {
        try {
            Resources resources = context != null ? context.getResources() : Resources.getSystem();
            if (resources == null) return 0;
            return resources.getDisplayMetrics().densityDpi;
        } catch (Exception ignored) {
            return 0;
        }
    }

    private static String currentDensityBucket() {
        return currentDensityBucket(null);
    }

    private static String currentDensityBucket(Context context) {
        try {
            Resources resources = context != null ? context.getResources() : Resources.getSystem();
            if (resources == null) return "";
            int density = resources.getDisplayMetrics().densityDpi;
            if (density >= 640) return "xxxhdpi";
            if (density >= 480) return "xxhdpi";
            if (density >= 320) return "xhdpi";
            if (density >= 240) return "hdpi";
            if (density >= 213) return "tvdpi";
            if (density >= 160) return "mdpi";
            if (density > 0) return "ldpi";
        } catch (Exception ignored) {
        }
        return "";
    }

    private static boolean looksLikeLanguageSplit(String name) {


        if (TextUtils.isEmpty(name)) return false;
        String normalized = normalizeSplitName(name);
        if (!normalized.endsWith(".apk")) return false;
        if (containsAnyDensityBucket(normalized) || looksLikeAbiSplit(normalized)) return false;
        if (isCoreBaseApkName(normalized)) return false;

        String stem = normalized.substring(0, normalized.length() - 4);
        String[] tokens = stem.split("[._\\-+]+");
        for (int i = 0; i < tokens.length; i++) {
            String token = tokens[i];
            if (TextUtils.isEmpty(token)) continue;

            if ("b".equals(token)) {
                if (i + 1 < tokens.length && isKnownLanguageCode(tokens[i + 1])) {
                    return true;
                }
                continue;
            }

            if (!isKnownLanguageCode(token)) continue;
            if (i == 0) return true;

            String prev = tokens[i - 1];
            if ("base".equals(prev) || "config".equals(prev) || "split".equals(prev)
                    || "splitconfig".equals(prev) || "split-config".equals(prev)
                    || "split_config".equals(prev) || prev.startsWith("base") || prev.startsWith("split")) {
                return true;
            }
        }

        if (normalized.contains("b+") && normalized.endsWith(".apk")) {
            String tail = stem;
            int idx = tail.indexOf("b+");
            if (idx >= 0) {
                String[] bcp47 = tail.substring(idx + 2).split("[._\\-+]+");
                if (bcp47.length > 0 && isKnownLanguageCode(bcp47[0])) return true;
            }
        }

        if ((normalized.startsWith("base-") || normalized.startsWith("split-") || normalized.startsWith("split_")
                || normalized.startsWith("config.") || normalized.startsWith("split-config.")
                || normalized.startsWith("split_config.")) && stem.contains("-")) {
            String suffix = stem.substring(stem.indexOf('-') + 1);
            String first = suffix.split("[._\\-+]+")[0];
            if (isKnownLanguageCode(first)) return true;
        }

        return false;
    }

    private static boolean isKnownLanguageCode(String token) {
        if (TextUtils.isEmpty(token)) return false;
        String normalized = normalizeLocaleQualifier(token);
        if (TextUtils.isEmpty(normalized)) return false;
        int dash = normalized.indexOf('-');
        if (dash > 0) normalized = normalized.substring(0, dash);
        if (normalized.startsWith("b+")) normalized = normalized.substring(2);
        if (normalized.startsWith("b-")) normalized = normalized.substring(2);
        if (normalized.startsWith("r") && normalized.length() == 3) return false;
        if (!normalized.chars().allMatch(ch -> Character.isLetter(ch))) return false;

        if (normalized.length() == 2) return true;
        if (normalized.length() == 3) {
            if (getKnownLanguageCodes().contains(normalized)) return true;
            for (Locale locale : Locale.getAvailableLocales()) {
                if (locale == null) continue;
                String lang = FileUtils.safeLower(locale.getLanguage());
                if (normalized.equals(lang)) return true;
            }
        }
        return false;
    }

    private static java.util.Set<String> getKnownLanguageCodes() {
        java.util.Set<String> cache = KNOWN_LANGUAGE_CODES;
        if (cache != null) return cache;
        synchronized (ArchiveUtils.class) {
            if (KNOWN_LANGUAGE_CODES != null) return KNOWN_LANGUAGE_CODES;

            java.util.LinkedHashSet<String> codes = new java.util.LinkedHashSet<>();
            try {
                String[] iso = Locale.getISOLanguages();
                if (iso != null) {
                    for (String lang : iso) {
                        if (!TextUtils.isEmpty(lang)) {
                            codes.add(FileUtils.safeLower(lang));
                        }
                    }
                }
            } catch (Exception ignored) {
            }

            try {
                Locale[] locales = Locale.getAvailableLocales();
                if (locales != null) {
                    for (Locale locale : locales) {
                        if (locale == null) continue;

                        String lang = FileUtils.safeLower(locale.getLanguage());
                        if (!TextUtils.isEmpty(lang)) {
                            codes.add(lang);
                        }

                        try {
                            String iso3 = FileUtils.safeLower(locale.getISO3Language());
                            if (!TextUtils.isEmpty(iso3)) {
                                codes.add(iso3);
                            }
                        } catch (Exception ignored) {
                        }
                    }
                }
            } catch (Exception ignored) {
            }

            KNOWN_LANGUAGE_CODES = codes;
            return codes;
        }
    }

    private static boolean containsAnyDensityBucket(String name) {
        if (TextUtils.isEmpty(name)) return false;
        String[] buckets = {"xxxhdpi", "xxhdpi", "xhdpi", "hdpi", "tvdpi", "mdpi", "ldpi"};
        for (String bucket : buckets) {
            if (name.contains(bucket)) return true;
        }
        return false;
    }

    private static boolean isCoreBaseApkName(String apkName) {
        if (TextUtils.isEmpty(apkName)) return false;
        String name = normalizeSplitName(apkName);
        if (!name.endsWith(".apk")) return false;

        if ("base.apk".equals(name) || "universal.apk".equals(name)) return true;
        if (name.matches("^base-master(?:[._-]?[0-9]+)?\\.apk$")) return true;
        if (name.matches("^base-main(?:[._-]?[0-9]+)?\\.apk$")) return true;
        if (name.equals("main.apk")) return true;
        return false;
    }

    private static boolean looksLikeAbiSplit(String name) {
        if (TextUtils.isEmpty(name)) return false;
        String lower = FileUtils.safeLower(name);
        String[] abiTokens = {"arm64_v8a", "armeabi_v7a", "armeabi", "arm64", "v8a", "v7a", "x86_64", "x86"};
        for (String token : abiTokens) {
            if (lower.contains(token)) return true;
        }
        return false;
    }

    private static boolean isTargetedSplitName(String apkName) {
        if (TextUtils.isEmpty(apkName)) return false;
        String name = normalizeSplitName(apkName);
        return containsAnyDensityBucket(name) || looksLikeLanguageSplit(name) || looksLikeAbiSplit(name);
    }

    private static String normalizeSplitName(String value) {
        if (TextUtils.isEmpty(value)) return "";
        String out = FileUtils.safeLower(value).replace('\\', '/');
        out = out.replace('_', '-');
        out = out.replace('+', '-');
        out = out.replace("--", "-");
        return out;
    }

    private static boolean isCoreSplitName(String apkName) {
        if (TextUtils.isEmpty(apkName)) return false;
        String name = normalizeSplitName(apkName);
        return name.startsWith("split_")
                || name.startsWith("split-")
                || name.startsWith("config.")
                || name.startsWith("split-config.")
                || name.startsWith("split_config.");
    }

    public static boolean isBundletoolBaseCandidate(File apkFile) {
        if (apkFile == null || !apkFile.exists()) return false;
        String name = normalizeSplitName(apkFile.getName());
        if (!name.endsWith(".apk")) return false;
        return isCoreBaseApkName(name);
    }

    public static List<File> findBundletoolBaseCandidates(List<File> apks) {
        List<File> out = new ArrayList<>();
        if (apks == null || apks.isEmpty()) return out;
        for (File file : apks) {
            if (isBundletoolBaseCandidate(file)) out.add(file);
        }
        return dedupeFiles(out);
    }

    public static File choosePreferredBundletoolBase(List<File> baseCandidates, ArchiveMetadata meta) {
        if (baseCandidates == null || baseCandidates.isEmpty()) return null;

        if (meta != null) {
            if (!TextUtils.isEmpty(meta.baseApkHint)) {
                File hinted = findApkByHint(baseCandidates, meta.baseApkHint);
                if (hinted != null) return hinted;
            }
            if (!TextUtils.isEmpty(meta.packageName)) {
                File pkgNameApk = findApkByHint(baseCandidates, meta.packageName + ".apk");
                if (pkgNameApk != null) return pkgNameApk;
            }
        }

        File best = null;
        int bestScore = Integer.MIN_VALUE;
        for (File file : baseCandidates) {
            if (file == null || !file.exists()) continue;
            int score = scoreMainApkCandidate(file);
            if (score > bestScore) {
                bestScore = score;
                best = file;
            }
        }
        return best != null ? best : baseCandidates.get(0);
    }

    public static List<File> selectDeviceRelevantApks(List<File> input) {
        return selectDeviceRelevantApks(null, input, ArchiveKind.APKS_BUNDLETOOL);
    }

    public static List<File> selectDeviceRelevantApks(List<File> input, ArchiveKind kind) {
        return selectDeviceRelevantApks(null, input, kind);
    }

    public static List<File> selectDeviceRelevantApks(Context context, List<File> input, ArchiveKind kind) {
        if (input == null || input.isEmpty()) return new ArrayList<>();

        List<File> selected = new ArrayList<>();

        for (File apk : input) {
            if (apk == null || !apk.exists()) continue;

            String name = FileUtils.safeLower(apk.getName());
            String path = FileUtils.safeLower(apk.getPath().replace('\\', '/'));

            if (isCoreBaseApkName(name)) {
                selected.add(apk);
                continue;
            }

            boolean targetedSplit = isTargetedSplitApk(name, kind);
            boolean splitStyle = targetedSplit
                    || isConfigStyleSplitName(name)
                    || path.contains("/splits/")
                    || path.contains("/standalones/")
                    || name.startsWith("split_")
                    || name.startsWith("split-")
                    || name.startsWith("config.")
                    || name.startsWith("base-");

            if (splitStyle && targetedSplit) {
                if (matchesTargetedDeviceSplit(context, name, kind)) {
                    selected.add(apk);
                }
                continue;
            }

            if (splitStyle) {
                selected.add(apk);
                continue;
            }

            if (name.endsWith(".apk")) {
                selected.add(apk);
            }
        }

        return dedupeFiles(selected);
    }

    public static List<File> selectBundletoolApksForInstall(List<File> candidates) {


        if (candidates == null || candidates.isEmpty()) return new ArrayList<>();
        List<File> out = new ArrayList<>();
        for (File file : candidates) {
            if (file != null && file.exists()) out.add(file);
        }
        sortApks(out);
        return dedupeFiles(out);
    }

    private static List<File> selectBestBundletoolStandaloneSet(List<File> candidates) {
        List<File> out = new ArrayList<>();
        if (candidates == null || candidates.isEmpty()) return out;

        File best = null;
        int bestScore = Integer.MIN_VALUE;
        for (File file : candidates) {
            if (file == null || !file.exists()) continue;
            int score = scoreBundletoolApk(file);
            if (score > bestScore) {
                bestScore = score;
                best = file;
            }
        }

        if (best != null) out.add(best);
        return dedupeFiles(out);
    }

    private static List<File> selectBestBundletoolSplitSet(List<File> candidates) {
        List<File> out = new ArrayList<>();
        if (candidates == null || candidates.isEmpty()) return out;

        Map<String, File> bestByBucket = new LinkedHashMap<>();
        Map<String, Integer> scoreByBucket = new LinkedHashMap<>();

        for (File file : candidates) {
            if (file == null || !file.exists()) continue;
            String bucket = bundletoolBucket(file);
            int score = scoreBundletoolApk(file);

            Integer currentScore = scoreByBucket.get(bucket);
            if (currentScore == null || score > currentScore) {
                scoreByBucket.put(bucket, score);
                bestByBucket.put(bucket, file);
            }
        }

        for (Map.Entry<String, File> entry : bestByBucket.entrySet()) {
            File file = entry.getValue();
            if (file != null && file.exists()) out.add(file);
        }

        sortApks(out);
        return dedupeFiles(out);
    }

    private static String bundletoolBucket(File apkFile) {
        if (apkFile == null) return "";
        String name = FileUtils.safeLower(apkFile.getName());
        String path = FileUtils.safeLower(apkFile.getPath().replace('\\', '/'));

        if (isBundletoolStandaloneCandidate(apkFile)) return "standalone";
        if (isBaseApkCandidate(apkFile)) return "base";
        if (containsExactSupportedAbi(name)) return "abi";
        if (containsAnyDensityBucket(name)) return "density";
        if (containsCurrentLocale(name)) return "locale";
        if (name.startsWith("config.") || name.startsWith("split_config.")) return "config:" + name;
        if (path.contains("/splits/")) return "split:" + name;
        return "file:" + path;
    }

    private static int scoreBundletoolApk(File apkFile) {
        if (apkFile == null || !apkFile.exists()) return Integer.MIN_VALUE;
        String name = FileUtils.safeLower(apkFile.getName());
        String path = FileUtils.safeLower(apkFile.getPath().replace('\\', '/'));

        int score = 0;
        if (path.contains("/standalones/")) score += 2000;
        if (path.contains("/splits/")) score += 1500;
        if (name.equals("base.apk")) score += 1200;
        if (name.equals("universal.apk")) score += 1100;
        if (name.equals("base-master.apk")) score += 1000;
        if (name.startsWith("base-")) score += 900;
        if (name.startsWith("split_")) score += 500;
        if (name.startsWith("split-")) score += 500;
        if (name.startsWith("config.")) score += 400;
        if (name.startsWith("split_config.")) score += 400;
        if (containsExactSupportedAbi(name)) score += 300;
        if (containsAnyDensityBucket(name)) score += 200;
        if (containsCurrentLocale(name)) score += 100;
        if (name.contains("master")) score += 50;
        if (name.endsWith(".apk")) score += 10;
        return score;
    }

    private static boolean isBaseApkCandidate(File apkFile) {
        if (apkFile == null || !apkFile.exists()) return false;
        String name = FileUtils.safeLower(apkFile.getName());
        return isCoreBaseApkName(name);
    }

    private static boolean isBundletoolStandaloneCandidate(File apkFile) {
        if (apkFile == null || !apkFile.exists()) return false;
        String path = FileUtils.safeLower(apkFile.getPath().replace('\\', '/'));
        String name = FileUtils.safeLower(apkFile.getName());
        return path.contains("/standalones/") || isCoreBaseApkName(name);
    }

    private static boolean containsExactSupportedAbi(String apkName) {
        if (TextUtils.isEmpty(apkName)) return false;
        return deviceSupportsAbiQualifier(apkName);
    }

    private static boolean containsAnySupportedAbiToken(String apkName) {
        if (TextUtils.isEmpty(apkName)) return false;
        String normalized = normalizeSplitName(apkName);
        return normalized.contains("arm64-v8a")
                || normalized.contains("aarch64")
                || normalized.contains("armeabi-v7a")
                || normalized.contains("armv7a")
                || normalized.contains("armeabi")
                || normalized.contains("x86_64")
                || normalized.contains("x86-64")
                || normalized.contains("x86");
    }

    private static boolean deviceSupportsAbiQualifier(String apkName) {
        if (TextUtils.isEmpty(apkName)) return false;
        String normalized = normalizeSplitName(apkName);

        if (containsAnyAlias(normalized, "arm64-v8a", "arm64v8a", "arm64", "aarch64", "v8a")) {
            return hasArm64Support();
        }
        if (containsAnyAlias(normalized, "armeabi-v7a", "armeabiv7a", "armv7a", "armv7", "v7a", "armeabi")) {
            return hasArm32Support();
        }
        if (containsAnyAlias(normalized, "x86_64", "x86-64", "x8664")) {
            return hasX86_64Support();
        }
        if (containsAnyAlias(normalized, "x86")) {
            return hasX86Support();
        }
        return false;
    }

    private static boolean containsAnyAlias(String normalizedName, String... aliases) {
        if (TextUtils.isEmpty(normalizedName) || aliases == null) return false;
        for (String alias : aliases) {
            if (TextUtils.isEmpty(alias)) continue;
            String normalizedAlias = normalizeSplitName(alias);
            if (TextUtils.isEmpty(normalizedAlias)) continue;
            if (normalizedName.contains(normalizedAlias)) return true;
        }
        return false;
    }

    private static boolean hasArm64Support() {
        return containsAnyAbi(Build.SUPPORTED_64_BIT_ABIS, "arm64-v8a", "aarch64", "arm64");
    }

    private static boolean hasArm32Support() {
        return containsAnyAbi(Build.SUPPORTED_32_BIT_ABIS, "armeabi-v7a", "armv7a", "armeabi", "armv7");
    }

    private static boolean hasX86_64Support() {
        return containsAnyAbi(Build.SUPPORTED_64_BIT_ABIS, "x86_64", "x86-64");
    }

    private static boolean hasX86Support() {
        return containsAnyAbi(Build.SUPPORTED_32_BIT_ABIS, "x86");
    }

    private static boolean containsAnyAbi(String[] abis, String... aliases) {
        if (abis == null || aliases == null) return false;
        for (String abi : abis) {
            if (TextUtils.isEmpty(abi)) continue;
            String normalizedAbi = normalizeSplitName(abi);
            for (String alias : aliases) {
                if (TextUtils.isEmpty(alias)) continue;
                String normalizedAlias = normalizeSplitName(alias);
                if (!TextUtils.isEmpty(normalizedAlias) && normalizedAbi.contains(normalizedAlias)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean containsCurrentLocale(String apkName) {

        if (TextUtils.isEmpty(apkName)) return false;
        String name = normalizeLocaleQualifier(apkName);
        String language = FileUtils.safeLower(Locale.getDefault().getLanguage());
        String country = FileUtils.safeLower(Locale.getDefault().getCountry());
        String script = "";
        try {
            script = FileUtils.safeLower(Locale.getDefault().getScript());
        } catch (Exception ignored) {
        }
        String languageTag = "";
        try {
            languageTag = FileUtils.safeLower(Locale.getDefault().toLanguageTag());
        } catch (Exception ignored) {
        }

        if (!TextUtils.isEmpty(languageTag) && containsLocaleToken(name, languageTag)) return true;

        if (!TextUtils.isEmpty(language) && !TextUtils.isEmpty(country)) {
            String combined = language + "-" + country;
            if (containsLocaleToken(name, combined)) return true;
            if (containsLocaleToken(name, language + "_" + country)) return true;
        }

        if (!TextUtils.isEmpty(language) && !TextUtils.isEmpty(script)) {
            String combined = language + "-" + script;
            if (containsLocaleToken(name, combined)) return true;
        }

        if (!TextUtils.isEmpty(language) && containsLocaleToken(name, language)) return true;
        return false;
    }

    private static boolean containsLocaleToken(String apkName, String token) {
        if (TextUtils.isEmpty(apkName) || TextUtils.isEmpty(token)) return false;
        String name = normalizeLocaleQualifier(FileUtils.safeLower(apkName));
        String wanted = normalizeLocaleQualifier(FileUtils.safeLower(token));

        if (TextUtils.isEmpty(name) || TextUtils.isEmpty(wanted)) return false;
        if (name.equals(wanted + ".apk")) return true;
        if (name.contains("-" + wanted + ".apk")) return true;
        if (name.contains("." + wanted + ".apk")) return true;
        if (name.contains("." + wanted + ".")) return true;
        if (name.contains("-" + wanted + ".")) return true;
        if (name.contains("_" + wanted + "_")) return true;
        if (name.contains("_" + wanted + ".apk")) return true;
        if (name.contains("/" + wanted + ".apk")) return true;
        return false;
    }

    private static String normalizeLocaleQualifier(String value) {
        if (TextUtils.isEmpty(value)) return "";
        String out = FileUtils.safeLower(value).replace('\\', '/');
        out = out.replace('_', '-');
        out = out.replace('+', '-');
        out = out.replace("-r", "-");
        out = out.replace("--", "-");
        return out;
    }

    public static List<File> findAllApkFiles(File dir) {
        List<File> apks = new ArrayList<>();
        if (dir == null || !dir.exists()) return apks;
        File[] files = dir.listFiles();
        if (files == null) return apks;
        for (File f : files) {
            if (f == null) continue;
            if (f.isDirectory()) apks.addAll(findAllApkFiles(f));
            else if (FileUtils.safeLower(f.getName()).endsWith(".apk")) apks.add(f);
        }
        return apks;
    }

    public static List<File> dedupeFiles(List<File> list) {
        List<File> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        if (list == null) return out;
        for (File f : list) {
            if (f == null) continue;
            try {
                if (seen.add(f.getCanonicalPath())) out.add(f);
            } catch (Exception e) {
                if (seen.add(f.getAbsolutePath())) out.add(f);
            }
        }
        return out;
    }

    public static Map<String, List<File>> groupApksByPackageName(Context context, List<File> apks) {
        Map<String, List<File>> groups = new LinkedHashMap<>();
        List<File> unparsed = new ArrayList<>();
        if (context == null || apks == null) return groups;
        PackageManager pm = context.getPackageManager();

        for (File apk : apks) {
            if (apk == null || !apk.exists()) continue;
            PackageInfo pi = null;
            try {
                pi = pm.getPackageArchiveInfo(apk.getAbsolutePath(), 0);
            } catch (Exception ignored) {}
            String pkg = (pi != null && !TextUtils.isEmpty(pi.packageName)) ? pi.packageName : "";
            if (TextUtils.isEmpty(pkg)) {
                unparsed.add(apk);
                continue;
            }
            if (!groups.containsKey(pkg)) groups.put(pkg, new ArrayList<>());
            groups.get(pkg).add(apk);
        }

        if (!unparsed.isEmpty()) {
            if (groups.size() == 1) {
                groups.values().iterator().next().addAll(unparsed);
            } else if (!groups.isEmpty()) {
                String bestPkg = null;
                int max = -1;
                for (Map.Entry<String, List<File>> entry : groups.entrySet()) {
                    if (entry.getValue().size() > max) {
                        max = entry.getValue().size();
                        bestPkg = entry.getKey();
                    }
                }
                if (bestPkg != null) groups.get(bestPkg).addAll(unparsed);
            } else {
                groups.put("unknown_package", unparsed);
            }
        }
        return groups;
    }

    public static List<File> chooseBestGroup(Map<String, List<File>> groups, ArchiveMetadata meta, List<File> fallbackAll) {
        if (groups == null || groups.isEmpty()) return fallbackAll != null ? new ArrayList<>(fallbackAll) : new ArrayList<>();
        if (meta != null && !TextUtils.isEmpty(meta.packageName) && groups.containsKey(meta.packageName)) {
            return new ArrayList<>(groups.get(meta.packageName));
        }

        String bestPackage = null;
        int bestCount = -1;
        long bestBytes = -1L;
        for (Map.Entry<String, List<File>> entry : groups.entrySet()) {
            List<File> list = entry.getValue();
            if (list == null || list.isEmpty()) continue;
            int count = list.size();
            long bytes = 0L;
            for (File f : list) if (f != null && f.exists()) bytes += f.length();
            if (count > bestCount || (count == bestCount && bytes > bestBytes)) {
                bestPackage = entry.getKey();
                bestCount = count;
                bestBytes = bytes;
            }
        }
        if (!TextUtils.isEmpty(bestPackage) && groups.containsKey(bestPackage)) return new ArrayList<>(groups.get(bestPackage));
        return fallbackAll != null ? new ArrayList<>(fallbackAll) : new ArrayList<>();
    }

    public static void sortApks(List<File> apks) {
        if (apks == null) return;
        Collections.sort(apks, (f1, f2) -> {
            if (f1 == null && f2 == null) return 0;
            if (f1 == null) return 1;
            if (f2 == null) return -1;
            String n1 = FileUtils.safeLower(f1.getName());
            String n2 = FileUtils.safeLower(f2.getName());
            int r1 = apkSortRank(n1);
            int r2 = apkSortRank(n2);
            if (r1 != r2) return r1 - r2;
            return n1.compareTo(n2);
        });
    }

    private static int apkSortRank(String name) {
        String n = FileUtils.safeLower(name);
        if ("base.apk".equals(n)) return 0;
        if (n.startsWith("base-") || n.startsWith("main")) return 1;
        if (n.startsWith("split") || n.startsWith("config.")) return 2;
        return 3;
    }

    public static File findMainApkFile(List<File> apks, ArchiveMetadata meta) {
        if (apks == null || apks.isEmpty()) return null;

        if (meta != null) {
            if (!TextUtils.isEmpty(meta.baseApkHint)) {
                File hinted = findApkByHint(apks, meta.baseApkHint);
                if (hinted != null) return hinted;
            }
            if (!TextUtils.isEmpty(meta.packageName)) {
                File pkgNameApk = findApkByHint(apks, meta.packageName + ".apk");
                if (pkgNameApk != null) return pkgNameApk;
            }
        }

        File best = null;
        int bestScore = Integer.MIN_VALUE;

        for (File f : apks) {
            if (f == null || !f.exists()) continue;
            int score = scoreMainApkCandidate(f);
            if (score > bestScore) {
                bestScore = score;
                best = f;
            }
        }

        if (best != null) return best;
        if (apks.size() == 1) return apks.get(0);

        File largest = null;
        long maxSize = -1L;
        for (File f : apks) {
            if (f == null) continue;
            long size = f.length();
            if (size > maxSize) {
                maxSize = size;
                largest = f;
            }
        }
        return largest;
    }

    private static int scoreMainApkCandidate(File apkFile) {
        if (apkFile == null || !apkFile.exists()) return Integer.MIN_VALUE;
        String name = normalizeSplitName(apkFile.getName());
        String path = FileUtils.safeLower(apkFile.getPath().replace('\\', '/'));

        int score = 0;
        if ("base.apk".equals(name)) score += 10000;
        if ("base-master.apk".equals(name)) score += 9900;
        if (name.matches("^base-master(?:[._-]?[0-9]+)?\\.apk$")) score += 9800;
        if ("universal.apk".equals(name)) score += 9700;
        if ("main.apk".equals(name)) score += 9600;
        if (name.startsWith("main")) score += 6000;
        if (path.contains("/standalones/")) score += 4000;
        if (path.contains("/splits/")) score += 3000;

        if (looksLikeLanguageSplit(name)) score -= 6000;
        if (containsAnyDensityBucket(name)) score -= 4000;
        if (looksLikeAbiSplit(name)) score -= 2500;
        if (isCoreSplitName(name) && !isCoreBaseApkName(name)) score -= 1500;
        if (name.startsWith("base-") && !isCoreBaseApkName(name)) score -= 1000;
        if (!name.endsWith(".apk")) score -= 10000;
        return score;
    }

    public static File findApkByHint(List<File> apks, String hint) {
        if (apks == null || apks.isEmpty() || TextUtils.isEmpty(hint)) return null;
        String wanted = FileUtils.safeLower(hint.trim());
        for (File f : apks) {
            if (f == null) continue;
            String name = FileUtils.safeLower(f.getName());
            String path = FileUtils.safeLower(f.getPath());
            if (name.equals(wanted) || path.endsWith(File.separator + wanted) || path.endsWith(wanted)) return f;
        }
        for (File f : apks) {
            if (f == null) continue;
            String name = FileUtils.safeLower(f.getName());
            String path = FileUtils.safeLower(f.getPath());
            if (name.endsWith(wanted) || path.endsWith(wanted)) return f;
        }
        return null;
    }

    public static String getCategorizedApkName(File apk) {
        return getCategorizedApkName(apk, null);
    }

    public static String getCategorizedApkName(File apk, ArchiveKind archiveKind) {
        if (apk == null) return "";
        String name = FileUtils.safeLower(apk.getName());

        if (archiveKind == ArchiveKind.XAPK || archiveKind == ArchiveKind.APKM) {
            if (isCoreBaseApkName(name)) {
                return "[BASE] " + apk.getName();
            }

            SplitCategory category = classifySplitApkName(name, archiveKind);
            switch (category) {
                case LANGUAGE:
                    return "[言語] " + apk.getName();
                case ARCHITECTURE:
                    return "[アーキテクチャ] " + apk.getName();
                case DENSITY:
                    return "[密度] " + apk.getName();
                default:
                    return "[機能] " + apk.getName();
            }
        }

        if (archiveKind == ArchiveKind.APKS_BUNDLETOOL) {
            if (isBundletoolBaseCandidate(apk)) {
                return "[BASE] " + apk.getName();
            }

            SplitCategory category = classifySplitApkName(name, archiveKind);
            switch (category) {
                case LANGUAGE:
                    return "[言語] " + apk.getName();
                case ARCHITECTURE:
                    return "[アーキテクチャ] " + apk.getName();
                case DENSITY:
                    return "[密度] " + apk.getName();
                case FEATURE:
                    if (name.startsWith("split_") || name.startsWith("split-") || name.startsWith("config.") || name.startsWith("base-")) {
                        return "[機能] " + apk.getName();
                    }
                    return "[APK] " + apk.getName();
                default:
                    return "[APK] " + apk.getName();
            }
        }

        if (isBundletoolBaseCandidate(apk)) {
            return "[BASE] " + apk.getName();
        }

        SplitCategory category = classifySplitApkName(name, archiveKind);
        switch (category) {
            case LANGUAGE:
                return "[言語] " + apk.getName();
            case ARCHITECTURE:
                return "[アーキテクチャ] " + apk.getName();
            case DENSITY:
                return "[密度] " + apk.getName();
            case FEATURE:
                return "[機能] " + apk.getName();
            default:
                return "[APK] " + apk.getName();
        }
    }

    private static ZipEntry findFirstExistingEntry(ZipFile zipFile, String... candidateNames) {

        if (zipFile == null || candidateNames == null) return null;
        for (String candidate : candidateNames) {
            ZipEntry entry = findEntryByExactName(zipFile, candidate);
            if (entry != null) return entry;
        }
        return null;
    }

    private static String readZipEntryAsString(ZipFile zipFile, ZipEntry entry) {
        if (zipFile == null || entry == null) return "";
        try (InputStream is = zipFile.getInputStream(entry);
             BufferedReader reader = new BufferedReader(new InputStreamReader(is, Charset.forName("UTF-8")))) {
            StringBuilder sb = new StringBuilder();
            char[] buffer = new char[4096];
            int len;
            while ((len = reader.read(buffer)) > 0) sb.append(buffer, 0, len);
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

}
