package com.artemis.ondevice;

import android.app.Application;
import android.os.Build;

/** Starts the file log and records crashes before the process dies. */
public class ArtemisApp extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        AppLog.init(this);
        AppLog.i(AppLog.APP, "start: version " + LogExport.versionName(this) + ", " + Build.MANUFACTURER + " "
                + Build.MODEL + ", Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")");
        final Thread.UncaughtExceptionHandler system = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            AppLog.e(AppLog.APP, "crash in thread " + thread.getName(), error);
            AppLog.flush();
            if (system != null) system.uncaughtException(thread, error);
        });
    }
}
