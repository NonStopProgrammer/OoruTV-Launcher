package io.freemytv;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** System notices for the main process: the TV booted, or this app was updated. Not exported. */
public class HomeReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)) {
            HomeMode.bootCheck(context);
        } else if (Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            Watchdog.clear(context);    // a new version starts with a clean slate (the last one's crashes aren't its own)
            HomeMode.verify(context);   // app updated: complete OoruTV mode if something is missing
            HomeMode.ensureRemoteService(context, 3_000);
        }
    }
}
