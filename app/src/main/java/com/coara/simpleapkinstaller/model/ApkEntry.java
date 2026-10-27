package com.coara.simpleapkinstaller.model;

import java.io.File;

public final class ApkEntry {
    public final String entryName;
    public final String displayName;
    public final long size;
    public final String group;
    public final String qualifier;
    public SplitCategory category;
    public boolean checked;
    public File file;

    public ApkEntry(String entryName, String displayName, long size, String group, String qualifier, SplitCategory category) {
        this.entryName = entryName;
        this.displayName = displayName;
        this.size = size;
        this.group = group;
        this.qualifier = qualifier;
        this.category = category;
    }

    public boolean isBase() {
        return category == SplitCategory.BASE;
    }
}
