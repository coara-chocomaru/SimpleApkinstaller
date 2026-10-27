package com.coara.simpleapkinstaller.util;

import com.coara.simpleapkinstaller.model.ApkEntry;
import com.coara.simpleapkinstaller.model.ArchiveKind;
import com.coara.simpleapkinstaller.model.ArchiveMetadata;
import com.coara.simpleapkinstaller.model.SplitCategory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

public final class SplitSelector {
    private static final Pattern CORE_BASE = Pattern.compile("^(base|universal|main|base[-_]master([-_.]?[0-9]+)?|base[-_]main([-_.]?[0-9]+)?)$");
    private static final Pattern LANGUAGE_HEAD = Pattern.compile("^[a-z]{2,3}$");
    private static final Pattern LANGUAGE_TAIL = Pattern.compile("^(r[a-z]{2}|[a-z]{4}|[0-9]{3}|[a-z]{2})$");
    private static final Pattern NUMERIC_DPI = Pattern.compile("^([0-9]{2,4})dpi$");

    private static final String[] DENSITY_NAMES = {"ldpi", "mdpi", "tvdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi"};
    private static final int[] DENSITY_VALUES = {120, 160, 213, 240, 320, 480, 640};

    private static final String[][] ABI_ALIASES = {
            {"arm64_v8a", "arm64_v8a"}, {"arm64", "arm64_v8a"}, {"aarch64", "arm64_v8a"}, {"armv8", "arm64_v8a"},
            {"armeabi_v7a", "armeabi_v7a"}, {"armeabiv7a", "armeabi_v7a"}, {"armv7a", "armeabi_v7a"}, {"armv7", "armeabi_v7a"},
            {"armeabi", "armeabi"},
            {"x86_64", "x86_64"}, {"x8664", "x86_64"},
            {"x86", "x86"},
            {"mips64", "mips64"}, {"mips", "mips"},
            {"riscv64", "riscv64"}
    };

    private SplitSelector() {}

    public static String normalizeAbi(String value) {
        if (value == null) return "";
        String n = value.toLowerCase(Locale.ROOT).trim().replace('-', '_');
        for (String[] alias : ABI_ALIASES) {
            if (alias[0].equals(n)) return alias[1];
        }
        return n;
    }

    public static String normalizeLanguage(String value) {
        if (value == null) return "";
        String n = value.toLowerCase(Locale.ROOT).trim();
        switch (n) {
            case "in":
                return "id";
            case "iw":
                return "he";
            case "ji":
                return "yi";
            case "tl":
                return "fil";
            default:
                return n;
        }
    }

    private static String abiOf(String qualifier) {
        String n = qualifier.toLowerCase(Locale.ROOT).replace('-', '_');
        for (String[] alias : ABI_ALIASES) {
            if (alias[0].equals(n)) return alias[1];
        }
        return null;
    }

    private static int dpiOf(String qualifier) {
        String n = qualifier.toLowerCase(Locale.ROOT);
        for (int i = 0; i < DENSITY_NAMES.length; i++) {
            if (DENSITY_NAMES[i].equals(n)) return DENSITY_VALUES[i];
        }
        java.util.regex.Matcher matcher = NUMERIC_DPI.matcher(n);
        if (matcher.matches()) {
            try {
                return Integer.parseInt(matcher.group(1));
            } catch (NumberFormatException ignored) {
            }
        }
        return 0;
    }

    private static String languageOf(String qualifier) {
        String n = qualifier.toLowerCase(Locale.ROOT);
        if (n.startsWith("b+")) {
            n = n.substring(2);
        } else if (n.startsWith("b-") || n.startsWith("b_")) {
            n = n.substring(2);
        }
        n = n.replace('+', '-').replace('_', '-');
        String[] tokens = n.split("-");
        if (tokens.length == 0 || tokens.length > 3) return null;
        if (!LANGUAGE_HEAD.matcher(tokens[0]).matches()) return null;
        for (int i = 1; i < tokens.length; i++) {
            if (!LANGUAGE_TAIL.matcher(tokens[i]).matches()) return null;
        }
        return normalizeLanguage(tokens[0]);
    }

    private static String leafOf(String entryName) {
        String n = entryName.replace('\\', '/');
        int slash = n.lastIndexOf('/');
        return slash >= 0 ? n.substring(slash + 1) : n;
    }

    private static boolean isIgnoredPath(String lowerPath) {
        return lowerPath.startsWith("meta-inf/") || lowerPath.contains("/meta-inf/") || lowerPath.startsWith("__macosx/");
    }

    public static List<ApkEntry> build(Map<String, Long> apkEntries, ArchiveKind kind) {
        Map<String, Long> filtered = new LinkedHashMap<>();
        boolean hasSplitsDir = false;
        for (String name : apkEntries.keySet()) {
            String lower = name.replace('\\', '/').toLowerCase(Locale.ROOT);
            if (lower.startsWith("splits/") || lower.contains("/splits/")) {
                hasSplitsDir = true;
                break;
            }
        }

        for (Map.Entry<String, Long> item : apkEntries.entrySet()) {
            String name = item.getKey();
            String lower = name.replace('\\', '/').toLowerCase(Locale.ROOT);
            if (!lower.endsWith(".apk") || isIgnoredPath(lower)) continue;
            if (kind == ArchiveKind.APKS_BUNDLETOOL && hasSplitsDir && !(lower.startsWith("splits/") || lower.contains("/splits/"))) continue;
            filtered.put(name, item.getValue());
        }

        List<ApkEntry> out = new ArrayList<>();
        for (Map.Entry<String, Long> item : filtered.entrySet()) {
            out.add(classify(item.getKey(), item.getValue() == null ? 0L : item.getValue()));
        }
        return out;
    }

    public static ApkEntry classify(String entryName, long size) {
        String leaf = leafOf(entryName);
        String lower = leaf.toLowerCase(Locale.ROOT);
        String stem = lower.endsWith(".apk") ? lower.substring(0, lower.length() - 4) : lower;

        if (CORE_BASE.matcher(stem).matches()) {
            return new ApkEntry(entryName, leaf, size, "", stem, SplitCategory.BASE);
        }
        if (stem.startsWith("standalone")) {
            return new ApkEntry(entryName, leaf, size, "", stem, SplitCategory.BASE);
        }

        String[] prefixes = {"split_config.", "split-config.", "config."};
        for (String prefix : prefixes) {
            if (stem.startsWith(prefix)) {
                return qualified(entryName, leaf, size, "", stem.substring(prefix.length()));
            }
        }

        int configIdx = stem.indexOf(".config.");
        if (configIdx > 0) {
            return qualified(entryName, leaf, size, stem.substring(0, configIdx), stem.substring(configIdx + 8));
        }

        if (stem.startsWith("split_") || stem.startsWith("split-")) {
            return new ApkEntry(entryName, leaf, size, stem, "", SplitCategory.FEATURE);
        }

        int dash = stem.indexOf('-');
        if (dash > 0 && dash < stem.length() - 1) {
            String head = stem.substring(0, dash);
            String tail = stem.substring(dash + 1);
            String group = "base".equals(head) ? "" : head;
            if (tail.matches("^(master|main)([-_.]?[0-9]+)?$")) {
                if (group.isEmpty()) return new ApkEntry(entryName, leaf, size, "", stem, SplitCategory.BASE);
                return new ApkEntry(entryName, leaf, size, group, "", SplitCategory.FEATURE);
            }
            ApkEntry qualified = qualified(entryName, leaf, size, group, tail);
            boolean accept = qualified.category == SplitCategory.ABI
                    || qualified.category == SplitCategory.DENSITY
                    || (qualified.category == SplitCategory.LANGUAGE && !head.contains("."));
            if (accept) return qualified;
        }

        return new ApkEntry(entryName, leaf, size, stem, "", SplitCategory.FEATURE);
    }

    private static ApkEntry qualified(String entryName, String leaf, long size, String group, String qualifier) {
        String abi = abiOf(qualifier);
        if (abi != null) return new ApkEntry(entryName, leaf, size, group, abi, SplitCategory.ABI);
        int dpi = dpiOf(qualifier);
        if (dpi > 0) return new ApkEntry(entryName, leaf, size, group, String.valueOf(dpi), SplitCategory.DENSITY);
        String language = languageOf(qualifier);
        if (language != null) return new ApkEntry(entryName, leaf, size, group, language, SplitCategory.LANGUAGE);
        return new ApkEntry(entryName, leaf, size, group, qualifier, SplitCategory.FEATURE);
    }

    public static void applyDefaults(List<ApkEntry> entries, DeviceProfile profile, ArchiveMetadata meta) {
        if (entries.isEmpty()) return;
        for (ApkEntry entry : entries) entry.checked = false;

        ensureBaseExists(entries, meta);

        ApkEntry base = chooseBase(entries, profile, meta);
        for (ApkEntry entry : entries) {
            if (entry.isBase()) entry.checked = entry == base;
        }

        Map<String, ApkEntry> bestAbi = new LinkedHashMap<>();
        Map<String, ApkEntry> bestDensity = new LinkedHashMap<>();
        for (ApkEntry entry : entries) {
            switch (entry.category) {
                case FEATURE:
                    entry.checked = true;
                    break;
                case LANGUAGE:
                    entry.checked = profile.languages.contains(entry.qualifier);
                    break;
                case ABI: {
                    int rank = profile.abis.indexOf(entry.qualifier);
                    if (rank < 0) break;
                    ApkEntry current = bestAbi.get(entry.group);
                    if (current == null || rank < profile.abis.indexOf(current.qualifier)) {
                        bestAbi.put(entry.group, entry);
                    }
                    break;
                }
                case DENSITY: {
                    ApkEntry current = bestDensity.get(entry.group);
                    if (current == null || densityCloser(entry, current, profile.densityDpi)) {
                        bestDensity.put(entry.group, entry);
                    }
                    break;
                }
                default:
                    break;
            }
        }
        for (ApkEntry entry : bestAbi.values()) entry.checked = true;
        for (ApkEntry entry : bestDensity.values()) entry.checked = true;
    }

    private static boolean densityCloser(ApkEntry candidate, ApkEntry current, int deviceDpi) {
        int c = parseIntSafe(candidate.qualifier);
        int k = parseIntSafe(current.qualifier);
        int dc = Math.abs(c - deviceDpi);
        int dk = Math.abs(k - deviceDpi);
        if (dc != dk) return dc < dk;
        return c > k;
    }

    private static int parseIntSafe(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static void ensureBaseExists(List<ApkEntry> entries, ArchiveMetadata meta) {
        for (ApkEntry entry : entries) {
            if (entry.isBase()) return;
        }

        ApkEntry chosen = null;
        if (meta != null && !meta.baseApkHint.isEmpty()) chosen = findByHint(entries, meta.baseApkHint);
        if (chosen == null && meta != null && !meta.packageName.isEmpty()) chosen = findByHint(entries, meta.packageName + ".apk");
        if (chosen == null) {
            for (ApkEntry entry : entries) {
                if (entry.category != SplitCategory.FEATURE) continue;
                if (chosen == null || entry.size > chosen.size) chosen = entry;
            }
        }
        if (chosen == null) {
            for (ApkEntry entry : entries) {
                if (chosen == null || entry.size > chosen.size) chosen = entry;
            }
        }
        if (chosen != null) chosen.category = SplitCategory.BASE;
    }

    private static ApkEntry findByHint(List<ApkEntry> entries, String hint) {
        String wanted = hint.replace('\\', '/').trim().toLowerCase(Locale.ROOT);
        if (wanted.isEmpty()) return null;
        String wantedLeaf = leafOf(wanted);
        for (ApkEntry entry : entries) {
            String name = entry.entryName.replace('\\', '/').toLowerCase(Locale.ROOT);
            if (name.equals(wanted) || name.endsWith("/" + wanted)) return entry;
        }
        for (ApkEntry entry : entries) {
            if (entry.displayName.toLowerCase(Locale.ROOT).equals(wantedLeaf)) return entry;
        }
        return null;
    }

    public static ApkEntry chooseBase(List<ApkEntry> entries, DeviceProfile profile, ArchiveMetadata meta) {
        List<ApkEntry> candidates = new ArrayList<>();
        for (ApkEntry entry : entries) {
            if (entry.isBase()) candidates.add(entry);
        }
        if (candidates.isEmpty()) return null;

        if (meta != null && !meta.baseApkHint.isEmpty()) {
            ApkEntry hinted = findByHint(candidates, meta.baseApkHint);
            if (hinted != null) return hinted;
        }
        if (meta != null && !meta.packageName.isEmpty()) {
            ApkEntry byPackage = findByHint(candidates, meta.packageName + ".apk");
            if (byPackage != null) return byPackage;
        }

        ApkEntry best = null;
        int bestScore = Integer.MIN_VALUE;
        for (ApkEntry entry : candidates) {
            int score = baseScore(entry, profile);
            if (score > bestScore) {
                bestScore = score;
                best = entry;
            }
        }
        return best;
    }

    private static int baseScore(ApkEntry entry, DeviceProfile profile) {
        String stem = entry.qualifier;
        if (stem.equals("base")) return 1000;
        if (stem.startsWith("base-master") || stem.startsWith("base_master")) return 950;
        if (stem.startsWith("base-main") || stem.startsWith("base_main")) return 940;
        if (stem.equals("universal")) return 900;
        if (stem.equals("main")) return 800;
        if (stem.startsWith("standalone")) return 500 + standaloneScore(stem, profile);
        return 100;
    }

    private static int standaloneScore(String name, DeviceProfile profile) {
        String n = name.replace('-', '_');
        int score = 0;

        String withoutX8664 = n.replace("x86_64", "");
        int bestRank = Integer.MAX_VALUE;
        boolean mentionsAbi = false;
        for (int i = 0; i < profile.abis.size(); i++) {
            String abi = profile.abis.get(i);
            String haystack = abi.equals("x86") ? withoutX8664 : n;
            if (haystack.contains(abi)) {
                bestRank = Math.min(bestRank, i);
                mentionsAbi = true;
            }
        }
        boolean otherAbi = false;
        for (String[] alias : ABI_ALIASES) {
            if (alias[0].equals(alias[1]) && !profile.abis.contains(alias[1])) {
                String haystack = alias[1].equals("x86") ? withoutX8664 : n;
                if (haystack.contains(alias[1])) otherAbi = true;
            }
        }
        if (mentionsAbi) score += 200 - bestRank * 20;
        else if (otherAbi) score -= 400;

        int densityDistance = -1;
        for (int i = DENSITY_NAMES.length - 1; i >= 0; i--) {
            if (n.contains(DENSITY_NAMES[i])) {
                densityDistance = Math.abs(DENSITY_VALUES[i] - profile.densityDpi);
                break;
            }
        }
        if (densityDistance >= 0) score -= densityDistance / 10;
        return score;
    }

    public static void selectBase(List<ApkEntry> entries, ApkEntry chosen) {
        for (ApkEntry entry : entries) {
            if (entry.isBase()) entry.checked = entry == chosen;
        }
    }

    public static ApkEntry selectedBase(List<ApkEntry> entries) {
        for (ApkEntry entry : entries) {
            if (entry.isBase() && entry.checked) return entry;
        }
        return null;
    }

    public static List<ApkEntry> checkedEntries(List<ApkEntry> entries) {
        List<ApkEntry> out = new ArrayList<>();
        ApkEntry base = selectedBase(entries);
        if (base != null) out.add(base);
        for (ApkEntry entry : entries) {
            if (entry.checked && !entry.isBase()) out.add(entry);
        }
        return out;
    }
}
