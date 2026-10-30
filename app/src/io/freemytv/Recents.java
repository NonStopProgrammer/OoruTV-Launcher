package io.freemytv;

import android.app.ActivityManager;
import android.app.AppOpsManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Process;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The recent apps switcher (press Home twice, or Control Center › Recent Apps): the apps used lately, newest first,
 * from Android's usage stats. Reading them needs usage access (the GET_USAGE_STATS app op): the installer grants it,
 * and if it's missing the app grants it to itself through the TV's own ADB, only when the TV already trusts the app
 * (it never makes the TV ask). Closing an app is a force stop, as tvOS's swipe up quits one.
 */
final class Recents {
    private Recents() {}

    static final int MAX = 10;
    private static final long WEEK_MS = 7L * 24 * 3600_000;

    /** The recent apps (off the UI thread); null when usage access isn't granted and couldn't be. */
    static List<HomeApps.Item> load(Context c) {
        if (!allowed(c) && !grant(c)) return null;
        UsageStatsManager um = (UsageStatsManager) c.getSystemService(Context.USAGE_STATS_SERVICE);
        if (um == null) return null;
        long now = System.currentTimeMillis();
        Map<String, Long> last = new HashMap<>();
        try {
            UsageEvents events = um.queryEvents(now - WEEK_MS, now);
            UsageEvents.Event e = new UsageEvents.Event();
            while (events != null && events.hasNextEvent()) {
                events.getNextEvent(e);
                if (e.getEventType() == UsageEvents.Event.MOVE_TO_FOREGROUND) last.put(e.getPackageName(), e.getTimeStamp());
            }
            if (last.isEmpty()) {
                for (UsageStats st : um.queryUsageStats(UsageStatsManager.INTERVAL_WEEKLY, now - WEEK_MS, now)) {
                    Long t = last.get(st.getPackageName());
                    if (st.getLastTimeUsed() > 0 && (t == null || st.getLastTimeUsed() > t)) last.put(st.getPackageName(), st.getLastTimeUsed());
                }
            }
        } catch (RuntimeException e) {
            AppLog.w("recents: " + e.getMessage());
            return null;
        }
        PackageManager pm = c.getPackageManager();
        Set<String> skip = new HashSet<>();
        skip.add(c.getPackageName());
        // Home screens (the TV's own included) aren't apps you switch to.
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        for (ResolveInfo ri : pm.queryIntentActivities(home, 0)) skip.add(ri.activityInfo.packageName);
        List<Map.Entry<String, Long>> order = new ArrayList<>(last.entrySet());
        Collections.sort(order, (a, b) -> Long.compare(b.getValue(), a.getValue()));
        List<HomeApps.Item> out = new ArrayList<>();
        for (Map.Entry<String, Long> en : order) {
            String pkg = en.getKey();
            if (skip.contains(pkg) || HomeApps.launchIntent(pm, pkg) == null) continue;
            String label;
            try {
                label = String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)));
            } catch (PackageManager.NameNotFoundException e) {
                continue;
            }
            HomeApps.Item it = HomeApps.app(pkg, label);
            it.tile = HomeApps.bigTile(c, pkg);
            out.add(it);
            if (out.size() >= MAX) break;
        }
        return out;
    }

    static boolean allowed(Context c) {
        try {
            AppOpsManager ops = (AppOpsManager) c.getSystemService(Context.APP_OPS_SERVICE);
            return ops != null && ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(),
                    c.getPackageName()) == AppOpsManager.MODE_ALLOWED;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static boolean grant(Context c) {
        try (Adb adb = Adb.connect(c, 0, null)) {
            adb.shell("appops set " + c.getPackageName() + " GET_USAGE_STATS allow");
        } catch (IOException | RuntimeException e) {
            AppLog.w("recents: usage access: " + e.getMessage());
            return false;
        }
        return allowed(c);
    }

    /** Quits the app: a force stop through the TV's ADB, else the system's kill of its background processes. */
    static boolean close(Context c, String pkg) {
        if (pkg == null || pkg.equals(c.getPackageName())) return false;
        try (Adb adb = Adb.connect(c, 0, null)) {
            adb.shell("am force-stop " + pkg);
            return true;
        } catch (IOException | RuntimeException e) {
            AppLog.w("recents: close " + pkg + ": " + e.getMessage());
        }
        try {
            ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) return false;
            am.killBackgroundProcesses(pkg);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** "1.2 GB of 2 GB free": the TV's memory right now. */
    static String memory(Context c) {
        try {
            ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            return Ui.trf("Memory: {free} of {total} free", "free", gb(mi.availMem), "total", gb(mi.totalMem));
        } catch (RuntimeException e) {
            return "";
        }
    }

    private static String gb(long bytes) {
        return String.format(Locale.ROOT, "%.1f GB", bytes / 1_073_741_824.0);
    }
}
