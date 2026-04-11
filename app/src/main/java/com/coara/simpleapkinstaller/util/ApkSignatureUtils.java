package com.coara.simpleapkinstaller.util;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import android.text.TextUtils;
import android.util.Base64;

import com.android.apksig.ApkSigner;
import com.android.apksig.ApkVerifier;
import com.coara.simpleapkinstaller.model.ArchiveKind;
import com.coara.simpleapkinstaller.model.SignerMaterials;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class ApkSignatureUtils {
    private ApkSignatureUtils() {}

    public interface StatusReporter {
        void post(String message);
        void toast(String message);
    }

    private static final String SAI_TEST_SIGNER_NAME = "SAI_TEST";
    private static final String SAI_TEST_PRIVATE_KEY_ASSET = "key.pk8";
    private static final String SAI_TEST_CERT_ASSET = "cert.pem";

    public static PackageInfo readArchiveInfo(Context context, File apkFile) {
        if (context == null || apkFile == null) return null;
        try {
            PackageManager pm = context.getPackageManager();
            int flags = PackageManager.GET_ACTIVITIES | PackageManager.GET_META_DATA;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) flags |= PackageManager.GET_SIGNING_CERTIFICATES;
            else {
                @SuppressWarnings("deprecation")
                int legacy = PackageManager.GET_SIGNATURES;
                flags |= legacy;
            }
            return pm.getPackageArchiveInfo(apkFile.getAbsolutePath(), flags);
        } catch (Exception e) {
            return null;
        }
    }

    public static boolean hasValidApkSignature(Context context, File inputApk) {
        if (inputApk == null || !inputApk.exists()) return false;
        try {
            PackageInfo info = readArchiveInfo(context, inputApk);
            if (info != null && !getSigningDigestSet(info).isEmpty()) return true;
        } catch (Exception ignored) {}

        try {
            ApkVerifier verifier = new ApkVerifier.Builder(inputApk)
                    .setMinCheckedPlatformVersion(21)
                    .build();
            ApkVerifier.Result result = verifier.verify();
            return result != null && result.isVerified();
        } catch (Exception e) {
            return false;
        }
    }

    public static List<File> signAllApks(Context context, List<File> input, File cacheDir, StatusReporter reporter) {
        if (context == null || input == null || cacheDir == null || reporter == null) return new ArrayList<>();
        File signedDir = new File(cacheDir, "signed_apks_" + System.currentTimeMillis());
        if (!signedDir.exists() && !signedDir.mkdirs()) {
            reporter.post("署名用フォルダの作成に失敗しました");
            reporter.toast("署名用フォルダを作成できませんでした");
            return new ArrayList<>();
        }

        List<File> prepared = new ArrayList<>();
        try {
            for (int i = 0; i < input.size(); i++) {
                File apk = input.get(i);
                if (apk == null || !apk.exists()) continue;

                reporter.post("署名中 (" + (i + 1) + "/" + input.size() + "): " + apk.getName());
                File signedApk = signApkWithTestKey(context, apk, signedDir);
                if (signedApk == null || !signedApk.exists()) {
                    throw new Exception("署名結果が作成されませんでした: " + apk.getName());
                }
                prepared.add(signedApk);
            }

            if (prepared.isEmpty()) throw new Exception("インストール対象の APK を準備できませんでした");
            ArchiveUtils.sortApks(prepared);
            return prepared;
        } catch (Exception e) {
            e.printStackTrace();
            reporter.post("署名エラー: " + getDeepErrorMessage(e));
            reporter.toast("APK の署名に失敗しました");
            return new ArrayList<>();
        }
    }

    private static File signApkWithTestKey(Context context, File inputApk, File outputDir) throws Exception {
        if (context == null || inputApk == null || !inputApk.exists()) {
            throw new Exception("署名対象の APK が存在しません");
        }
        if (outputDir == null) throw new Exception("出力先フォルダがありません");

        SignerMaterials materials = loadSaiTestSignerMaterials(context);
        if (materials == null || materials.privateKey == null || materials.certificates == null || materials.certificates.isEmpty()) {
            throw new Exception("署名鍵の準備に失敗しました");
        }

        File outputApk = new File(outputDir, inputApk.getName());
        if (outputApk.exists() && !outputApk.delete()) throw new Exception("出力先 APK を削除できませんでした");

        ApkSigner.SignerConfig signerConfig = new ApkSigner.SignerConfig.Builder(
                SAI_TEST_SIGNER_NAME, materials.privateKey, materials.certificates
        ).build();

        ApkSigner signer = new ApkSigner.Builder(Collections.singletonList(signerConfig))
                .setInputApk(inputApk)
                .setOutputApk(outputApk)
                .setMinSdkVersion(24)
                .setV1SigningEnabled(false)
                .setV2SigningEnabled(true)
                .setV3SigningEnabled(true)
                .build();

        signer.sign();
        return outputApk;
    }

    private static SignerMaterials loadSaiTestSignerMaterials(Context context) throws Exception {
        byte[] keyBytes = readAssetRawPk8(context, SAI_TEST_PRIVATE_KEY_ASSET);
        List<X509Certificate> certificates = loadAssetCertificates(context, SAI_TEST_CERT_ASSET);

        if (certificates.isEmpty()) {
            throw new Exception("証明書が見つかりませんでした: " + SAI_TEST_CERT_ASSET);
        }

        KeyFactory keyFactory = KeyFactory.getInstance("RSA");
        PrivateKey privateKey = keyFactory.generatePrivate(new PKCS8EncodedKeySpec(keyBytes));
        return new SignerMaterials(privateKey, certificates);
    }

    private static byte[] readAssetRawPk8(Context context, String assetName) throws Exception {
        try (InputStream is = context.getAssets().open(assetName);
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int len;
            while ((len = is.read(buffer)) > 0) baos.write(buffer, 0, len);
            return baos.toByteArray();
        }
    }

    private static List<X509Certificate> loadAssetCertificates(Context context, String assetName) throws Exception {
        List<X509Certificate> certs = new ArrayList<>();
        CertificateFactory certificateFactory = CertificateFactory.getInstance("X.509");

        try (InputStream is = context.getAssets().open(assetName);
             BufferedReader reader = new BufferedReader(new InputStreamReader(is, Charset.forName("UTF-8")))) {

            String line;
            StringBuilder pemBuilder = new StringBuilder();
            boolean inCert = false;

            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.startsWith("-----BEGIN CERTIFICATE-----")) {
                    inCert = true;
                    pemBuilder = new StringBuilder();
                    continue;
                }
                if (trimmed.startsWith("-----END CERTIFICATE-----")) {
                    if (inCert) {
                        inCert = false;
                        String base64 = pemBuilder.toString().replaceAll("\\s+", "");
                        byte[] derBytes = Base64.decode(base64, Base64.DEFAULT);
                        X509Certificate cert = (X509Certificate) certificateFactory.generateCertificate(new ByteArrayInputStream(derBytes));
                        certs.add(cert);
                    }
                    continue;
                }
                if (inCert) pemBuilder.append(line);
            }
        }
        return certs;
    }

    public static String getDeepErrorMessage(Throwable throwable) {
        if (throwable == null) return "不明なエラー";
        StringBuilder sb = new StringBuilder();
        Throwable current = throwable;
        while (current != null) {
            String msg = current.getMessage();
            if (!TextUtils.isEmpty(msg)) {
                if (sb.length() > 0) sb.append(" / ");
                sb.append(current.getClass().getSimpleName()).append(": ").append(msg);
            } else {
                if (sb.length() > 0) sb.append(" / ");
                sb.append(current.getClass().getSimpleName());
            }
            current = current.getCause();
        }
        return sb.length() > 0 ? sb.toString() : "不明なエラー";
    }

    public static boolean validateSelectedApksConsistency(Context context, List<File> apks, File baseApk, StatusReporter reporter) {
        if (context == null || apks == null || apks.isEmpty() || baseApk == null) return false;
        PackageInfo baseInfo = readArchiveInfo(context, baseApk);
        if (baseInfo == null || TextUtils.isEmpty(baseInfo.packageName)) return true;

        Set<String> baseDigests = getSigningDigestSet(baseInfo);
        for (File apk : apks) {
            if (apk == null || !apk.exists() || apk.equals(baseApk)) continue;
            PackageInfo pi = readArchiveInfo(context, apk);
            if (pi == null || TextUtils.isEmpty(pi.packageName)) continue;
            Set<String> digests = getSigningDigestSet(pi);
            if (!baseDigests.isEmpty() && !digests.isEmpty() && !baseDigests.equals(digests)) {
                if (reporter != null) {
                    reporter.post("エラー: 署名が一致しません: " + apk.getName());
                    reporter.toast("署名が不一致です。再度ファイルを選択してください。");
                }
                return false;
            }
        }
        return true;
    }

    public static boolean validateUpdateCompatibilityIfNeeded(Context context, String packageName, File archiveBaseApk, PackageInfo installedInfo, StatusReporter reporter) {
        if (context == null || TextUtils.isEmpty(packageName) || archiveBaseApk == null || !archiveBaseApk.exists()) return true;
        if (installedInfo == null) return true;

        PackageInfo archiveInfo = readArchiveInfo(context, archiveBaseApk);
        if (archiveInfo == null) return true;

        Set<String> installedDigests = getSigningDigestSet(installedInfo);
        Set<String> archiveDigests = getSigningDigestSet(archiveInfo);
        if (!installedDigests.isEmpty() && !archiveDigests.isEmpty() && !installedDigests.equals(archiveDigests)) {
            String msg = "既存アプリと署名が一致しません。既存アプリをアンインストールしてください。";
            if (reporter != null) {
                reporter.post(msg);
                reporter.toast(msg);
            }
            return false;
        }

        long installedVersion = getVersionCodeCompat(installedInfo);
        long archiveVersion = getVersionCodeCompat(archiveInfo);
        if (installedVersion >= 0 && archiveVersion >= 0 && archiveVersion < installedVersion) {
            String msg = "ダウングレードになるため中止されました";
            if (reporter != null) {
                reporter.post(msg);
                reporter.toast(msg);
            }
            return false;
        }
        return true;
    }

    public static PackageInfo getInstalledPackageInfo(Context context, String packageName) {
        if (context == null || TextUtils.isEmpty(packageName)) return null;
        try {
            PackageManager pm = context.getPackageManager();
            int flags = PackageManager.GET_ACTIVITIES | PackageManager.GET_META_DATA;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) flags |= PackageManager.GET_SIGNING_CERTIFICATES;
            else {
                @SuppressWarnings("deprecation")
                int legacy = PackageManager.GET_SIGNATURES;
                flags |= legacy;
            }
            return pm.getPackageInfo(packageName, flags);
        } catch (Exception e) {
            return null;
        }
    }

    public static long getVersionCodeCompat(PackageInfo info) {
        if (info == null) return -1L;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) return info.getLongVersionCode();
        @SuppressWarnings("deprecation")
        long legacyVersionCode = info.versionCode;
        return legacyVersionCode;
    }

    public static Set<String> getSigningDigestSet(PackageInfo info) {
        Set<String> out = new LinkedHashSet<>();
        if (info == null) return out;
        try {
            Signature[] signatures = null;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && info.signingInfo != null) {
                if (info.signingInfo.hasMultipleSigners()) signatures = info.signingInfo.getApkContentsSigners();
                else signatures = info.signingInfo.getSigningCertificateHistory();
            } else {
                @SuppressWarnings("deprecation")
                Signature[] legacySignatures = info.signatures;
                signatures = legacySignatures;
            }
            if (signatures == null) return out;
            for (Signature s : signatures) {
                if (s == null) continue;
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                out.add(bytesToHex(digest.digest(s.toByteArray())));
            }
        } catch (Exception ignored) {}
        return out;
    }

    private static String bytesToHex(byte[] data) {
        if (data == null) return "";
        StringBuilder sb = new StringBuilder(data.length * 2);
        for (byte b : data) sb.append(String.format(Locale.US, "%02x", b));
        return sb.toString();
    }
}
