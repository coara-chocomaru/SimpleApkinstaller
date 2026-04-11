package com.coara.simpleapkinstaller;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.View;
import android.view.KeyEvent;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.coara.simpleapkinstaller.model.ArchiveKind;
import com.coara.simpleapkinstaller.model.ArchiveMetadata;
import com.coara.simpleapkinstaller.util.ApkSignatureUtils;
import com.coara.simpleapkinstaller.util.ArchiveUtils;
import com.coara.simpleapkinstaller.util.FileUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class MainActivity extends Activity implements View.OnClickListener {

    private static final int REQUEST_CODE_FILE_PICKER = 101;
    private static final String ACTION_INSTALL_COMPLETE = "com.coara.simpleapkinstaller.INSTALL_COMPLETE";

    private static final String KEY_SELECTED_SOURCE_FILE = "selectedSourceFile";
    private static final String KEY_EXTRACTION_DIR = "extractionDir";
    private static final String KEY_BASE_APK_FILE = "baseApkFile";
    private static final String KEY_SELECTED_PACKAGE_NAME = "selectedPackageName";
    private static final String KEY_SELECTED_APP_LABEL = "selectedAppLabel";
    private static final String KEY_IS_ARCHIVE_BUNDLE = "isArchiveBundle";
    private static final String KEY_ARCHIVE_KIND = "archiveKind";
    private static final String KEY_ALL_AVAILABLE_APKS = "allAvailableApks";
    private static final String KEY_SELECTED_APK_STATES = "selectedApkStates";
    private static final String KEY_SELECTED_BASE_APK_FILE = "selectedBaseApkFile";

    private Button btnSelect;
    private Button btnInstall;
    private Button btnSelectSplits;
    private ImageView ivIcon;
    private TextView tvFileName;
    private TextView tvSize;
    private TextView tvStatus;

    private File selectedSourceFile;
    private File extractionDir;
    private File baseApkFile;

    private final List<File> filesToInstall = new ArrayList<>();
    private final List<File> allAvailableApks = new ArrayList<>();
    private final List<File> baseApkCandidates = new ArrayList<>();
    private boolean[] selectedApkStates;
    private boolean isArchiveBundle = false;
    private ArchiveKind archiveKind = ArchiveKind.APK;

    private String selectedPackageName = "";
    private String selectedAppLabel = "";
    private ArchiveMetadata archiveMetadata = new ArchiveMetadata();

    private AlertDialog installLockDialog;
    private boolean installFlowLocked = false;
    private String installLockMessage = "インストール中……";

    private SharedPreferences prefs;

    private final BroadcastReceiver installReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent != null && ACTION_INSTALL_COMPLETE.equals(intent.getAction())) {
                handleInstallResult(intent);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        btnSelect = findViewById(R.id.btn_select);
        btnInstall = findViewById(R.id.btn_install);
        btnSelectSplits = findViewById(R.id.btn_select_splits);
        ivIcon = findViewById(R.id.iv_icon);
        tvFileName = findViewById(R.id.tv_file_name);
        tvSize = findViewById(R.id.tv_size);
        tvStatus = findViewById(R.id.tv_status);

        btnSelect.setOnClickListener(this);
        btnInstall.setOnClickListener(this);
        btnSelectSplits.setOnClickListener(this);

        prefs = getSharedPreferences("device_config", Context.MODE_PRIVATE);
        detectArchIfNeeded();
        cleanupOldCacheFiles();

        if (savedInstanceState != null) {
            restoreInstanceState(savedInstanceState);
        } else {
            resetUIAfterInstall();
        }

        IntentFilter filter = new IntentFilter(ACTION_INSTALL_COMPLETE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(installReceiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(installReceiver, filter);
        }

        ensureStartupGuidance();
        handleIntent(getIntent());
    }

    private void detectArchIfNeeded() {
        if (!prefs.contains("device_arch")) {
            String arch = detectPrimaryArch();
            prefs.edit().putString("device_arch", arch).apply();
        }
    }

    private String detectPrimaryArch() {
        String[] abi64 = Build.SUPPORTED_64_BIT_ABIS;
        if (abi64 != null) {
            for (String abi : abi64) {
                if (abi == null) continue;
                String lower = abi.toLowerCase(Locale.US);
                if (lower.contains("arm64") || lower.contains("aarch64")) return "arm64";
                if (lower.contains("x86_64")) return "x86_64";
            }
        }

        String[] abi32 = Build.SUPPORTED_32_BIT_ABIS;
        if (abi32 != null) {
            for (String abi : abi32) {
                if (abi == null) continue;
                String lower = abi.toLowerCase(Locale.US);
                if (lower.contains("armeabi") || lower.contains("armv7") || lower.contains("arm")) return "arm";
                if (lower.contains("x86")) return "x86";
            }
        }

        String[] abis = Build.SUPPORTED_ABIS;
        if (abis != null && abis.length > 0) {
            String primary = abis[0].toLowerCase(Locale.US);
            if (primary.contains("arm64") || primary.contains("aarch64")) return "arm64";
            if (primary.contains("armeabi") || primary.contains("armv7") || primary.contains("arm")) return "arm";
            if (primary.contains("x86_64")) return "x86_64";
            if (primary.contains("x86")) return "x86";
        }
        return "arm";
    }

    private void cleanupOldCacheFiles() {
        File cacheDir = getCacheDir();
        if (cacheDir == null || !cacheDir.exists()) return;
        File[] files = cacheDir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f == null || !f.exists()) continue;
            if ((extractionDir != null && f.equals(extractionDir)) ||
                (selectedSourceFile != null && f.equals(selectedSourceFile))) {
                continue;
            }
            FileUtils.deleteRecursive(f);
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);

        if (selectedSourceFile != null && selectedSourceFile.exists()) {
            outState.putString(KEY_SELECTED_SOURCE_FILE, selectedSourceFile.getAbsolutePath());
        }
        if (extractionDir != null && extractionDir.exists()) {
            outState.putString(KEY_EXTRACTION_DIR, extractionDir.getAbsolutePath());
        }
        if (baseApkFile != null && baseApkFile.exists()) {
            outState.putString(KEY_BASE_APK_FILE, baseApkFile.getAbsolutePath());
        }

        outState.putString(KEY_SELECTED_PACKAGE_NAME, selectedPackageName);
        outState.putString(KEY_SELECTED_APP_LABEL, selectedAppLabel);
        outState.putBoolean(KEY_IS_ARCHIVE_BUNDLE, isArchiveBundle);
        outState.putInt(KEY_ARCHIVE_KIND, archiveKind.ordinal());

        if (!allAvailableApks.isEmpty()) {
            ArrayList<String> paths = new ArrayList<>();
            for (File f : allAvailableApks) {
                if (f != null && f.exists()) paths.add(f.getAbsolutePath());
            }
            outState.putStringArrayList(KEY_ALL_AVAILABLE_APKS, paths);
        }

        if (selectedApkStates != null) {
            outState.putBooleanArray(KEY_SELECTED_APK_STATES, selectedApkStates);
        }

        if (baseApkFile != null && baseApkFile.exists()) {
            outState.putString(KEY_SELECTED_BASE_APK_FILE, baseApkFile.getAbsolutePath());
        }
    }

    private void restoreInstanceState(Bundle savedInstanceState) {
        String path = savedInstanceState.getString(KEY_SELECTED_SOURCE_FILE);
        if (!TextUtils.isEmpty(path)) selectedSourceFile = new File(path);

        path = savedInstanceState.getString(KEY_EXTRACTION_DIR);
        if (!TextUtils.isEmpty(path)) extractionDir = new File(path);

        path = savedInstanceState.getString(KEY_BASE_APK_FILE);
        if (!TextUtils.isEmpty(path)) baseApkFile = new File(path);

        selectedPackageName = savedInstanceState.getString(KEY_SELECTED_PACKAGE_NAME, "");
        selectedAppLabel = savedInstanceState.getString(KEY_SELECTED_APP_LABEL, "");
        isArchiveBundle = savedInstanceState.getBoolean(KEY_IS_ARCHIVE_BUNDLE, false);

        int kindOrdinal = savedInstanceState.getInt(KEY_ARCHIVE_KIND, ArchiveKind.APK.ordinal());
        archiveKind = ArchiveKind.values()[kindOrdinal];

        ArrayList<String> apkPaths = savedInstanceState.getStringArrayList(KEY_ALL_AVAILABLE_APKS);
        if (apkPaths != null) {
            allAvailableApks.clear();
            for (String p : apkPaths) {
                File f = new File(p);
                if (f.exists()) allAvailableApks.add(f);
            }
        }

        selectedApkStates = savedInstanceState.getBooleanArray(KEY_SELECTED_APK_STATES);

        path = savedInstanceState.getString(KEY_SELECTED_BASE_APK_FILE);
        if (!TextUtils.isEmpty(path)) baseApkFile = new File(path);

        if (selectedSourceFile != null && selectedSourceFile.exists() && baseApkFile != null && baseApkFile.exists()) {
            btnSelectSplits.setVisibility(isArchiveBundle ? View.VISIBLE : View.GONE);
            updateSelectedFileInfo();
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (installFlowLocked && (installLockDialog == null || !installLockDialog.isShowing())) {
            showInstallLockDialog(installLockMessage);
        }
    }

    private void handleIntent(Intent intent) {
        if (intent == null || !Intent.ACTION_VIEW.equals(intent.getAction())) {
            return;
        }
        Uri uri = intent.getData();
        if (uri == null) return;
        processUriAsSelected(uri);
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_CODE_FILE_PICKER || resultCode != RESULT_OK || data == null) {
            return;
        }

        Uri uri = data.getData();
        if (uri == null) return;
        try {
            final int takeFlags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            if (takeFlags != 0) {
                getContentResolver().takePersistableUriPermission(uri, takeFlags);
            }
        } catch (Exception ignored) {
        }

        cleanupTempFiles();
        resetUIAfterInstall();
        processSelectedUri(uri);
    }

    private void processSelectedUri(Uri uri) {
        String originalFileName = FileUtils.getFileName(this, uri);
        final String resolvedFileName = TextUtils.isEmpty(originalFileName) ? "selected_file" : originalFileName;

        final String lowerName = FileUtils.safeLower(resolvedFileName);
        final String safeName = FileUtils.sanitizeFileName(resolvedFileName);

        if (lowerName.endsWith(".apk")) {
            selectedSourceFile = new File(getCacheDir(), safeName);
            postStatus("APK をコピー中...");

            new Thread(() -> {
                if (!FileUtils.copyUriToFile(this, uri, selectedSourceFile)) {
                    postStatus("コピー失敗");
                    toast("ファイルのコピーに失敗しました");
                    selectedSourceFile = null;
                    return;
                }

                isArchiveBundle = false;
                baseApkFile = selectedSourceFile;
                archiveMetadata = new ArchiveMetadata();
                selectedPackageName = "";
                selectedAppLabel = "";

                PackageInfo pi = ApkSignatureUtils.readArchiveInfo(this, baseApkFile);
                if (pi != null && !TextUtils.isEmpty(pi.packageName)) {
                    selectedPackageName = pi.packageName;
                }

                runOnUiThread(this::updateSelectedFileInfo);
            }).start();
            return;
        }

        if (lowerName.endsWith(".xapk") || lowerName.endsWith(".apks") || lowerName.endsWith(".apkm") || lowerName.endsWith(".zip")) {
            selectedSourceFile = new File(getCacheDir(), safeName);
            postStatus("アーカイブをコピー中...");

            new Thread(() -> {
                if (!FileUtils.copyUriToFile(this, uri, selectedSourceFile)) {
                    postStatus("コピー失敗");
                    toast("ファイルのコピーに失敗しました");
                    selectedSourceFile = null;
                    return;
                }
                processArchive(selectedSourceFile);
            }).start();
            return;
        }

        toast("ファイルを選択してください");
        cleanupTempFiles();
    }

    private void processUriAsSelected(Uri uri) {
        if (uri == null) return;

        try {
            getContentResolver().takePersistableUriPermission(uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        } catch (Exception ignored) {
        }

        cleanupTempFiles();
        resetUIAfterInstall();
        processSelectedUri(uri);
    }

    private void processArchive(File archiveFile) {
        postStatus("アーカイブを解析中...");
        archiveKind = ArchiveUtils.detectArchiveKind(archiveFile);
        archiveMetadata = ArchiveUtils.readArchiveMetadataFromZip(archiveFile);

        extractionDir = new File(getCacheDir(), "bundle_extract_" + System.currentTimeMillis());
        if (!extractionDir.mkdirs() && !extractionDir.exists()) {
            postStatus("解凍先の作成に失敗しました");
            cleanupTempFiles();
            return;
        }

        boolean unzipOk;
        if (archiveKind == ArchiveKind.XAPK) {
            unzipOk = ArchiveUtils.safeUnzipApkOnly(archiveFile, extractionDir);
        } else {
            unzipOk = ArchiveUtils.safeUnzip(archiveFile, extractionDir);
        }

        if (!unzipOk) {
            postStatus("解凍失敗");
            cleanupTempFiles();
            return;
        }

        List<File> allApks = ArchiveUtils.dedupeFiles(ArchiveUtils.findInstallableApkFiles(extractionDir, archiveKind));

        if (allApks.isEmpty()) {
            postStatus("APK が見つかりません");
            cleanupTempFiles();
            return;
        }

        Map<String, List<File>> groups = ArchiveUtils.groupApksByPackageName(this, allApks);
        List<File> chosen = ArchiveUtils.chooseBestGroup(groups, archiveMetadata, allApks);
        if (chosen.isEmpty()) {
            postStatus("有効な APK が見つかりません");
            cleanupTempFiles();
            return;
        }

        ArchiveUtils.sortApks(chosen);

        allAvailableApks.clear();
        allAvailableApks.addAll(chosen);

        baseApkCandidates.clear();
        baseApkCandidates.addAll(ArchiveUtils.findBundletoolBaseCandidates(allAvailableApks));
        if (baseApkCandidates.isEmpty()) {
            File fallbackBase = ArchiveUtils.findMainApkFile(chosen, archiveMetadata);
            if (fallbackBase != null) baseApkCandidates.add(fallbackBase);
        }

        baseApkFile = ArchiveUtils.choosePreferredBundletoolBase(baseApkCandidates, archiveMetadata);
        if (baseApkFile == null) {
            baseApkFile = ArchiveUtils.findMainApkFile(chosen, archiveMetadata);
        }
        if (baseApkFile == null && !chosen.isEmpty()) {
            baseApkFile = chosen.get(0);
        }

        if (baseApkFile == null || !baseApkFile.exists()) {
            postStatus("base.apk が特定できません");
            cleanupTempFiles();
            return;
        }

        selectedApkStates = new boolean[allAvailableApks.size()];
        for (int i = 0; i < selectedApkStates.length; i++) selectedApkStates[i] = true;

        applyDefaultApkSelection();

        selectedPackageName = "";
        selectedAppLabel = "";
        readArchiveMetadata(baseApkFile);

        if (TextUtils.isEmpty(selectedPackageName) && !TextUtils.isEmpty(archiveMetadata.packageName)) {
            selectedPackageName = archiveMetadata.packageName;
        }
        if (TextUtils.isEmpty(selectedAppLabel) && !TextUtils.isEmpty(archiveMetadata.appName)) {
            selectedAppLabel = archiveMetadata.appName;
        }

        isArchiveBundle = archiveKind != ArchiveKind.APK;

        if (selectedSourceFile != null && selectedSourceFile.exists() && isArchiveBundle) {
            FileUtils.deleteIfUnderCache(this, selectedSourceFile);
        }

        runOnUiThread(() -> {
            btnSelectSplits.setVisibility(isArchiveBundle ? View.VISIBLE : View.GONE);
            updateSelectedFileInfo();
        });
    }

    private void applyDefaultApkSelection() {
        if (allAvailableApks.isEmpty() || selectedApkStates == null) return;

        List<File> relevantApks = ArchiveUtils.selectDeviceRelevantApks(this, allAvailableApks, archiveKind);
        for (int i = 0; i < allAvailableApks.size(); i++) {
            File apk = allAvailableApks.get(i);
            if (apk == null) continue;

            if (archiveKind == ArchiveKind.APKS_BUNDLETOOL && ArchiveUtils.isBundletoolBaseCandidate(apk)) {
                selectedApkStates[i] = sameFile(apk, baseApkFile);
                continue;
            }

            selectedApkStates[i] = relevantApks.contains(apk);
        }

        if (archiveKind == ArchiveKind.APKS_BUNDLETOOL) {
            ensureSingleBundletoolBaseSelection();
        }
    }

    private boolean isCompatibleWithDevice(String name, String deviceArch) {
        if (name == null) return false;
        String lower = name.toLowerCase(Locale.US);
        if ("arm64".equals(deviceArch)) {
            return lower.contains("arm64") || lower.contains("aarch64") || lower.contains("v8a") || lower.contains("v7a") || lower.contains("armeabi");
        } else if ("arm".equals(deviceArch)) {
            return lower.contains("v7a") || lower.contains("armeabi") || lower.contains("armv7");
        } else if ("x86_64".equals(deviceArch)) {
            return lower.contains("x86_64") || lower.contains("x86-64") || lower.contains("x86");
        } else if ("x86".equals(deviceArch)) {
            return lower.contains("x86");
        }
        return false;
    }

    private void setSelectedBundletoolBase(File selectedApk, AlertDialog dialog) {
        if (selectedApk == null || !selectedApk.exists()) return;
        baseApkFile = selectedApk;

        if (selectedApkStates == null || allAvailableApks.isEmpty()) return;

        for (int i = 0; i < allAvailableApks.size(); i++) {
            File apk = allAvailableApks.get(i);
            if (apk == null) continue;
            if (ArchiveUtils.isBundletoolBaseCandidate(apk)) {
                boolean checked = sameFile(apk, selectedApk);
                selectedApkStates[i] = checked;
                if (dialog != null && dialog.getListView() != null) {
                    dialog.getListView().setItemChecked(i, checked);
                }
            }
        }
        updateSelectedFileInfo();
    }

    private void ensureSingleBundletoolBaseSelection() {
        if (archiveKind != ArchiveKind.APKS_BUNDLETOOL) return;
        if (selectedApkStates == null || allAvailableApks.isEmpty()) return;

        if (baseApkFile == null || !containsFile(allAvailableApks, baseApkFile)) {
            File preferred = ArchiveUtils.choosePreferredBundletoolBase(baseApkCandidates, archiveMetadata);
            if (preferred == null) {
                preferred = ArchiveUtils.findMainApkFile(allAvailableApks, archiveMetadata);
            }
            if (preferred == null && !allAvailableApks.isEmpty()) {
                preferred = allAvailableApks.get(0);
            }
            baseApkFile = preferred;
        }

        for (int i = 0; i < allAvailableApks.size(); i++) {
            File apk = allAvailableApks.get(i);
            if (apk == null) continue;
            if (ArchiveUtils.isBundletoolBaseCandidate(apk)) {
                selectedApkStates[i] = sameFile(apk, baseApkFile);
            }
        }
    }

    private List<File> normalizeBundletoolSelection(List<File> sourceApks) {
        List<File> out = new ArrayList<>();
        if (sourceApks == null || sourceApks.isEmpty()) return out;

        File preferredBase = resolvePreferredBundletoolBase(sourceApks);
        for (File apk : sourceApks) {
            if (apk == null || !apk.exists()) continue;
            if (ArchiveUtils.isBundletoolBaseCandidate(apk)) {
                if (preferredBase != null && sameFile(apk, preferredBase)) {
                    out.add(apk);
                }
            } else {
                out.add(apk);
            }
        }

        if (out.isEmpty() && preferredBase != null) {
            out.add(preferredBase);
        }
        return ArchiveUtils.dedupeFiles(out);
    }

    private File resolvePreferredBundletoolBase(List<File> apks) {
        if (apks == null || apks.isEmpty()) return null;

        if (baseApkFile != null) {
            for (File apk : apks) {
                if (sameFile(apk, baseApkFile)) return apk;
            }
            File hinted = ArchiveUtils.findApkByHint(apks, baseApkFile.getName());
            if (hinted != null && ArchiveUtils.isBundletoolBaseCandidate(hinted)) return hinted;
        }

        List<File> candidates = ArchiveUtils.findBundletoolBaseCandidates(apks);
        File preferred = ArchiveUtils.choosePreferredBundletoolBase(candidates, archiveMetadata);
        if (preferred != null) return preferred;

        for (File apk : apks) {
            if (apk != null && ArchiveUtils.isBundletoolBaseCandidate(apk)) return apk;
        }
        return apks.get(0);
    }

    private boolean containsFile(List<File> files, File target) {
        if (files == null || files.isEmpty() || target == null) return false;
        for (File file : files) {
            if (sameFile(file, target)) return true;
        }
        return false;
    }

    private boolean sameFile(File a, File b) {
        if (a == null || b == null) return false;
        try {
            return a.getCanonicalPath().equals(b.getCanonicalPath());
        } catch (Exception e) {
            return a.getAbsolutePath().equals(b.getAbsolutePath());
        }
    }

    private String buildArchiveDisplayName(File file) {
        if (file == null) return "";
        if (archiveKind == ArchiveKind.APKS_BUNDLETOOL && ArchiveUtils.isBundletoolBaseCandidate(file)) {
            if (sameFile(file, baseApkFile)) {
                return "[BASE] " + file.getName();
            }
            return "[BASE候補] " + file.getName();
        }
        return ArchiveUtils.getCategorizedApkName(file, archiveKind);
    }

    private void showSelectSplitsDialog() {
        if (allAvailableApks == null || allAvailableApks.isEmpty()) return;
        if (selectedApkStates == null || selectedApkStates.length != allAvailableApks.size()) {
            selectedApkStates = new boolean[allAvailableApks.size()];
            applyDefaultApkSelection();
        }

        String[] displayNames = new String[allAvailableApks.size()];
        for (int i = 0; i < allAvailableApks.size(); i++) {
            displayNames[i] = buildArchiveDisplayName(allAvailableApks.get(i));
        }

        new AlertDialog.Builder(this)
                .setTitle("インストールするAPKを選択")
                .setMultiChoiceItems(displayNames, selectedApkStates, (dialog, which, isChecked) -> {
                    File selectedApk = allAvailableApks.get(which);
                    AlertDialog alertDialog = (AlertDialog) dialog;
                    if (selectedApk == null) return;

                    if (archiveKind == ArchiveKind.APKS_BUNDLETOOL && ArchiveUtils.isBundletoolBaseCandidate(selectedApk)) {
                        if (isChecked) {
                            setSelectedBundletoolBase(selectedApk, alertDialog);
                        } else {
                            alertDialog.getListView().setItemChecked(which, true);
                            selectedApkStates[which] = true;
                            Toast.makeText(MainActivity.this, "Base APK は必須です", Toast.LENGTH_SHORT).show();
                        }
                        return;
                    }

                    selectedApkStates[which] = isChecked;
                })
                .setPositiveButton("完了", (dialog, which) -> {
                    if (archiveKind == ArchiveKind.APKS_BUNDLETOOL) {
                        ensureSingleBundletoolBaseSelection();
                    }
                    updateSelectedFileInfo();
                })
                .setNegativeButton("キャンセル", null)
                .show();
    }

    private void updateSelectedFileInfo() {
        if (baseApkFile == null || !baseApkFile.exists()) {
            return;
        }
        if (!isArchiveBundle && (selectedSourceFile == null || !selectedSourceFile.exists())) {
            return;
        }

        if (selectedAppLabel == null) selectedAppLabel = "";
        if (isArchiveBundle) {
            tvFileName.setText((selectedAppLabel.isEmpty() ? selectedSourceFile.getName() : selectedAppLabel) + " (アプリ)");
            long totalSize = 0;
            int count = 0;
            if (allAvailableApks != null && selectedApkStates != null) {
                for (int i = 0; i < allAvailableApks.size(); i++) {
                    if (selectedApkStates[i]) {
                        totalSize += allAvailableApks.get(i).length();
                        count++;
                    }
                }
            }
            tvSize.setText(FileUtils.formatFileSize(totalSize) + " / 選択中: " + count + "個のAPK");
        } else {
            tvFileName.setText(selectedAppLabel.isEmpty() ? selectedSourceFile.getName() : selectedAppLabel);
            tvSize.setText(FileUtils.formatFileSize(selectedSourceFile.length()));
        }

        try {
            PackageInfo pi = ApkSignatureUtils.readArchiveInfo(this, baseApkFile);
            if (pi != null && pi.applicationInfo != null) {
                ApplicationInfo ai = pi.applicationInfo;
                ai.sourceDir = baseApkFile.getAbsolutePath();
                ai.publicSourceDir = baseApkFile.getAbsolutePath();
                Drawable icon = getPackageManager().getApplicationIcon(ai);
                ivIcon.setImageDrawable(icon);
            } else {
                ivIcon.setImageResource(android.R.drawable.sym_def_app_icon);
            }
        } catch (Exception e) {
            ivIcon.setImageResource(android.R.drawable.sym_def_app_icon);
        }

        btnInstall.setEnabled(true);
        tvStatus.setText("インストールの準備が完了しました");
    }

    private void startPackageInstall() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!getPackageManager().canRequestPackageInstalls()) {
                showUnknownInstallDialog();
                toast("不明なアプリのインストール許可が必要です");
                return;
            }
        }

        btnInstall.setEnabled(false);
        postStatus("署名と整合性を確認中...");
        new Thread(() -> {
            List<File> apksForInstall = prepareApksForInstall(filesToInstall);
            if (apksForInstall == null || apksForInstall.isEmpty()) {
                runOnUiThread(() -> btnInstall.setEnabled(true));
                return;
            }

            if (archiveKind == ArchiveKind.APKS_BUNDLETOOL) {
                File keptBase = null;
                for (File apk : apksForInstall) {
                    if (sameFile(apk, baseApkFile)) {
                        keptBase = apk;
                        break;
                    }
                }
                if (keptBase != null) {
                    baseApkFile = keptBase;
                } else {
                    baseApkFile = ArchiveUtils.findMainApkFile(apksForInstall, archiveMetadata);
                    if (baseApkFile == null && !apksForInstall.isEmpty()) baseApkFile = apksForInstall.get(0);
                }
            } else {
                baseApkFile = ArchiveUtils.findMainApkFile(apksForInstall, archiveMetadata);
                if (baseApkFile == null && !apksForInstall.isEmpty()) baseApkFile = apksForInstall.get(0);
            }

            if (baseApkFile == null || !baseApkFile.exists()) {
                postStatus("base.apk を特定できません");
                toast("base APK の特定に失敗しました");
                runOnUiThread(() -> btnInstall.setEnabled(true));
                return;
            }

            if (TextUtils.isEmpty(selectedPackageName)) {
                PackageInfo pi = ApkSignatureUtils.readArchiveInfo(this, baseApkFile);
                if (pi != null && !TextUtils.isEmpty(pi.packageName)) selectedPackageName = pi.packageName;
            }

            if (isArchiveBundle && !ApkSignatureUtils.validateSelectedApksConsistency(this, apksForInstall, baseApkFile, new ApkSignatureUtils.StatusReporter() {
                @Override
                public void post(String message) {
                    postStatus(message);
                }

                @Override
                public void toast(String message) {
                    MainActivity.this.toast(message);
                }
            })) {
                runOnUiThread(() -> btnInstall.setEnabled(true));
                return;
            }

            PackageInfo installedInfo = ApkSignatureUtils.getInstalledPackageInfo(this, selectedPackageName);
            if (!ApkSignatureUtils.validateUpdateCompatibilityIfNeeded(this, selectedPackageName, baseApkFile, installedInfo, new ApkSignatureUtils.StatusReporter() {
                @Override
                public void post(String message) {
                    postStatus(message);
                }

                @Override
                public void toast(String message) {
                    MainActivity.this.toast(message);
                }
            })) {
                runOnUiThread(() -> btnInstall.setEnabled(true));
                return;
            }

            postStatus("インストール処理中...");
            long totalSize = FileUtils.calcTotalSize(apksForInstall);
            PackageInstaller.Session session = null;

            try {
                PackageInstaller packageInstaller = getPackageManager().getPackageInstaller();
                int mode = (installedInfo != null)
                        ? PackageInstaller.SessionParams.MODE_INHERIT_EXISTING
                        : PackageInstaller.SessionParams.MODE_FULL_INSTALL;
                PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(mode);
                params.setSize(totalSize);
                params.setAppPackageName(selectedPackageName);

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    params.setPackageSource(PackageInstaller.PACKAGE_SOURCE_LOCAL_FILE);
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && mode == PackageInstaller.SessionParams.MODE_INHERIT_EXISTING) {
                    params.setDontKillApp(true);
                }

                int sessionId = packageInstaller.createSession(params);
                session = packageInstaller.openSession(sessionId);

                for (File apkFile : apksForInstall) {
                    if (apkFile == null || !apkFile.exists()) continue;
                    String writeName = (apkFile.equals(baseApkFile)) ? "base.apk" : apkFile.getName();
                    try (FileInputStream fis = new FileInputStream(apkFile);
                         OutputStream out = session.openWrite(writeName, 0, apkFile.length())) {
                        byte[] buffer = new byte[8192];
                        int len;
                        while ((len = fis.read(buffer)) > 0) out.write(buffer, 0, len);
                        out.flush();
                        session.fsync(out);
                    }
                }

                session.setStagingProgress(1.0f);
                Intent callbackIntent = new Intent(ACTION_INSTALL_COMPLETE);
                callbackIntent.setPackage(getPackageName());

                int piFlags = PendingIntent.FLAG_UPDATE_CURRENT;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) piFlags |= PendingIntent.FLAG_MUTABLE;

                PendingIntent pendingIntent = PendingIntent.getBroadcast(
                        MainActivity.this, sessionId, callbackIntent, piFlags
                );
                session.commit(pendingIntent.getIntentSender());
                showInstallLockDialog("インストール中……");
                postStatus("インストール要求を送信しました");
            } catch (Exception e) {
                dismissInstallLockDialog();
                final String errorMsg = (e.getMessage() != null) ? e.getMessage() : "不明なエラー";
                postStatus("インストール開始エラー: " + errorMsg);
                toast("インストール開始に失敗しました");
                runOnUiThread(() -> btnInstall.setEnabled(true));
            } finally {
                if (session != null) {
                    try { session.close(); } catch (Exception ignored) {}
                }
            }
        }).start();
    }

    private List<File> prepareApksForInstall(List<File> sourceApks) {
        if (sourceApks == null || sourceApks.isEmpty()) return new ArrayList<>();
        List<File> input = new ArrayList<>();
        for (File apk : sourceApks) {
            if (apk != null && apk.exists()) input.add(apk);
        }
        if (input.isEmpty()) return new ArrayList<>();

        if (archiveKind == ArchiveKind.APKS_BUNDLETOOL) {
            input = normalizeBundletoolSelection(input);
        }

        postStatus("APK の署名状態を確認しています...");
        boolean allSigned = true;
        for (File apk : input) {
            if (!ApkSignatureUtils.hasValidApkSignature(this, apk)) {
                allSigned = false;
                break;
            }
        }

        if (allSigned) {
            if (archiveKind == ArchiveKind.APKS_BUNDLETOOL) {
                postStatus("bundletool 由来の APK Set です。既存署名を保持します。");
            } else if (archiveKind == ArchiveKind.APKM) {
                postStatus("APKM の APK は既存署名を保持します。");
            } else {
                postStatus("署名済みの APK です。再署名はスキップします。");
            }
            ArchiveUtils.sortApks(input);
            return input;
        }

        postStatus("未署名の APK があるため署名します...");
        return ApkSignatureUtils.signAllApks(this, input, getCacheDir(), new ApkSignatureUtils.StatusReporter() {
            @Override
            public void post(String message) {
                postStatus(message);
            }

            @Override
            public void toast(String message) {
                MainActivity.this.toast(message);
            }
        });
    }

    private void readArchiveMetadata(File apkFile) {
        if (apkFile == null) {
            selectedPackageName = "";
            selectedAppLabel = "";
            return;
        }
        try {
            PackageInfo pi = ApkSignatureUtils.readArchiveInfo(this, apkFile);
            if (pi == null || pi.applicationInfo == null) {
                selectedPackageName = archiveMetadata != null ? archiveMetadata.packageName : "";
                selectedAppLabel = archiveMetadata != null ? archiveMetadata.appName : "";
                return;
            }
            selectedPackageName = pi.packageName != null ? pi.packageName : "";
            ApplicationInfo ai = pi.applicationInfo;
            ai.sourceDir = apkFile.getAbsolutePath();
            ai.publicSourceDir = apkFile.getAbsolutePath();
            CharSequence label = null;
            try { label = ai.loadLabel(getPackageManager()); } catch (Exception ignored) {}
            selectedAppLabel = (label != null) ? label.toString() : (archiveMetadata != null ? archiveMetadata.appName : "");
        } catch (Exception e) {
            selectedPackageName = archiveMetadata != null ? archiveMetadata.packageName : "";
            selectedAppLabel = archiveMetadata != null ? archiveMetadata.appName : "";
        }
    }

    private void handleInstallResult(Intent intent) {
        if (intent == null) return;
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        String message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);

        switch (status) {
            case PackageInstaller.STATUS_SUCCESS:
                dismissInstallLockDialog();
                postStatus("インストール成功！");
                toast("アプリが正常にインストールされました");
                resetUIAfterInstall();
                cleanupTempFiles();
                break;
            case PackageInstaller.STATUS_PENDING_USER_ACTION:
                updateInstallLockDialogMessage("インストール処理進行中……");
                Intent confirmationIntent;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    confirmationIntent = intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent.class);
                } else {
                    confirmationIntent = intent.getParcelableExtra(Intent.EXTRA_INTENT);
                }
                if (confirmationIntent != null) {
                    try {
                        confirmationIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(confirmationIntent);
                    } catch (Exception e) {
                        toast("確認画面を起動できませんでした");
                    }
                }
                break;
            case PackageInstaller.STATUS_FAILURE_ABORTED:
                dismissInstallLockDialog();
                postStatus("インストール失敗: 中断されました");
                toast("インストールが中断されました");
                btnInstall.setEnabled(true);
                break;
            case PackageInstaller.STATUS_FAILURE_BLOCKED:
                dismissInstallLockDialog();
                postStatus("インストール失敗: ブロックされました");
                toast("インストールがブロックされました");
                btnInstall.setEnabled(true);
                break;
            case PackageInstaller.STATUS_FAILURE_CONFLICT:
                dismissInstallLockDialog();
                postStatus("インストール失敗: 既存のアプリと署名が競合しています。");
                toast("署名の競合エラーです");
                btnInstall.setEnabled(true);
                break;
            case PackageInstaller.STATUS_FAILURE_INCOMPATIBLE:
                dismissInstallLockDialog();
                postStatus("インストール失敗: 互換性がありません");
                toast("端末との互換性エラーです");
                btnInstall.setEnabled(true);
                break;
            case PackageInstaller.STATUS_FAILURE_INVALID:
                dismissInstallLockDialog();
                postStatus("インストール失敗: APKファイルが不正です");
                toast("無効なAPKファイルです");
                btnInstall.setEnabled(true);
                break;
            case PackageInstaller.STATUS_FAILURE_STORAGE:
                dismissInstallLockDialog();
                postStatus("インストール失敗: ストレージ不足");
                toast("ストレージ容量が不足しています");
                btnInstall.setEnabled(true);
                break;
            default:
                dismissInstallLockDialog();
                String errorDetail = (message != null && !message.isEmpty()) ? message : "詳細不明";
                postStatus("インストール失敗 (" + status + "): " + errorDetail);
                toast("インストールに失敗しました");
                btnInstall.setEnabled(true);
                break;
        }
    }

    private void resetUIAfterInstall() {
        if (btnInstall != null) btnInstall.setEnabled(false);
        if (btnSelectSplits != null) btnSelectSplits.setVisibility(View.GONE);
        if (tvFileName != null) tvFileName.setText("ファイルを選択してください");
        if (tvSize != null) tvSize.setText("");
        if (tvStatus != null) tvStatus.setText("");
        if (ivIcon != null) ivIcon.setImageResource(android.R.drawable.sym_def_app_icon);

        selectedSourceFile = null;
        extractionDir = null;
        baseApkFile = null;
        selectedPackageName = "";
        selectedAppLabel = "";
        archiveMetadata = new ArchiveMetadata();
        isArchiveBundle = false;

        filesToInstall.clear();
        allAvailableApks.clear();
        baseApkCandidates.clear();
        selectedApkStates = null;
    }

    private void cleanupTempFiles() {
        FileUtils.deleteIfUnderCache(this, selectedSourceFile);
        if (extractionDir != null) FileUtils.deleteRecursive(extractionDir);
        extractionDir = null;
    }

    private void postStatus(final String message) {
        runOnUiThread(() -> tvStatus.setText(message));
    }

    private void toast(final String message) {
        runOnUiThread(() -> Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show());
    }

    private void showInstallLockDialog(final String message) {
        runOnUiThread(() -> {
            if (isFinishing()) return;
            installFlowLocked = true;
            installLockMessage = TextUtils.isEmpty(message) ? "インストール中……" : message;

            try {
                if (installLockDialog != null && installLockDialog.isShowing()) {
                    installLockDialog.dismiss();
                }
            } catch (Exception ignored) {
            }

            AlertDialog.Builder builder = new AlertDialog.Builder(this)
                    .setTitle("インストール中")
                    .setMessage(installLockMessage)
                    .setCancelable(false);

            installLockDialog = builder.create();
            installLockDialog.setCanceledOnTouchOutside(false);
            installLockDialog.setOnKeyListener((dialog, keyCode, event) -> keyCode == KeyEvent.KEYCODE_BACK);
            installLockDialog.show();

            try {
                if (installLockDialog.getWindow() != null) {
                    installLockDialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                }
            } catch (Exception ignored) {
            }

            try {
                if (getWindow() != null) {
                    getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                }
            } catch (Exception ignored) {
            }
        });
    }

    private void updateInstallLockDialogMessage(final String message) {
        runOnUiThread(() -> {
            installLockMessage = TextUtils.isEmpty(message) ? installLockMessage : message;
            if (installLockDialog != null && installLockDialog.isShowing()) {
                try {
                    installLockDialog.setMessage(installLockMessage);
                } catch (Exception ignored) {
                }
            }
        });
    }

    private void dismissInstallLockDialog() {
        runOnUiThread(() -> {
            installFlowLocked = false;
            installLockMessage = "インストール中……";
            try {
                if (installLockDialog != null) {
                    installLockDialog.dismiss();
                }
            } catch (Exception ignored) {
            }
            installLockDialog = null;
            try {
                if (getWindow() != null) {
                    getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                }
            } catch (Exception ignored) {
            }
        });
    }

    private void ensureStartupGuidance() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !getPackageManager().canRequestPackageInstalls()) {
            showUnknownInstallDialog();
        }
    }

    private void showUnknownInstallDialog() {
        if (isFinishing()) return;
        new AlertDialog.Builder(this)
                .setTitle("インストール許可が必要です")
                .setMessage("アプリをインストールするには、\"不明なアプリのインストール\" を許可してください。")
                .setPositiveButton("設定を開く", (dialog, which) -> {
                    try {
                        Intent settingsIntent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES);
                        settingsIntent.setData(Uri.parse("package:" + getPackageName()));
                        startActivity(settingsIntent);
                    } catch (Exception e) {
                        toast("設定画面を開けませんでした");
                    }
                })
                .setNegativeButton("閉じる", null)
                .show();
    }

    private void openFilePicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                "application/vnd.android.package-archive",
                "application/octet-stream",
                "application/zip",
                "*/*"
        });
        startActivityForResult(intent, REQUEST_CODE_FILE_PICKER);
    }

    @Override
    public void onClick(View v) {
        if (v.getId() == R.id.btn_select) {
            openFilePicker();
            return;
        }

        if (v.getId() == R.id.btn_select_splits) {
            showSelectSplitsDialog();
            return;
        }

        if (v.getId() == R.id.btn_install) {
            filesToInstall.clear();
            if (isArchiveBundle && archiveKind == ArchiveKind.APKS_BUNDLETOOL) {
                ensureSingleBundletoolBaseSelection();
            }
            if (isArchiveBundle && allAvailableApks != null && selectedApkStates != null) {
                for (int i = 0; i < allAvailableApks.size(); i++) {
                    if (selectedApkStates[i]) filesToInstall.add(allAvailableApks.get(i));
                }
            } else if (!isArchiveBundle && selectedSourceFile != null) {
                filesToInstall.add(selectedSourceFile);
            }

            if (filesToInstall.isEmpty()) {
                toast("インストール対象のAPKが選択されていません");
                return;
            }

            startPackageInstall();
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (installFlowLocked) {
            toast("インストール完了までお待ちください");
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        dismissInstallLockDialog();
        super.onDestroy();
        try {
            unregisterReceiver(installReceiver);
        } catch (Exception ignored) {
        }
        cleanupTempFiles();
    }
}
