package com.coara.simpleapkinstaller.util;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.graphics.drawable.Drawable;
import android.os.Build;

import java.io.File;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class PackageUtils {
    private PackageUtils() {}

    public static final class AppInfo {
        public String packageName = "";
        public String label = "";
        public String versionName = "";
        public long versionCode = -1L;
        public Drawable icon;
    }

    public static DeviceProfile deviceProfile(Context context) {
        List<String> abis = new ArrayList<>();
        for (String abi : Build.SUPPORTED_ABIS) abis.add(SplitSelector.normalizeAbi(abi));

        List<String> languages = new ArrayList<>();
        android.os.LocaleList locales = context.getResources().getConfiguration().getLocales();
        for (int i = 0; i < locales.size(); i++) {
            String language = SplitSelector.normalizeLanguage(locales.get(i).getLanguage());
            if (!language.isEmpty() && !languages.contains(language)) languages.add(language);
        }
        String fallback = SplitSelector.normalizeLanguage(Locale.getDefault().getLanguage());
        if (!fallback.isEmpty() && !languages.contains(fallback)) languages.add(fallback);

        return new DeviceProfile(abis, context.getResources().getDisplayMetrics().densityDpi, languages);
    }

    @SuppressWarnings("deprecation")
    private static int signatureFlags() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.P ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
    }

    public static PackageInfo archiveInfo(Context context, File apk, boolean withSigners) {
        try {
            int flags = withSigners ? signatureFlags() : 0;
            return context.getPackageManager().getPackageArchiveInfo(apk.getAbsolutePath(), flags);
        } catch (RuntimeException e) {
            return null;
        }
    }

    @SuppressWarnings("deprecation")
    public static PackageInfo installedInfo(Context context, String packageName) {
        if (packageName == null || packageName.isEmpty()) return null;
        try {
            PackageManager pm = context.getPackageManager();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                return pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(signatureFlags()));
            }
            return pm.getPackageInfo(packageName, signatureFlags());
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            return null;
        }
    }

    @SuppressWarnings("deprecation")
    public static long versionCode(PackageInfo info) {
        if (info == null) return -1L;
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.P ? info.getLongVersionCode() : info.versionCode;
    }

    @SuppressWarnings("deprecation")
    public static Set<String> signerDigests(PackageInfo info) {
        Set<String> out = new LinkedHashSet<>();
        if (info == null) return out;
        Signature[] signatures = null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            SigningInfo signingInfo = info.signingInfo;
            if (signingInfo != null) {
                signatures = signingInfo.hasMultipleSigners()
                        ? signingInfo.getApkContentsSigners()
                        : signingInfo.getSigningCertificateHistory();
            }
        } else {
            signatures = info.signatures;
        }
        if (signatures == null) return out;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (Signature signature : signatures) {
                if (signature == null) continue;
                md.reset();
                out.add(hex(md.digest(signature.toByteArray())));
            }
        } catch (java.security.NoSuchAlgorithmException ignored) {
        }
        return out;
    }

    public static boolean signersCompatible(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) return true;
        for (String digest : a) {
            if (b.contains(digest)) return true;
        }
        return false;
    }

    public static AppInfo readApp(Context context, File apk) {
        PackageInfo info = archiveInfo(context, apk, false);
        if (info == null || info.packageName == null || info.packageName.isEmpty()) return null;

        AppInfo app = new AppInfo();
        app.packageName = info.packageName;
        app.versionName = info.versionName == null ? "" : info.versionName;
        app.versionCode = versionCode(info);

        ApplicationInfo ai = info.applicationInfo;
        if (ai != null) {
            ai.sourceDir = apk.getAbsolutePath();
            ai.publicSourceDir = apk.getAbsolutePath();
            PackageManager pm = context.getPackageManager();
            try {
                CharSequence label = ai.loadLabel(pm);
                app.label = label == null ? "" : label.toString();
            } catch (RuntimeException ignored) {
            }
            try {
                app.icon = ai.loadIcon(pm);
            } catch (RuntimeException ignored) {
            }
        }
        return app;
    }

    private static String hex(byte[] data) {
        char[] digits = "0123456789abcdef".toCharArray();
        char[] out = new char[data.length * 2];
        for (int i = 0; i < data.length; i++) {
            out[i * 2] = digits[(data[i] >> 4) & 0xF];
            out[i * 2 + 1] = digits[data[i] & 0xF];
        }
        return new String(out);
    }
}
