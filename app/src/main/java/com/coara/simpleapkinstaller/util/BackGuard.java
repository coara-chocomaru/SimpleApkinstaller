package com.coara.simpleapkinstaller.util;

import android.annotation.TargetApi;
import android.app.Activity;
import android.os.Build;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;

@TargetApi(Build.VERSION_CODES.TIRAMISU)
public final class BackGuard {
    private final Activity activity;
    private final Runnable onBack;
    private OnBackInvokedCallback callback;

    public BackGuard(Activity activity, Runnable onBack) {
        this.activity = activity;
        this.onBack = onBack;
    }

    public void setBlocked(boolean blocked) {
        OnBackInvokedDispatcher dispatcher = activity.getOnBackInvokedDispatcher();
        if (blocked && callback == null) {
            callback = onBack::run;
            dispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback);
        } else if (!blocked && callback != null) {
            dispatcher.unregisterOnBackInvokedCallback(callback);
            callback = null;
        }
    }
}
