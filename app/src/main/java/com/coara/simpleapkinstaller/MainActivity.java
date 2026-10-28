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
import com.coara.simpleapkinstaller.install.InstallStateStore;
import com.coara.simpleapkinstaller.model.ApkEntry;
import com.coara.simpleapkinstaller.util.BackGuard;
import com.coara.simpleapkinstaller.util.BundleLoader;
import com.coara.simpleapkinstaller.util.CancelToken;
import com.coara.simpleapkinstaller.util.FileUtils;
import com.coara.simpleapkinstaller.util.ObbInstaller;
import com.coara.simpleapkinstaller.util.PackageUtils;
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
    private volatile boolean commitInProgress;
    private volatile boolean activityDestroyed;
    private boolean confirmPending;
    private boolean confirmShown;
    private int pendingUserActionSessionId = -1;
    private int stepTotal = 3;
    private PackageInstaller.SessionCallback sessionCallback;
    private volatile boolean obbRunning;
    private volatile boolean installPreparing;
    private volatile int lastTransferProgress;
    private boolean resumeObbInstall;
    private boolean installed;
    private boolean installPromptShown;
    private volatile int sessionId = -1;
    private int lastSystemProgress;
    private volatile boolean systemSessionFinished;
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
            if (installPreparing) {
                ui(() -> {
                    progress.setVisibility(View.VISIBLE);
                    progress.setIndeterminate(true);
                });
                return;
            }
            ui(() -> {
                progress.setVisibility(View.VISIBLE);
                if (percent < 0) {
                    progress.setIndeterminate(true);
                } else {
                    progress.setIndeterminate(false);
                    progress.setProgress(Math.max(0, Math.min(100, percent)));
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

        boolean restored = restorePendingInstall();
        clearStaleJobs(restored ? jobDir : null);
        if (!restored) requestInstallPermissionIfNeeded();
        if (!restored) handleIntent(getIntent());
        if (resumeObbInstall && loaded != null) startObbInstall(loaded);
    }

    private boolean restorePendingInstall() {
        InstallStateStore.State state = InstallStateStore.read(this);
        if (state == null || state.sessionId < 0) return false;
        File dir = state.jobDir;
        if (dir == null) return false;
        if (InstallStateStore.PHASE_TRANSFER.equals(state.phase) && !InstallStateStore.isActiveJob(dir)) {
            try {
                getPackageManager().getPackageInstaller().abandonSession(state.sessionId);
            } catch (RuntimeException ignored) {
            }
            InstallStateStore.clear(this);
            InstallStateStore.clearActiveJob(dir);
            FileUtils.deleteRecursive(dir);
            return false;
        }
        if (InstallStateStore.PHASE_COMMITTING.equals(state.phase) && !InstallStateStore.isActiveJob(dir)) {
            PackageInstaller.SessionInfo info = null;
            try {
                info = getPackageManager().getPackageInstaller().getSessionInfo(state.sessionId);
            } catch (RuntimeException ignored) {
            }
            boolean commitSubmitted = state.pendingEvent != null || info == null;
            if (!commitSubmitted && info != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) commitSubmitted = info.isSealed();
                else commitSubmitted = info.isActive();
            }
            if (!commitSubmitted) {
                try {
                    getPackageManager().getPackageInstaller().abandonSession(state.sessionId);
                } catch (RuntimeException ignored) {
                }
                InstallStateStore.clear(this);
                InstallStateStore.clearActiveJob(dir);
                FileUtils.deleteRecursive(dir);
                return false;
            }
            InstallStateStore.setPhase(this, InstallStateStore.PHASE_INSTALL);
            state.phase = InstallStateStore.PHASE_INSTALL;
        }
        if (InstallStateStore.PHASE_INSTALL.equals(state.phase) && !InstallStateStore.isActiveJob(dir)) {
            PackageInstaller.SessionInfo info = null;
            try {
                info = getPackageManager().getPackageInstaller().getSessionInfo(state.sessionId);
            } catch (RuntimeException ignored) {
            }
            boolean commitSubmitted = state.pendingEvent != null || info == null;
            if (info != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) commitSubmitted = info.isSealed();
                else commitSubmitted = info.isActive();
            }
            if (!commitSubmitted && state.pendingEvent == null) {
                try {
                    getPackageManager().getPackageInstaller().abandonSession(state.sessionId);
                } catch (RuntimeException ignored) {
                }
                InstallStateStore.clear(this);
                InstallStateStore.clearActiveJob(dir);
                FileUtils.deleteRecursive(dir);
                return false;
            }
            if (!state.hasObb) {
                FileUtils.deleteRecursive(dir);
                InstallStateStore.clearActiveJob(dir);
            } else if (dir.isDirectory()) {
                File[] children = dir.listFiles();
                if (children != null) {
                    for (File child : children) {
                        if (!"source.bin".equals(child.getName())) FileUtils.deleteRecursive(child);
                    }
                }
            }
        }
        jobDir = dir;
        sessionId = state.sessionId;
        committed = InstallStateStore.PHASE_INSTALL.equals(state.phase);
        commitInProgress = InstallStateStore.PHASE_TRANSFER.equals(state.phase)
                || InstallStateStore.PHASE_COMMITTING.equals(state.phase);
        lastSystemProgress = InstallStateStore.PHASE_TRANSFER.equals(state.phase) ? 0 : 50;
        lastTransferProgress = 0;
        installPreparing = false;
        systemSessionFinished = false;
        loaded = new BundleLoader.Loaded();
        loaded.source = new File(dir, "source.bin");
        loaded.fileName = loaded.source.getName();
        loaded.hasObb = state.hasObb;
        stepTotal = state.hasObb ? 4 : 3;
        loaded.obbEntries = new java.util.ArrayList<>(state.obbEntries);
        loaded.app = new PackageUtils.AppInfo();
        loaded.app.packageName = state.packageName == null ? "" : state.packageName;
        loaded.app.label = loaded.app.packageName;
        if (InstallStateStore.PHASE_OBB_DONE.equals(state.phase)) {
            committed = false;
            awaitingResult = false;
            obbRunning = false;
            resumeObbInstall = false;
            finishSuccess(state.obbError);
            return true;
        }
        if (InstallStateStore.PHASE_OBB.equals(state.phase)) {
            committed = false;
            awaitingResult = false;
            obbRunning = false;
            resumeObbInstall = true;
            return true;
        }
        awaitingResult = true;
        resumeObbInstall = false;
        setBusy(true);
        setBackBlocked(true);
        if (InstallStateStore.PHASE_TRANSFER.equals(state.phase)) {
            setStep(2, R.string.step_transfer);
            setBar(0);
            spinner.setVisibility(View.VISIBLE);
            tvStatus.setText(getString(R.string.status_writing, 0));
        } else {
            setStep(3, R.string.step_install);
            setBar(50);
            spinner.setVisibility(View.VISIBLE);
            tvStatus.setText(getString(R.string.status_installing, 50));
        }
        registerSessionCallback();
        try {
            PackageInstaller.SessionInfo info = getPackageManager().getPackageInstaller().getSessionInfo(sessionId);
            if (info != null) {
                if (InstallStateStore.PHASE_TRANSFER.equals(state.phase)) showTransferProgress(info.getProgress());
                else showSystemProgress(info.getProgress());
            }
        } catch (RuntimeException ignored) {
        }
        return true;
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

    private void clearStaleJobs(File preserveDir) {
        File cache = getCacheDir();
        if (preserveDir == null) {
            String activePath = InstallStateStore.getActiveJobPath();
            if (activePath != null) preserveDir = new File(activePath);
            else {
                InstallStateStore.State state = InstallStateStore.read(this);
                if (state != null) preserveDir = state.jobDir;
            }
        }
        File[] children = cache.listFiles();
        if (children == null) return;
        String preservePath = null;
        try {
            if (preserveDir != null) preservePath = preserveDir.getCanonicalPath();
        } catch (IOException ignored) {
        }
        for (File child : children) {
            if (!child.getName().startsWith(JOB_PREFIX)) continue;
            try {
                if (preservePath != null && preservePath.equals(child.getCanonicalPath())) continue;
            } catch (IOException ignored) {
            }
            FileUtils.deleteRecursive(child);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        InstallEvents.attach(this);
        InstallStateStore.State state = InstallStateStore.read(this);
        if (state != null && InstallStateStore.PHASE_OBB_DONE.equals(state.phase)) {
            finishSuccess(state.obbError);
            return;
        }
        if (state != null && state.pendingEvent != null) onInstallEvent(state.pendingEvent);
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
                if (activityDestroyed || isFinishing() || !awaitingResult) return;
                if (sessionId != -1 && id != sessionId) return;
                InstallStateStore.State state = InstallStateStore.read(MainActivity.this);
                if (state == null) return;
                if (InstallStateStore.PHASE_TRANSFER.equals(state.phase)) {
                    showTransferProgress(value);
                    return;
                }
                if (InstallStateStore.PHASE_COMMITTING.equals(state.phase)) {
                    commitInProgress = true;
                    showSystemProgress(value);
                    return;
                }
                committed = true;
                commitInProgress = false;
                showSystemProgress(value);
            }

            @Override
            public void onFinished(int id, boolean success) {
                if (activityDestroyed || isFinishing() || !success || !awaitingResult || (!committed && !commitInProgress)) return;
                if (sessionId != -1 && id != sessionId) return;
                systemSessionFinished = true;
                BundleLoader.Loaded current = loaded;
                if (current == null || !current.hasObb) showSystemProgress(1f);
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

    private void showTransferProgress(float value) {
        if (Float.isNaN(value) || Float.isInfinite(value)) return;
        float clientPart = Math.max(0f, Math.min(1f, value / 0.8f));
        int percent = Math.round(clientPart * 50f);
        percent = Math.max(0, Math.min(50, percent));
        percent = Math.max(lastTransferProgress, percent);
        lastTransferProgress = percent;
        setStep(2, R.string.step_transfer);
        setBar(percent);
        spinner.setVisibility(View.VISIBLE);
        tvStatus.setText(getString(R.string.status_writing, percent));
    }

    private void showSystemProgress(float value) {
        if (Float.isNaN(value) || Float.isInfinite(value)) return;
        float overall = Math.max(0f, Math.min(1f, value));
        boolean installing = overall >= 0.89f;
        if (confirmPending) {
            if (!installing) return;
            confirmPending = false;
            confirmShown = false;
        }
        float systemPart = Math.max(0f, Math.min(1f, (overall - 0.8f) / 0.2f));
        int percent = 50 + Math.round(systemPart * 50f);
        if (!systemSessionFinished && percent >= 100) percent = 99;
        percent = Math.max(lastSystemProgress, Math.min(100, percent));
        lastSystemProgress = percent;
        setStep(3, R.string.step_install);
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
            showSystemProgress(0.8f);
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
        activityDestroyed = true;
        unregisterSessionCallback();
        boolean keepWorking;
        synchronized (this) {
            keepWorking = committed || commitInProgress || obbRunning;
            if (!keepWorking) {
                awaitingResult = false;
                token.cancel();
            }
        }
        if (keepWorking) {
            worker.shutdown();
            if (committed && !commitInProgress) closeLoadedSource();
        } else {
            closeLoadedSource();
            worker.shutdownNow();
            deleteJobAsync();
        }
        setBackBlocked(false);
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
        closeLoadedSource();
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

    private void closeLoadedSource() {
        BundleLoader.Loaded current = loaded;
        if (current != null) current.closeSource();
    }

    private void deleteJobAsync() {
        final File dir = jobDir;
        jobDir = null;
        InstallStateStore.clearActiveJob(dir);
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
        InstallStateStore.registerActiveJob(dir);
        pendingReadUri = null;
        setBusy(true);
        tvStatus.setText(R.string.status_analyzing);

        worker.execute(() -> {
            try {
                BundleLoader.Loaded result = BundleLoader.load(getApplicationContext(), uri, dir, reporter, currentToken);
                runOnUiThread(() -> {
                    if (isDestroyed() || isFinishing()) {
                        result.closeSource();
                        FileUtils.deleteRecursive(dir);
                    } else {
                        onLoaded(result);
                    }
                });
            } catch (UserException e) {
                runOnUiThread(() -> {
                    if (isDestroyed() || isFinishing()) FileUtils.deleteRecursive(dir);
                    else onLoadFailed(e, uri);
                });
            } catch (RuntimeException e) {
                UserException failure = new UserException(R.string.error_read, String.valueOf(e.getMessage()));
                runOnUiThread(() -> {
                    if (isDestroyed() || isFinishing()) FileUtils.deleteRecursive(dir);
                    else onLoadFailed(failure, uri);
                });
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
        pendingUserActionSessionId = -1;
        lastSystemProgress = 0;
        lastTransferProgress = 0;
        systemSessionFinished = false;
        installPreparing = true;
        setBar(0);
        progress.setIndeterminate(true);
        InstallStateStore.clear(this);
        InstallEvents.discardPending();
        setBusy(true);
        committed = false;
        commitInProgress = false;
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
                installPreparing = false;
                lastTransferProgress = 0;
                ui(() -> {
                    if (!awaitingResult) return;
                    setStep(2, R.string.step_transfer);
                    setBar(0);
                    spinner.setVisibility(View.VISIBLE);
                    tvStatus.setText(getString(R.string.status_writing, 0));
                });
                reporter.status(R.string.status_writing, 0);
                final int[] last = {-1};
                currentToken.check();
                synchronized (MainActivity.this) {
                    currentToken.check();
                    commitInProgress = true;
                }
                int id = ApkInstaller.commit(getApplicationContext(), prepared.files, prepared.base, prepared.packageName, dir, l.hasObb, l.obbEntries, percent -> {
                    currentToken.check();
                    if (percent != last[0]) {
                        last[0] = percent;
                        int mapped = percent * 50 / 100;
                        lastTransferProgress = Math.max(lastTransferProgress, mapped);
                        reporter.status(R.string.status_writing, lastTransferProgress);
                        reporter.progress(lastTransferProgress);
                    }
                });
                synchronized (MainActivity.this) {
                    sessionId = id;
                    committed = true;
                    commitInProgress = false;
                }
                if (activityDestroyed) closeLoadedSource();
                InstallEvents.publishTransient(new InstallEvents.Event(id,
                        InstallEvents.STATUS_COMMIT_ACCEPTED, null, prepared.packageName, null));
                ui(() -> {
                    if (!awaitingResult) return;
                    InstallStateStore.State state = InstallStateStore.read(MainActivity.this);
                    if (state != null && state.pendingEvent != null) {
                        onInstallEvent(state.pendingEvent);
                        return;
                    }
                    if (!confirmPending) showSystemProgress(0.8f);
                });
            } catch (UserException e) {
                installPreparing = false;
                commitInProgress = false;
                if (activityDestroyed) {
                    closeLoadedSource();
                    cleanupDestroyedJob(dir, getString(e.resId, e.args));
                }
                else ui(() -> onInstallFailed(getString(R.string.status_failure, getString(e.resId, e.args))));
            } catch (InterruptedIOException e) {
                installPreparing = false;
                commitInProgress = false;
                if (activityDestroyed) {
                    closeLoadedSource();
                    cleanupDestroyedJob(dir, String.valueOf(e.getMessage()));
                }
                else ui(() -> onInstallFailed(getString(R.string.status_cancelled)));
            } catch (IOException | RuntimeException e) {
                installPreparing = false;
                commitInProgress = false;
                if (activityDestroyed) {
                    closeLoadedSource();
                    cleanupDestroyedJob(dir, String.valueOf(e.getMessage()));
                }
                else ui(() -> onInstallFailed(getString(R.string.status_failure, getString(R.string.error_session, String.valueOf(e.getMessage())))));
            }
        });
    }

    @Override
    public void onInstallEvent(InstallEvents.Event event) {
        ui(() -> handleEvent(event));
    }

    private void handleEvent(InstallEvents.Event event) {
        if (event.status == InstallEvents.STATUS_COMMIT_ACCEPTED) {
            if (sessionId < 0 || event.sessionId != sessionId) return;
            committed = true;
            commitInProgress = false;
            InstallStateStore.State accepted = InstallStateStore.read(this);
            if (accepted != null && accepted.pendingEvent != null) handleEvent(accepted.pendingEvent);
            else if (awaitingResult && !confirmPending) showSystemProgress(0.8f);
            return;
        }
        if (event.status == InstallEvents.STATUS_OBB_FINISHED_SUCCESS
                || event.status == InstallEvents.STATUS_OBB_FINISHED_FAILURE) {
            if (sessionId >= 0 && event.sessionId >= 0 && sessionId != event.sessionId) return;
            finishSuccess(event.status == InstallEvents.STATUS_OBB_FINISHED_FAILURE ? event.message : null);
            return;
        }
        InstallStateStore.State stateBeforeEvent = InstallStateStore.read(this);
        if (stateBeforeEvent != null && InstallStateStore.PHASE_OBB.equals(stateBeforeEvent.phase)
                && event.sessionId == stateBeforeEvent.sessionId) return;
        if (!awaitingResult) {
            InstallStateStore.State stored = InstallStateStore.read(this);
            if (stored == null || stored.sessionId < 0 || stored.sessionId != event.sessionId) return;
            if (!restorePendingInstall()) return;
        }
        if (!committed) {
            InstallStateStore.State current = InstallStateStore.read(this);
            if (current != null && (event.sessionId < 0 || current.sessionId == event.sessionId)) {
                InstallStateStore.recordEvent(this, event);
            } else if (sessionId >= 0 && event.sessionId == sessionId) {
                String message = event.message == null || event.message.trim().isEmpty()
                        ? getString(R.string.error_session, "Installation session ended") : event.message;
                onInstallFailed(getString(R.string.status_failure, message));
            }
            return;
        }
        if (sessionId != -1 && event.sessionId != -1 && event.sessionId != sessionId) return;
        InstallStateStore.clearPendingEvent(this);

        switch (event.status) {
            case android.content.pm.PackageInstaller.STATUS_PENDING_USER_ACTION:
                if (pendingUserActionSessionId == event.sessionId) break;
                pendingUserActionSessionId = event.sessionId;
                confirmPending = true;
                setStep(3, R.string.step_confirm);
                lastSystemProgress = Math.max(lastSystemProgress, 50);
                setBar(lastSystemProgress);
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
        installPreparing = false;
        progress.setIndeterminate(false);
        awaitingResult = false;
        committed = false;
        commitInProgress = false;
        confirmPending = false;
        confirmShown = false;
        unregisterSessionCallback();
        InstallStateStore.clear(this);
        setBackBlocked(false);
        setBusy(false);
        if (loaded != null && ((loaded.source == null || !loaded.source.isFile()) || loaded.entries.isEmpty())) {
            closeLoadedSource();
            loaded = null;
            deleteJobAsync();
            btnInstall.setEnabled(false);
            btnSelectSplits.setVisibility(View.GONE);
            btnOpen.setVisibility(View.GONE);
        }
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
        InstallStateStore.clearPendingEvent(this);
        InstallStateStore.setPhase(this, InstallStateStore.PHASE_OBB);
        startObbInstall(l);
    }

    private void startObbInstall(BundleLoader.Loaded l) {
        if (l == null || l.source == null || l.app == null || l.app.packageName == null || l.app.packageName.isEmpty()) {
            finishSuccess("Missing package metadata");
            return;
        }
        resumeObbInstall = false;
        final File dir = jobDir;
        if (InstallStateStore.isActiveObbJob(dir)) {
            obbRunning = true;
            int currentProgress = InstallStateStore.getObbProgress(this);
            setBusy(true);
            setStep(4, R.string.step_obb);
            setBar(currentProgress);
            tvStatus.setText(getString(R.string.status_obb, currentProgress));
            spinner.setVisibility(View.VISIBLE);
            setBackBlocked(true);
            return;
        }
        obbRunning = true;
        InstallStateStore.setObbProgress(this, 0);
        InstallStateStore.registerActiveObbJob(dir);
        setBusy(true);
        setStep(4, R.string.step_obb);
        setBar(0);
        spinner.setVisibility(View.VISIBLE);
        setBackBlocked(true);
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
                        InstallStateStore.setObbProgress(getApplicationContext(), percent);
                        reporter.status(R.string.status_obb, percent);
                        reporter.progress(percent);
                    }
                });
            } catch (IOException | RuntimeException e) {
                error = String.valueOf(e.getMessage());
            }
            final String result = error;
            InstallStateStore.setObbResult(getApplicationContext(), result);
            InstallStateStore.clearActiveObbJob(dir);
            InstallStateStore.clearActiveJob(dir);
            FileUtils.deleteRecursive(dir);
            if (activityDestroyed) {
                InstallEvents.publishTransient(new InstallEvents.Event(sessionId,
                        result == null ? InstallEvents.STATUS_OBB_FINISHED_SUCCESS : InstallEvents.STATUS_OBB_FINISHED_FAILURE,
                        result, packageName, null));
            } else {
                ui(() -> finishSuccess(result));
            }
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
        committed = false;
        commitInProgress = false;
        installed = true;
        InstallStateStore.clear(this);
        setBackBlocked(false);
        setBusy(false);
        setBar(100);
        btnInstall.setEnabled(false);
        btnSelectSplits.setVisibility(View.GONE);
        tvStatus.setText(obbError == null ? getString(R.string.status_success) : getString(R.string.status_success_obb_failed, obbError));

        BundleLoader.Loaded l = loaded;
        if (l != null && getPackageManager().getLaunchIntentForPackage(l.app.packageName) != null) {
            btnOpen.setVisibility(View.VISIBLE);
        }
        closeLoadedSource();
        deleteJobAsync();
    }

    private void cleanupDestroyedJob(File dir, String message) {
        InstallStateStore.State state = InstallStateStore.read(getApplicationContext());
        int failedSession = state == null ? sessionId : state.sessionId;
        String packageName = state == null ? null : state.packageName;
        InstallStateStore.clear(getApplicationContext());
        InstallStateStore.clearActiveJob(dir);
        FileUtils.deleteRecursive(dir);
        if (failedSession >= 0) {
            InstallEvents.publish(new InstallEvents.Event(failedSession,
                    PackageInstaller.STATUS_FAILURE, message, packageName, null));
        }
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
