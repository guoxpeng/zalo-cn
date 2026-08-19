package de.robv.android.xposed.callbacks;

import android.app.Application;

public abstract class XC_LoadPackage extends XCallback {
    public static class LoadPackageParam {
        public String packageName;
        public String processName;
        public ClassLoader classLoader;
        public Application appContext;
        public boolean isFirstApplication;
        public android.content.pm.ApplicationInfo appInfo;
    }
}
