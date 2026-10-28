package com.coara.simpleapkinstaller;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.text.format.Formatter;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.coara.simpleapkinstaller.install.ApkInstaller;
import com.coara.simpleapkinstaller.install.InstallEvents;
import com.coara.simpleapkinstaller.install.InstallPipeline;
import com.coara.simpleapkinstaller.model.ApkEntry;
import com.coara.simpleapkinstaller.util.BackGuard;
import com.coara.simpleapkinstaller.util.BundleLoader;
import com.coara.simpleapkinstaller.util.CancelToken;
import com.coara.simpleapkinstaller.util.FileUtils;
import com.coara.simpleapkinstaller.util.ObbInstaller;
import com.coara.simpleapkinstaller.util.Reporter;
import com.coara.simpleapkinstaller.util.SplitSelector;
import com.coara.simpleapkinstaller.util.UserException;

import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity implements View.OnClickListener, InstallEvents.Listener {
    private static final int REQUEST_PICK = 101;
    private static final int REQUEST_STORAGE_READ = 102;
    private static final int REQUEST_STORAGE_WRITE = 103;
    private static final String JOB_PREFIX = "job_";

    private View root;
    private Button btnSelect;
    private Button btnSelectSplits;
    private Button btnInstall;
    private Button btnOpen;
    private ImageView ivIcon;
    private TextView tvName;
    private TextView tvPackage;
    private TextView tvVersion;
    private TextView tvSize;
    private TextView tvStatus;
    private ProgressBar progress;
    private ProgressBar spinner;
    private TextView tvStep;

    private ExecutorService worker;
    private CancelToken token = new CancelToken();
    private File jobDir;
    private BundleLoader.Loaded loaded;
    private Uri pendingReadUri;

    private volatile boolean busy;
    private volatile boolean awaitingResult;
    private volatile boolean committed;
    private boolean confirmPending;
    private boolean confirmShown;
    private int stepTotal = 3;
    private PackageInstaller.SessionCallback sessionCallback;
    private boolean obbRunning;
    private boolean installed;
    private boolean installPromptShown;
    private int sessionId = -1;
    private Object lastReportKey;

    private BackGuard backGuard;

    private final Reporter reporter = new Reporter() {
        @Override
        public void status(int resId, Object... args) {
            Object[] key = new Object[args.length + 1];
            key[0] = resId;
            System.arraycopy(args, 0, key, 1, args.length);
            Object snapshot = Arrays.asList(key);
            synchronized (MainActivity.this) {
                if (snapshot.equals(lastReportKey)) return;
                lastReportKey = snapshot;
            }
            ui(() -> tvStatus.setText(getString(resId, args)));
        }

        @Override
        public void progress(int percent) {
            ui(() -> {
                if (percent < 0) {
                    progress.setIndeterminate(true);
                } else {
                    progress.setIndeterminate(false);
                    progress.setProgress(percent);
                }
            });
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        root = findViewById(R.id.root);
        btnSelect = findViewById(R.id.btn_select);
        btnSelectSplits = findViewById(R.id.btn_select_splits);
        btnInstall = findViewById(R.id.btn_install);
        btnOpen = findViewById(R.id.btn_open);
        ivIcon = findViewById(R.id.iv_icon);
        tvName = findViewById(R.id.tv_file_name);
        tvPackage = findViewById(R.id.tv_package);
        tvVersion = findViewById(R.id.tv_version);
        tvSize = findViewById(R.id.tv_size);
        tvStatus = findViewById(R.id.tv_status);
        progress = findViewById(R.id.progress);
        spinner = findViewById(R.id.spinner);
        tvStep = findViewById(R.id.tv_step);

        btnSelect.setOnClickListener(this);
        btnSelectSplits.setOnClickListener(this);
        btnInstall.setOnClickListener(this);
        btnOpen.setOnClickListener(this);

        root.setOnApplyWindowInsetsListener((view, insets) -> {
            applyInsets(view, insets);
            return insets;
        });

        worker = Executors.newSingleThreadExecutor();

        if (savedInstanceState == null) {
            clearStaleJobs();
            requestInstallPermissionIfNeeded();
            handleIntent(getIntent());
        }
    }

    @SuppressWarnings("deprecation")
    private void applyInsets(View view, WindowInsets insets) {
        int left;
        int top;
        int right;
        int bottom;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            left = bars.left;
            top = bars.top;
            right = bars.right;
            bottom = bars.bottom;
        } else {
            left = insets.getSystemWindowInsetLeft();
            top = insets.getSystemWindowInsetTop();
            right = insets.getSystemWindowInsetRight();
            bottom = insets.getSystemWindowInsetBottom();
        }
        view.setPadding(left, top, right, bottom);
    }

    private void clearStaleJobs() {
        File cache = getCacheDir();
        File[] children = cache.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (child.getName().startsWith(JOB_PREFIX)) FileUtils.deleteRecursive(child);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        InstallEvents.attach(this);
    }

    @Override
    protected void onStop() {
        InstallEvents.detach(this);
        super.onStop();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    private void registerSessionCallback() {
        if (sessionCallback != null) return;
        sessionCallback = new PackageInstaller.SessionCallback() {
            @Override
            public void onCreated(int id) {
                if (awaitingResult && sessionId == -1 && !committed) sessionId = id;
            }

            @Override
            public void onBadgingChanged(int id) {
            }

            @Override
            public void onActiveChanged(int id, boolean active) {
            }

            @Override
            public void onProgressChanged(int id, float value) {
                if (!awaitingResult || !committed) return;
                if (sessionId != -1 && id != sessionId) return;
                showSystemProgress(value);
            }

            @Override
            public void onFinished(int id, boolean success) {
            }
        };
        getPackageManager().getPackageInstaller().registerSessionCallback(sessionCallback);
    }

    private void setStep(int index, int labelRes) {
        tvStep.setText(getString(R.string.step_line, index, stepTotal, getString(labelRes)));
        tvStep.setVisibility(View.VISIBLE);
    }

    private void setBar(int percent) {
        progress.setVisibility(View.VISIBLE);
        progress.setIndeterminate(false);
        progress.setProgress(Math.max(0, Math.min(100, percent)));
    }

    private void showSystemProgress(float value) {
        boolean installing = value >= 0.89f;
        if (confirmPending) {
            if (!installing) return;
            confirmPending = false;
            confirmShown = false;
        }
        int percent = Math.round(Math.max(0f, Math.min(1f, (value - 0.8f) / 0.2f)) * 100f);
        setStep(3, installing ? R.string.step_install : R.string.step_verify);
        setBar(percent);
        spinner.setVisibility(View.VISIBLE);
        tvStatus.setText(getString(R.string.status_installing, percent));
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (awaitingResult && committed && confirmPending && confirmShown) {
            confirmPending = false;
            confirmShown = false;
            setStep(3, R.string.step_verify);
            setBar(0);
            spinner.setVisibility(View.VISIBLE);
            tvStatus.setText(getString(R.string.status_installing, 0));
        }
    }

    private void unregisterSessionCallback() {
        if (sessionCallback == null) return;
        try {
            getPackageManager().getPackageInstaller().unregisterSessionCallback(sessionCallback);
        } catch (RuntimeException ignored) {
        }
        sessionCallback = null;
    }

    @Override
    protected void onDestroy() {
        unregisterSessionCallback();
        token.cancel();
        worker.shutdownNow();
        setBackBlocked(false);
        if (!awaitingResult && !obbRunning) deleteJobAsync();
        super.onDestroy();
    }

    private void handleIntent(Intent intent) {
        if (intent == null || (intent.getFlags() & Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0) return;
        String action = intent.getAction();
        Uri uri = null;
        if (Intent.ACTION_VIEW.equals(action)) {
            uri = intent.getData();
        } else if (Intent.ACTION_SEND.equals(action)) {
            uri = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                    ? intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri.class)
                    : legacyStream(intent);
        }
        if (uri != null) {
            intent.setAction(Intent.ACTION_MAIN);
            loadUri(uri);
        }
    }

    @SuppressWarnings("deprecation")
    private static Uri legacyStream(Intent intent) {
        Object extra = intent.getParcelableExtra(Intent.EXTRA_STREAM);
        return extra instanceof Uri ? (Uri) extra : null;
    }

    private void ui(Runnable runnable) {
        runOnUiThread(() -> {
            if (!isDestroyed() && !isFinishing()) runnable.run();
        });
    }

    private void toast(int resId) {
        Toast.makeText(this, resId, Toast.LENGTH_LONG).show();
    }

    private void setBusy(boolean value) {
        busy = value;
        btnSelect.setEnabled(!value);
        btnSelectSplits.setEnabled(!value);
        btnInstall.setEnabled(!value && loaded != null && !installed);
        btnOpen.setEnabled(!value);
        progress.setVisibility(value ? View.VISIBLE : View.GONE);
        spinner.setVisibility(View.GONE);
        tvStep.setVisibility(View.GONE);
        if (value) progress.setIndeterminate(true);
        if (value) {
            getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    private boolean isBackBlocked() {
        return awaitingResult || obbRunning;
    }

    private void setBackBlocked(boolean blocked) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return;
        if (backGuard == null) {
            if (!blocked) return;
            backGuard = new BackGuard(this, () -> toast(R.string.toast_busy));
        }
        backGuard.setBlocked(blocked);
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (isBackBlocked()) {
            toast(R.string.toast_busy);
            return;
        }
        super.onBackPressed();
    }

    @Override
    public void onClick(View v) {
        int id = v.getId();
        if (id == R.id.btn_select) {
            openPicker();
        } else if (id == R.id.btn_select_splits) {
            showSplitsDialog();
        } else if (id == R.id.btn_install) {
            onInstallClicked();
        } else if (id == R.id.btn_open) {
            openInstalledApp();
        }
    }

    private void openPicker() {
        if (busy) {
            toast(R.string.toast_busy);
            return;
        }
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        try {
            startActivityForResult(intent, REQUEST_PICK);
        } catch (ActivityNotFoundException e) {
            toast(R.string.toast_unsupported);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_PICK && resultCode == RESULT_OK && data != null && data.getData() != null) {
            loadUri(data.getData());
        }
    }

    private void resetUi() {
        loaded = null;
        installed = false;
        sessionId = -1;
        ivIcon.setImageResource(R.drawable.ic_placeholder);
        tvName.setText(R.string.placeholder_name);
        tvPackage.setText(R.string.placeholder_hint);
        tvVersion.setVisibility(View.GONE);
        tvSize.setVisibility(View.GONE);
        tvStatus.setText("");
        btnSelectSplits.setVisibility(View.GONE);
        btnOpen.setVisibility(View.GONE);
        btnInstall.setEnabled(false);
    }

    private void deleteJobAsync() {
        final File dir = jobDir;
        jobDir = null;
        if (dir == null) return;
        Thread thread = new Thread(() -> FileUtils.deleteRecursive(dir), "cleanup");
        thread.setDaemon(true);
        thread.start();
    }

    private void loadUri(Uri uri) {
        if (busy) {
            toast(R.string.toast_busy);
            return;
        }
        token.cancel();
        token = new CancelToken();
        final CancelToken currentToken = token;
        deleteJobAsync();
        resetUi();
        lastReportKey = null;

        final File dir = new File(getCacheDir(), JOB_PREFIX + System.currentTimeMillis());
        jobDir = dir;
        pendingReadUri = null;
        setBusy(true);
        tvStatus.setText(R.string.status_analyzing);

        worker.execute(() -> {
            try {
                BundleLoader.Loaded result = BundleLoader.load(getApplicationContext(), uri, dir, reporter, currentToken);
                ui(() -> onLoaded(result));
            } catch (UserException e) {
                ui(() -> onLoadFailed(e, uri));
            } catch (RuntimeException e) {
                ui(() -> onLoadFailed(new UserException(R.string.error_read, String.valueOf(e.getMessage())), uri));
            }
        });
    }

    private void onLoaded(BundleLoader.Loaded result) {
        loaded = result;
        installed = false;
        setBusy(false);
        showLoaded();
    }

    private void onLoadFailed(UserException e, Uri uri) {
        setBusy(false);
        deleteJobAsync();
        resetUi();
        if (e.storagePermission && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2
                && checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            pendingReadUri = uri;
            requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, REQUEST_STORAGE_READ);
            return;
        }
        tvStatus.setText(getString(e.resId, e.args));
    }

    private void showLoaded() {
        BundleLoader.Loaded l = loaded;
        if (l == null) return;

        if (l.app.icon != null) ivIcon.setImageDrawable(l.app.icon);
        else ivIcon.setImageResource(R.drawable.ic_placeholder);

        tvName.setText(l.app.label.isEmpty() ? l.fileName : l.app.label);
        tvPackage.setText(l.app.packageName);

        String version = getString(R.string.line_version, l.app.versionName.isEmpty() ? "-" : l.app.versionName, l.app.versionCode);
        if (l.installedVersionName != null) {
            String from = l.installedVersionName.isEmpty() ? String.valueOf(l.installedVersionCode) : l.installedVersionName;
            String to = l.app.versionName.isEmpty() ? String.valueOf(l.app.versionCode) : l.app.versionName;
            version = version + "\n" + getString(R.string.line_update, from, to);
        } else {
            version = version + "\n" + getString(R.string.line_new);
        }
        tvVersion.setText(version);
        tvVersion.setVisibility(View.VISIBLE);

        updateSizeLine();
        btnSelectSplits.setVisibility(l.entries.size() > 1 ? View.VISIBLE : View.GONE);
        btnInstall.setEnabled(!busy && !installed);
        tvStatus.setText(R.string.status_ready);
    }

    private void updateSizeLine() {
        BundleLoader.Loaded l = loaded;
        if (l == null) return;
        long total = 0L;
        int count = 0;
        for (ApkEntry entry : l.entries) {
            if (entry.checked) {
                total += Math.max(0L, entry.size);
                count++;
            }
        }
        String size = Formatter.formatShortFileSize(this, total);
        tvSize.setText(l.entries.size() > 1 ? getString(R.string.summary_bundle, size, count, l.entries.size()) : getString(R.string.summary_single, size));
        tvSize.setVisibility(View.VISIBLE);
    }

    private String labelFor(ApkEntry entry) {
        int baseCount = 0;
        for (ApkEntry e : loaded.entries) {
            if (e.isBase()) baseCount++;
        }
        int tag;
        switch (entry.category) {
            case BASE:
                tag = baseCount > 1 && !entry.checked ? R.string.tag_base_candidate : R.string.tag_base;
                break;
            case LANGUAGE:
                tag = R.string.tag_language;
                break;
            case ABI:
                tag = R.string.tag_abi;
                break;
            case DENSITY:
                tag = R.string.tag_density;
                break;
            default:
                tag = R.string.tag_feature;
                break;
        }
        return getString(tag) + " " + entry.displayName + " (" + Formatter.formatShortFileSize(this, Math.max(0L, entry.size)) + ")";
    }

    private void showSplitsDialog() {
        final BundleLoader.Loaded l = loaded;
        if (l == null || busy) return;

        final List<ApkEntry> entries = l.entries;
        final ApkEntry previousBase = l.baseEntry;
        final CharSequence[] labels = new CharSequence[entries.size()];
        final boolean[] checked = new boolean[entries.size()];
        final boolean[] snapshot = new boolean[entries.size()];
        for (int i = 0; i < entries.size(); i++) {
            labels[i] = labelFor(entries.get(i));
            checked[i] = entries.get(i).checked;
            snapshot[i] = checked[i];
        }

        new AlertDialog.Builder(this)
                .setTitle(R.string.title_choose_apks)
                .setMultiChoiceItems(labels, checked, (dialog, which, isChecked) -> {
                    ApkEntry entry = entries.get(which);
                    android.widget.ListView list = ((AlertDialog) dialog).getListView();
                    if (entry.isBase()) {
                        if (isChecked) {
                            SplitSelector.selectBase(entries, entry);
                            for (int i = 0; i < entries.size(); i++) {
                                if (entries.get(i).isBase()) list.setItemChecked(i, entries.get(i) == entry);
                            }
                        } else {
                            entry.checked = true;
                            list.setItemChecked(which, true);
                            toast(R.string.toast_base_required);
                        }
                    } else {
                        entry.checked = isChecked;
                    }
                })
                .setPositiveButton(R.string.action_ok, (dialog, which) -> onSelectionConfirmed(previousBase))
                .setNegativeButton(R.string.action_cancel, (dialog, which) -> restoreSelection(entries, snapshot))
                .setOnCancelListener(dialog -> restoreSelection(entries, snapshot))
                .show();
    }

    private void restoreSelection(List<ApkEntry> entries, boolean[] snapshot) {
        for (int i = 0; i < entries.size(); i++) entries.get(i).checked = snapshot[i];
        if (loaded != null) loaded.baseEntry = SplitSelector.selectedBase(entries);
        updateSizeLine();
    }

    private void onSelectionConfirmed(ApkEntry previousBase) {
        final BundleLoader.Loaded l = loaded;
        if (l == null) return;
        ApkEntry base = SplitSelector.selectedBase(l.entries);
        if (base == null) {
            SplitSelector.selectBase(l.entries, previousBase);
            base = previousBase;
        }
        l.baseEntry = base;
        updateSizeLine();
        if (base == previousBase) return;

        final File dir = jobDir;
        final CancelToken currentToken = token;
        final ApkEntry newBase = base;
        setBusy(true);
        worker.execute(() -> {
            try {
                BundleLoader.extractMissing(l.source, new File(dir, "apks"), java.util.Collections.singletonList(newBase), reporter, currentToken);
                BundleLoader.refreshApp(getApplicationContext(), l);
                ui(() -> {
                    setBusy(false);
                    showLoaded();
                });
            } catch (UserException e) {
                ui(() -> {
                    setBusy(false);
                    tvStatus.setText(getString(e.resId, e.args));
                });
            } catch (IOException e) {
                ui(() -> {
                    setBusy(false);
                    tvStatus.setText(getString(R.string.error_read, String.valueOf(e.getMessage())));
                });
            }
        });
    }

    private void requestInstallPermissionIfNeeded() {
        if (installPromptShown) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !getPackageManager().canRequestPackageInstalls()) {
            installPromptShown = true;
            showInstallPermissionDialog();
        }
    }

    @android.annotation.TargetApi(Build.VERSION_CODES.O)
    private void showInstallPermissionDialog() {
        if (isFinishing() || isDestroyed()) return;
        new AlertDialog.Builder(this)
                .setTitle(R.string.permission_install_title)
                .setMessage(R.string.permission_install_message)
                .setPositiveButton(R.string.action_open_settings, (dialog, which) -> {
                    Intent intent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName()));
                    startSettings(intent, null);
                })
                .setNegativeButton(R.string.action_close, null)
                .show();
    }

    @android.annotation.TargetApi(Build.VERSION_CODES.R)
    private void showAllFilesDialog() {
        if (isFinishing() || isDestroyed()) return;
        new AlertDialog.Builder(this)
                .setTitle(R.string.permission_files_title)
                .setMessage(R.string.permission_files_message)
                .setPositiveButton(R.string.action_open_settings, (dialog, which) -> {
                    Intent primary = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + getPackageName()));
                    Intent fallback = new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                    startSettings(primary, fallback);
                })
                .setNegativeButton(R.string.action_close, null)
                .show();
    }

    private void startSettings(Intent primary, Intent fallback) {
        try {
            startActivity(primary);
        } catch (RuntimeException first) {
            try {
                if (fallback == null) throw first;
                startActivity(fallback);
            } catch (RuntimeException second) {
                toast(R.string.error_settings);
            }
        }
    }

    private boolean hasObbAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) return Environment.isExternalStorageManager();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        if (requestCode == REQUEST_STORAGE_READ) {
            Uri uri = pendingReadUri;
            pendingReadUri = null;
            if (granted && uri != null) loadUri(uri);
            else toast(R.string.toast_permission_denied);
        } else if (requestCode == REQUEST_STORAGE_WRITE) {
            if (granted) onInstallClicked();
            else toast(R.string.toast_permission_denied);
        }
    }

    private void onInstallClicked() {
        final BundleLoader.Loaded l = loaded;
        if (l == null || busy) return;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !getPackageManager().canRequestPackageInstalls()) {
            showInstallPermissionDialog();
            return;
        }
        if (l.hasObb && !hasObbAccess()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                showAllFilesDialog();
            } else {
                requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQUEST_STORAGE_WRITE);
            }
            return;
        }

        final List<ApkEntry> selected = SplitSelector.checkedEntries(l.entries);
        if (selected.isEmpty() || SplitSelector.selectedBase(l.entries) == null) {
            toast(R.string.toast_no_selection);
            return;
        }

        final ApkEntry base = SplitSelector.selectedBase(l.entries);
        final File dir = jobDir;
        final CancelToken currentToken = token;
        lastReportKey = null;
        sessionId = -1;
        InstallEvents.discardPending();
        setBusy(true);
        committed = false;
        confirmPending = false;
        confirmShown = false;
        stepTotal = l.hasObb ? 4 : 3;
        awaitingResult = true;
        setBackBlocked(true);
        registerSessionCallback();
        setStep(1, R.string.step_prepare);

        worker.execute(() -> {
            try {
                InstallPipeline.Prepared prepared = InstallPipeline.prepare(getApplicationContext(), l.source, dir, selected, base, reporter, currentToken);
                ui(() -> {
                    if (!awaitingResult) return;
                    setStep(2, R.string.step_transfer);
                    setBar(0);
                });
                reporter.status(R.string.status_writing, 0);
                final int[] last = {-1};
                int id = ApkInstaller.commit(getApplicationContext(), prepared.files, prepared.base, prepared.packageName, percent -> {
                    currentToken.check();
                    if (percent != last[0]) {
                        last[0] = percent;
                        reporter.status(R.string.status_writing, percent);
                        reporter.progress(percent);
                    }
                });
                committed = true;
                ui(() -> {
                    sessionId = id;
                    if (!awaitingResult || confirmPending) return;
                    showSystemProgress(0.8f);
                });
            } catch (UserException e) {
                ui(() -> onInstallFailed(getString(R.string.status_failure, getString(e.resId, e.args))));
            } catch (InterruptedIOException e) {
                ui(() -> onInstallFailed(getString(R.string.status_cancelled)));
            } catch (IOException | RuntimeException e) {
                ui(() -> onInstallFailed(getString(R.string.status_failure, getString(R.string.error_session, String.valueOf(e.getMessage())))));
            }
        });
    }

    @Override
    public void onInstallEvent(InstallEvents.Event event) {
        ui(() -> handleEvent(event));
    }

    private void handleEvent(InstallEvents.Event event) {
        if (!awaitingResult) return;
        if (sessionId != -1 && event.sessionId != -1 && event.sessionId != sessionId) return;

        switch (event.status) {
            case android.content.pm.PackageInstaller.STATUS_PENDING_USER_ACTION:
                confirmPending = true;
                setStep(3, R.string.step_confirm);
                setBar(0);
                spinner.setVisibility(View.GONE);
                tvStatus.setText("");
                if (event.confirmIntent != null) {
                    try {
                        event.confirmIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(event.confirmIntent);
                        confirmShown = true;
                    } catch (RuntimeException e) {
                        onInstallFailed(getString(R.string.status_failure, String.valueOf(e.getMessage())));
                    }
                }
                break;
            case android.content.pm.PackageInstaller.STATUS_SUCCESS:
                onInstallSucceeded();
                break;
            default:
                onInstallFailed(getString(R.string.status_failure, failureText(event.status, event.message)));
                break;
        }
    }

    private String failureText(int status, String message) {
        String text;
        switch (status) {
            case android.content.pm.PackageInstaller.STATUS_FAILURE_ABORTED:
                text = getString(R.string.fail_aborted);
                break;
            case android.content.pm.PackageInstaller.STATUS_FAILURE_BLOCKED:
                text = getString(R.string.fail_blocked);
                break;
            case android.content.pm.PackageInstaller.STATUS_FAILURE_CONFLICT:
                text = getString(R.string.fail_conflict);
                break;
            case android.content.pm.PackageInstaller.STATUS_FAILURE_INCOMPATIBLE:
                text = getString(R.string.fail_incompatible);
                break;
            case android.content.pm.PackageInstaller.STATUS_FAILURE_INVALID:
                text = getString(R.string.fail_invalid);
                break;
            case android.content.pm.PackageInstaller.STATUS_FAILURE_STORAGE:
                text = getString(R.string.fail_storage);
                break;
            default:
                text = getString(R.string.fail_unknown, status);
                break;
        }
        if (message != null && !message.trim().isEmpty()) text = text + "\n" + message.trim();
        return text;
    }

    private void onInstallFailed(String text) {
        awaitingResult = false;
        committed = false;
        confirmPending = false;
        confirmShown = false;
        unregisterSessionCallback();
        setBackBlocked(false);
        setBusy(false);
        tvStatus.setText(text);
    }

    private void onInstallSucceeded() {
        awaitingResult = false;
        committed = false;
        confirmPending = false;
        confirmShown = false;
        unregisterSessionCallback();
        final BundleLoader.Loaded l = loaded;
        if (l == null || !l.hasObb) {
            finishSuccess(null);
            return;
        }

        obbRunning = true;
        setBusy(true);
        setStep(4, R.string.step_obb);
        setBar(0);
        lastReportKey = null;
        final long total = obbTotal(l);
        final String packageName = l.app.packageName;
        final CancelToken currentToken = token;

        worker.execute(() -> {
            String error = null;
            try {
                final int[] last = {-1};
                ObbInstaller.install(l.source, l.obbEntries, packageName, done -> {
                    currentToken.check();
                    int percent = total > 0 ? (int) Math.min(100L, done * 100L / total) : 0;
                    if (percent != last[0]) {
                        last[0] = percent;
                        reporter.status(R.string.status_obb, percent);
                        reporter.progress(percent);
                    }
                });
            } catch (IOException | RuntimeException e) {
                error = String.valueOf(e.getMessage());
            }
            final String result = error;
            ui(() -> finishSuccess(result));
        });
    }

    private long obbTotal(BundleLoader.Loaded l) {
        long total = 0L;
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(l.source)) {
            for (String name : l.obbEntries) {
                java.util.zip.ZipEntry entry = zip.getEntry(name);
                if (entry != null && entry.getSize() > 0) total += entry.getSize();
            }
        } catch (IOException ignored) {
        }
        return total;
    }

    private void finishSuccess(String obbError) {
        obbRunning = false;
        installed = true;
        setBackBlocked(false);
        setBusy(false);
        btnInstall.setEnabled(false);
        btnSelectSplits.setVisibility(View.GONE);
        tvStatus.setText(obbError == null ? getString(R.string.status_success) : getString(R.string.status_success_obb_failed, obbError));

        BundleLoader.Loaded l = loaded;
        if (l != null && getPackageManager().getLaunchIntentForPackage(l.app.packageName) != null) {
            btnOpen.setVisibility(View.VISIBLE);
        }
        deleteJobAsync();
    }

    private void openInstalledApp() {
        BundleLoader.Loaded l = loaded;
        if (l == null) return;
        Intent launch = getPackageManager().getLaunchIntentForPackage(l.app.packageName);
        if (launch == null) return;
        try {
            startActivity(launch);
        } catch (RuntimeException e) {
            toast(R.string.toast_unsupported);
        }
    }

}
