package com.coara.simpleapkinstaller.util;

public interface Reporter {
    void status(int resId, Object... args);

    void progress(int percent);
}
