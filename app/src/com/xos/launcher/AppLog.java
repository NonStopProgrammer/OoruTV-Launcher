package com.xos.launcher;

import android.util.Log;

/** The app's log (adb logcat -s XOS). Never logs keys. */
final class AppLog {
    private AppLog() {}

    static void w(String message) {
        Log.i("XOS", message);
    }
}
