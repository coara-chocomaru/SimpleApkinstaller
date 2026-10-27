package com.coara.simpleapkinstaller.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class DeviceProfile {
    public final List<String> abis;
    public final int densityDpi;
    public final List<String> languages;

    public DeviceProfile(List<String> abis, int densityDpi, List<String> languages) {
        this.abis = Collections.unmodifiableList(new ArrayList<>(abis));
        this.densityDpi = densityDpi;
        this.languages = Collections.unmodifiableList(new ArrayList<>(languages));
    }
}
