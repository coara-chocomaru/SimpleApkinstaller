package com.coara.simpleapkinstaller.model;

import java.util.ArrayList;
import java.util.List;

public class ArchiveMetadata {
    public String packageName = "";
    public String appName = "";
    public String versionName = "";
    public String baseApkHint = "";
    public long versionCode = -1L;
    public final List<String> splitHints = new ArrayList<>();
}
