package com.coara.simpleapkinstaller.util;

public final class UserException extends Exception {
    public final int resId;
    public final Object[] args;
    public final boolean storagePermission;

    public UserException(int resId, Object... args) {
        this(false, resId, args);
    }

    public UserException(boolean storagePermission, int resId, Object... args) {
        super("resId=" + resId);
        this.resId = resId;
        this.args = args;
        this.storagePermission = storagePermission;
    }
}
