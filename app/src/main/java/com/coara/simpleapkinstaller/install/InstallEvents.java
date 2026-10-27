package com.coara.simpleapkinstaller.install;

import android.content.Intent;

import java.util.ArrayList;
import java.util.List;

public final class InstallEvents {
    public interface Listener {
        void onInstallEvent(Event event);
    }

    public static final class Event {
        public final int sessionId;
        public final int status;
        public final String message;
        public final String packageName;
        public final Intent confirmIntent;

        public Event(int sessionId, int status, String message, String packageName, Intent confirmIntent) {
            this.sessionId = sessionId;
            this.status = status;
            this.message = message;
            this.packageName = packageName;
            this.confirmIntent = confirmIntent;
        }
    }

    private static final Object LOCK = new Object();
    private static final List<Event> PENDING = new ArrayList<>();
    private static Listener listener;

    private InstallEvents() {}

    public static void publish(Event event) {
        Listener target;
        synchronized (LOCK) {
            target = listener;
            if (target == null) PENDING.add(event);
        }
        if (target != null) target.onInstallEvent(event);
    }

    public static void attach(Listener newListener) {
        List<Event> drained;
        synchronized (LOCK) {
            listener = newListener;
            drained = new ArrayList<>(PENDING);
            PENDING.clear();
        }
        for (Event event : drained) newListener.onInstallEvent(event);
    }

    public static void detach(Listener oldListener) {
        synchronized (LOCK) {
            if (listener == oldListener) listener = null;
        }
    }

    public static void discardPending() {
        synchronized (LOCK) {
            PENDING.clear();
        }
    }
}
