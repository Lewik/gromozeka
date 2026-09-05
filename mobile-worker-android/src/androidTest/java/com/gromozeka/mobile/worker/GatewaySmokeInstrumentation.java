package com.gromozeka.mobile.worker;

import android.app.Activity;
import android.app.Instrumentation;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.service.notification.StatusBarNotification;
import org.json.JSONObject;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public final class GatewaySmokeInstrumentation extends Instrumentation {
    @Override
    public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        start();
    }

    @Override
    public void onStart() {
        Bundle result = new Bundle();
        Context context = getTargetContext();
        try {
            AndroidMobileWorkerStorage storage = new AndroidMobileWorkerStorage(context);
            check(storage.readState() == null, "Use a fresh test installation; existing Worker state must not be overwritten");
            String state = "{\"serverUrl\":\"https://127.0.0.1:1\",\"workerId\":\"android-smoke\","
                    + "\"gatewayEnabled\":true,\"outbox\":{\"streamId\":\"smoke-stream\",\"pending\":[],\"latest\":{},\"lastAcknowledgedAt\":null}}";
            storage.writeCredential("android-smoke-test-credential-with-no-real-server");
            storage.writeState(state);
            check(state.equals(new AndroidMobileWorkerStorage(context).readState()), "Encrypted state did not survive adapter recreation");
            byte[] encrypted = Files.readAllBytes(new File(context.getNoBackupFilesDir(), "worker-state.enc").toPath());
            check(!new String(encrypted, StandardCharsets.UTF_8).contains("android-smoke"), "Worker state leaked as plaintext");
            if (Build.VERSION.SDK_INT >= 33) {
                getUiAutomation().executeShellCommand("pm grant " + context.getPackageName() + " android.permission.POST_NOTIFICATIONS").close();
            }
            Intent activityIntent = new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            Activity activity = startActivitySync(activityIntent);
            waitForIdleSync();
            check(awaitNotification(context, true), "Foreground Gateway notification was not shown");
            runOnMainSync(activity::finish);
            waitForIdleSync();
            SystemClock.sleep(1_000);
            check(hasNotification(context), "Closing the activity stopped the independent Worker");
            Intent stop = new Intent(context, AndroidWorkerGatewayService.class)
                    .setAction("com.gromozeka.mobile.worker.DISABLE_COMMANDS");
            context.startService(stop);
            check(awaitNotification(context, false), "Gateway notification remained after disabling commands");
            check(!new JSONObject(storage.readState()).getBoolean("gatewayEnabled"), "Disabling commands was not persisted");
            SystemClock.sleep(1_000);
            check(!hasNotification(context), "Cancelled Gateway recreated its notification");
            result.putString("stream", "Gateway smoke passed: encrypted storage, foreground lifecycle, activity independence, durable disable.\n");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable error) {
            result.putString("stream", error.toString() + "\n");
            result.putString("error", error.toString());
            finish(Activity.RESULT_CANCELED, result);
        }
    }

    private static boolean awaitNotification(Context context, boolean expected) {
        for (int attempt = 0; attempt < 100; attempt++) {
            if (hasNotification(context) == expected) return true;
            SystemClock.sleep(100);
        }
        return false;
    }

    private static boolean hasNotification(Context context) {
        for (StatusBarNotification notification : context.getSystemService(NotificationManager.class).getActiveNotifications()) {
            if (notification.getId() == 27_045) return true;
        }
        return false;
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
