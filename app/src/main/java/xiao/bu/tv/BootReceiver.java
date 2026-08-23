package xiao.bu.tv;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/** Starts the TV activity after boot when the user has enabled auto start. */
public final class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "BootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !"android.intent.action.QUICKBOOT_POWERON".equals(action)) {
            return;
        }
        boolean enabled = context.getSharedPreferences(
                MainActivity.PREFERENCES, Context.MODE_PRIVATE)
                .getBoolean(MainActivity.AUTO_START, false);
        if (!enabled) {
            Log.i(TAG, "Ignoring boot broadcast because auto start is disabled");
            return;
        }
        Intent launch = new Intent(context, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        try {
            context.startActivity(launch);
            Log.i(TAG, "Started TV activity after boot action=" + action);
        } catch (RuntimeException error) {
            Log.e(TAG, "Unable to start TV activity after boot", error);
        }
    }
}
