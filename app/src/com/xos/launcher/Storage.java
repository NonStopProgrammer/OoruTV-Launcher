package com.xos.launcher;

import android.content.Context;
import android.content.pm.PackageManager;

import java.io.IOException;

/**
 * Reading the TV's shared folders (Pictures, Download: pictures for custom icons). Android 8 to 12 ask for the
 * storage permission; the TV's own ADB grants it (only when the TV already trusts X-OS: it never makes the TV ask).
 */
final class Storage {
    private Storage() {}

    static final String READ = "android.permission.READ_EXTERNAL_STORAGE";
    private static long triedAt;

    /** Off the UI thread: true when X-OS may read the shared folders (granting it first if needed). */
    static boolean canRead(Context c) {
        if (c.checkSelfPermission(READ) == PackageManager.PERMISSION_GRANTED) return true;
        long now = System.currentTimeMillis();
        if (now - triedAt < 60_000 && now >= triedAt) return false;
        triedAt = now;
        try (Adb adb = Adb.connect(c, 0, null)) {
            adb.shell("pm grant " + c.getPackageName() + " " + READ);
        } catch (IOException | RuntimeException e) {
            AppLog.w("storage permission: " + e.getMessage());
        }
        return c.checkSelfPermission(READ) == PackageManager.PERMISSION_GRANTED;
    }
}
