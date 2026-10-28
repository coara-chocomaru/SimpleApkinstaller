package com.coara.simpleapkinstaller.install;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInstaller;

import org.json.JSONArray;
import org.json.JSONException;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public final class InstallStateStore {
    private static final String PREFS = "install_session_state";
    private static final String SESSION_ID = "session_id";
    private static final String JOB_PATH = "job_path";
    private static final String HAS_OBB = "has_obb";
    private static final String PACKAGE_NAME = "package_name";
    private static final String OBB_ENTRIES = "obb_entries";
    private static final String PHASE = "phase";
    private static final String OBB_PROGRESS = "obb_progress";
    private static final String OBB_ERROR = "obb_error";
    private static final String EVENT_STATUS = "event_status";
    private static final String EVENT_SESSION_ID = "event_session_id";
    private static final String EVENT_MESSAGE = "event_message";
    private static final String EVENT_PACKAGE_NAME = "event_package_name";
    private static final String EVENT_CONFIRM_URI = "event_confirm_uri";

    public static final String PHASE_TRANSFER = "transfer";
    public static final String PHASE_COMMITTING = "committing";
    public static final String PHASE_INSTALL = "install";
    public static final String PHASE_OBB = "obb";
    public static final String PHASE_OBB_DONE = "obb_done";
    private static volatile String activeJobPath;
    private static volatile String activeObbJobPath;
    public static final String EXTRA_JOB_PATH = "com.coara.simpleapkinstaller.extra.JOB_PATH";
    public static final String EXTRA_HAS_OBB = "com.coara.simpleapkinstaller.extra.HAS_OBB";

    public static final class State {
        public int sessionId = -1;
        public File jobDir;
        public boolean hasObb;
        public String packageName = "";
        public List<String> obbEntries = new ArrayList<>();
        public String phase = PHASE_INSTALL;
        public String obbError;
        public InstallEvents.Event pendingEvent;
    }

    private InstallStateStore() {}

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static void registerActiveJob(File jobDir) {
        activeJobPath = jobDir == null ? null : jobDir.getAbsolutePath();
    }

    public static String getActiveJobPath() {
        return activeJobPath;
    }

    public static boolean isActiveJob(File jobDir) {
        if (jobDir == null || activeJobPath == null) return false;
        try {
            return jobDir.getCanonicalPath().equals(new File(activeJobPath).getCanonicalPath());
        } catch (java.io.IOException e) {
            return jobDir.getAbsolutePath().equals(activeJobPath);
        }
    }

    public static void clearActiveJob(File jobDir) {
        if (jobDir == null || activeJobPath == null) return;
        try {
            if (jobDir.getCanonicalPath().equals(new File(activeJobPath).getCanonicalPath())) activeJobPath = null;
        } catch (java.io.IOException e) {
            if (jobDir.getAbsolutePath().equals(activeJobPath)) activeJobPath = null;
        }
    }

    public static void registerActiveObbJob(File jobDir) {
        activeObbJobPath = jobDir == null ? null : jobDir.getAbsolutePath();
    }

    public static boolean isActiveObbJob(File jobDir) {
        if (jobDir == null || activeObbJobPath == null) return false;
        try {
            return jobDir.getCanonicalPath().equals(new File(activeObbJobPath).getCanonicalPath());
        } catch (java.io.IOException e) {
            return jobDir.getAbsolutePath().equals(activeObbJobPath);
        }
    }

    public static void clearActiveObbJob(File jobDir) {
        if (jobDir == null || activeObbJobPath == null) return;
        try {
            if (jobDir.getCanonicalPath().equals(new File(activeObbJobPath).getCanonicalPath())) activeObbJobPath = null;
        } catch (java.io.IOException e) {
            if (jobDir.getAbsolutePath().equals(activeObbJobPath)) activeObbJobPath = null;
        }
    }

    public static void saveTransfer(Context context, int sessionId, File jobDir, String packageName, boolean hasObb, List<String> obbEntries) {
        saveState(context, sessionId, jobDir, packageName, hasObb, obbEntries, PHASE_TRANSFER);
    }

    public static void save(Context context, int sessionId, File jobDir, String packageName, boolean hasObb, List<String> obbEntries) {
        saveState(context, sessionId, jobDir, packageName, hasObb, obbEntries, PHASE_INSTALL);
    }

    private static void saveState(Context context, int sessionId, File jobDir, String packageName, boolean hasObb, List<String> obbEntries, String phase) {
        JSONArray entries = new JSONArray();
        if (obbEntries != null) {
            for (String entry : obbEntries) entries.put(entry);
        }
        prefs(context).edit()
                .putInt(SESSION_ID, sessionId)
                .putString(JOB_PATH, jobDir == null ? "" : jobDir.getAbsolutePath())
                .putBoolean(HAS_OBB, hasObb)
                .putString(PACKAGE_NAME, packageName == null ? "" : packageName)
                .putString(OBB_ENTRIES, entries.toString())
                .putString(PHASE, phase)
                .putInt(OBB_PROGRESS, 0)
                .remove(EVENT_STATUS)
                .remove(EVENT_SESSION_ID)
                .remove(EVENT_MESSAGE)
                .remove(EVENT_PACKAGE_NAME)
                .remove(EVENT_CONFIRM_URI)
                .commit();
    }

    public static State read(Context context) {
        SharedPreferences p = prefs(context);
        if (!p.contains(SESSION_ID)) return null;
        State state = new State();
        state.sessionId = p.getInt(SESSION_ID, -1);
        String path = p.getString(JOB_PATH, "");
        if (path != null && !path.isEmpty()) state.jobDir = new File(path);
        state.hasObb = p.getBoolean(HAS_OBB, false);
        state.packageName = p.getString(PACKAGE_NAME, "");
        state.phase = p.getString(PHASE, PHASE_INSTALL);
        state.obbError = p.getString(OBB_ERROR, null);
        try {
            JSONArray entries = new JSONArray(p.getString(OBB_ENTRIES, "[]"));
            for (int i = 0; i < entries.length(); i++) {
                String entry = entries.optString(i, "");
                if (!entry.isEmpty()) state.obbEntries.add(entry);
            }
        } catch (JSONException ignored) {
        }
        state.pendingEvent = readPendingEvent(p);
        return state;
    }

    public static void setPhase(Context context, String phase) {
        SharedPreferences.Editor editor = prefs(context).edit().putString(PHASE, phase);
        if (PHASE_OBB.equals(phase)) editor.putInt(OBB_PROGRESS, 0).remove(OBB_ERROR);
        editor.commit();
    }

    public static void setObbResult(Context context, String error) {
        SharedPreferences.Editor editor = prefs(context).edit().putString(PHASE, PHASE_OBB_DONE);
        if (error == null) editor.remove(OBB_ERROR);
        else editor.putString(OBB_ERROR, error);
        editor.commit();
    }

    public static void setObbProgress(Context context, int percent) {
        prefs(context).edit().putInt(OBB_PROGRESS, Math.max(0, Math.min(100, percent))).apply();
    }

    public static int getObbProgress(Context context) {
        return prefs(context).getInt(OBB_PROGRESS, 0);
    }

    public static void recordEvent(Context context, InstallEvents.Event event) {
        SharedPreferences p = prefs(context);
        if (!p.contains(SESSION_ID)) return;
        int storedSessionId = p.getInt(SESSION_ID, -1);
        if (storedSessionId != -1 && event.sessionId != -1 && storedSessionId != event.sessionId) return;
        String confirmUri = null;
        if (event.confirmIntent != null) {
            try {
                confirmUri = event.confirmIntent.toUri(0);
            } catch (RuntimeException ignored) {
            }
        }
        SharedPreferences.Editor editor = p.edit()
                .putInt(EVENT_STATUS, event.status)
                .putInt(EVENT_SESSION_ID, event.sessionId)
                .putString(EVENT_MESSAGE, event.message)
                .putString(EVENT_PACKAGE_NAME, event.packageName);
        if (confirmUri == null) editor.remove(EVENT_CONFIRM_URI);
        else editor.putString(EVENT_CONFIRM_URI, confirmUri);
        editor.commit();
    }

    public static void clearPendingEvent(Context context) {
        prefs(context).edit()
                .remove(EVENT_STATUS)
                .remove(EVENT_SESSION_ID)
                .remove(EVENT_MESSAGE)
                .remove(EVENT_PACKAGE_NAME)
                .remove(EVENT_CONFIRM_URI)
                .commit();
    }

    public static void clear(Context context) {
        prefs(context).edit().clear().commit();
    }

    private static InstallEvents.Event readPendingEvent(SharedPreferences p) {
        if (!p.contains(EVENT_STATUS)) return null;
        int status = p.getInt(EVENT_STATUS, PackageInstaller.STATUS_FAILURE);
        int sessionId = p.getInt(EVENT_SESSION_ID, -1);
        String message = p.getString(EVENT_MESSAGE, null);
        String packageName = p.getString(EVENT_PACKAGE_NAME, null);
        Intent confirm = null;
        String uri = p.getString(EVENT_CONFIRM_URI, null);
        if (uri != null) {
            try {
                confirm = Intent.parseUri(uri, 0);
            } catch (java.net.URISyntaxException | RuntimeException ignored) {
            }
        }
        return new InstallEvents.Event(sessionId, status, message, packageName, confirm);
    }
}
