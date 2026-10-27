package com.coara.simpleapkinstaller.util;

import java.io.InterruptedIOException;

public final class CancelToken {
    private volatile boolean cancelled;

    public void cancel() {
        cancelled = true;
    }

    public boolean isCancelled() {
        return cancelled;
    }

    public void check() throws InterruptedIOException {
        if (cancelled) throw new InterruptedIOException("cancelled");
    }
}
