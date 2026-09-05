package com.gromozeka.mobile.worker;

import android.app.Activity;
import android.app.Instrumentation;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.service.notification.StatusBarNotification;
import org.json.JSONObject;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public final class GatewaySmokeInstrumentation extends Instrumentation {
    private boolean lifecycleSetup;
    private boolean locationSetup;

    @Override
    public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        lifecycleSetup = arguments != null && "true".equals(arguments.getString("lifecycleSetup"));
        locationSetup = arguments != null && "true".equals(arguments.getString("locationSetup"));
        start();
    }

    @Override
    public void onStart() {
        Bundle result = new Bundle();
        Context context = getTargetContext();
        try {
            AndroidMobileWorkerStorage storage = new AndroidMobileWorkerStorage(context);
            check(storage.readState() == null, "Use a fresh test installation; existing Worker state must not be overwritten");
            if (lifecycleSetup) {
                check(context.getPackageName().endsWith(".lifecycle"), "Lifecycle setup requires the isolated test application");
                storage.writeCredential("android-lifecycle-fixture-credential");
                storage.writeState("{\"serverUrl\":\"https://10.0.2.2:18876\",\"workerId\":\"android-lifecycle\","
                        + (locationSetup ? "\"locationConfiguration\":{\"enabled\":false,\"intervalSeconds\":1,\"minimumDistanceMeters\":0}," : "")
                        + "\"gatewayEnabled\":true,\"soundEnabled\":true,\"outbox\":{\"streamId\":\"lifecycle-stream\",\"pending\":[],\"latest\":{},\"lastAcknowledgedAt\":null}}");
                if (Build.VERSION.SDK_INT >= 33) shell("pm grant " + context.getPackageName() + " android.permission.POST_NOTIFICATIONS");
                result.putString("stream", "Lifecycle fixture enrollment prepared.\n");
                finish(Activity.RESULT_OK, result);
                return;
            }
            String state = "{\"serverUrl\":\"https://127.0.0.1:1\",\"workerId\":\"android-smoke\","
                    + "\"gatewayEnabled\":true,\"outbox\":{\"streamId\":\"smoke-stream\",\"pending\":[],\"latest\":{},\"lastAcknowledgedAt\":null}}";
            storage.writeCredential("android-smoke-test-credential-with-no-real-server");
            storage.writeState(state);
            check(state.equals(new AndroidMobileWorkerStorage(context).readState()), "Encrypted state did not survive adapter recreation");
            byte[] encrypted = Files.readAllBytes(new File(context.getNoBackupFilesDir(), "worker-state.enc").toPath());
            check(!new String(encrypted, StandardCharsets.UTF_8).contains("android-smoke"), "Worker state leaked as plaintext");
            if (Build.VERSION.SDK_INT >= 33) {
                shell("pm grant " + context.getPackageName() + " android.permission.POST_NOTIFICATIONS");
            }
            Intent activityIntent = new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            Activity activity = startActivitySync(activityIntent);
            waitForIdleSync();
            check(awaitNotification(context, true), "Foreground Gateway notification was not shown");
            runOnMainSync(activity::finish);
            waitForIdleSync();
            SystemClock.sleep(1_000);
            check(hasNotification(context), "Closing the activity stopped the independent Worker");
            verifySound(context, storage);
            Intent stop = new Intent(context, AndroidWorkerGatewayService.class)
                    .setAction("com.gromozeka.mobile.worker.DISABLE_COMMANDS");
            context.startService(stop);
            check(awaitNotification(context, false), "Gateway notification remained after disabling commands");
            check(!new JSONObject(storage.readState()).getBoolean("gatewayEnabled"), "Disabling commands was not persisted");
            SystemClock.sleep(1_000);
            check(!hasNotification(context), "Cancelled Gateway recreated its notification");
            result.putString("stream", "Gateway smoke passed: encrypted storage, foreground lifecycle, activity independence, sound opt-in, bounded playback, notification stop, volume restoration, durable disable.\n");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable error) {
            result.putString("stream", error.toString() + "\n");
            result.putString("error", error.toString());
            finish(Activity.RESULT_CANCELED, result);
        }
    }

    private void verifySound(Context context, AndroidMobileWorkerStorage storage) throws Exception {
        AudioManager audio = context.getSystemService(AudioManager.class);
        int original = audio.getStreamVolume(AudioManager.STREAM_ALARM);
        int maximum = audio.getStreamMaxVolume(AudioManager.STREAM_ALARM);
        int quiet = Math.max(1, maximum - 2);
        try {
            audio.setStreamVolume(AudioManager.STREAM_ALARM, quiet, 0);
            Intent test = new Intent(context, AndroidWorkerGatewayService.class)
                    .setAction("com.gromozeka.mobile.worker.TEST_SOUND");
            context.startService(test);
            SystemClock.sleep(500);
            check(audio.getStreamVolume(AudioManager.STREAM_ALARM) == quiet, "Disabled sound changed alarm volume");
            check(!soundPlaying(context), "Sound kept playing without opt-in");
            JSONObject state = new JSONObject(storage.readState());
            state.put("soundEnabled", true);
            storage.writeState(state.toString());
            if (Build.VERSION.SDK_INT < 35) verifyBlockedDoNotDisturb(context, audio, quiet, test);
            if (Build.VERSION.SDK_INT < 35) verifyInterruptedSoundRecovery(context, audio, quiet, maximum, test);
            context.startService(test);
            check(awaitVolume(audio, maximum), "Enabled sound never reached maximum alarm volume");
            check(soundPlaying(context), "Playing sound has no visible stop action");
            stopSoundFromNotification(context);
            check(awaitVolume(audio, quiet), "Local stop did not restore previous alarm volume");
            check(awaitSoundStopped(context), "Sound notification did not return to connected state");
            context.startService(test);
            check(awaitVolume(audio, maximum), "Second sound did not start after local stop");
            check(awaitVolume(audio, quiet), "Bounded sound did not end and restore volume");
            check(awaitSoundStopped(context), "Bounded sound remained active");
            context.startService(test);
            check(awaitVolume(audio, maximum), "Third sound did not start");
            int manual = Math.max(1, quiet - 1);
            audio.setStreamVolume(AudioManager.STREAM_ALARM, manual, 0);
            stopSoundFromNotification(context);
            check(awaitSoundStopped(context), "Manually adjusted sound did not stop");
            check(audio.getStreamVolume(AudioManager.STREAM_ALARM) == manual, "Local stop overwrote a manual volume change");
        } finally {
            context.startService(new Intent(context, AndroidWorkerGatewayService.class)
                    .setAction("com.gromozeka.mobile.worker.STOP_SOUND"));
            awaitSoundStopped(context);
            audio.setStreamVolume(AudioManager.STREAM_ALARM, original, 0);
        }
    }

    private void verifyBlockedDoNotDisturb(Context context, AudioManager audio, int quiet, Intent test) throws Exception {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        int previous = manager.getCurrentInterruptionFilter();
        try {
            shell("cmd notification allow_dnd " + context.getPackageName());
            manager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_NONE);
            check(awaitInterruptionFilter(manager, NotificationManager.INTERRUPTION_FILTER_NONE), "Could not enable test DND");
            shell("cmd notification disallow_dnd " + context.getPackageName());
            check(!manager.isNotificationPolicyAccessGranted(), "Test unexpectedly retained notification policy access");
            int mutedVolume = audio.getStreamVolume(AudioManager.STREAM_ALARM);
            context.startService(test);
            SystemClock.sleep(500);
            check(!soundPlaying(context), "DND-blocked sound stayed active");
            check(audio.getStreamVolume(AudioManager.STREAM_ALARM) == mutedVolume, "DND-blocked sound changed volume");
            check(manager.getCurrentInterruptionFilter() == NotificationManager.INTERRUPTION_FILTER_NONE, "Worker changed global DND");
        } finally {
            shell("cmd notification allow_dnd " + context.getPackageName());
            try {
                manager.setInterruptionFilter(previous);
                check(awaitInterruptionFilter(manager, previous), "Could not restore test DND");
                check(awaitVolume(audio, quiet), "DND-blocked sound changed the underlying alarm volume");
            } finally {
                shell("cmd notification disallow_dnd " + context.getPackageName());
            }
        }
    }

    private static boolean awaitInterruptionFilter(NotificationManager manager, int expected) {
        for (int attempt = 0; attempt < 70; attempt++) {
            if (manager.getCurrentInterruptionFilter() == expected) return true;
            SystemClock.sleep(100);
        }
        return false;
    }

    private void verifyInterruptedSoundRecovery(Context context, AudioManager audio, int quiet, int maximum, Intent test) throws Exception {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        int previous = manager.getCurrentInterruptionFilter();
        try {
            context.startService(test);
            check(awaitVolume(audio, maximum), "DND interruption test could not start sound");
            SystemClock.sleep(300);
            shell("cmd notification allow_dnd " + context.getPackageName());
            manager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_NONE);
            check(awaitInterruptionFilter(manager, NotificationManager.INTERRUPTION_FILTER_NONE), "Could not interrupt sound with DND");
            shell("cmd notification disallow_dnd " + context.getPackageName());
            check(awaitSoundStopped(context), "Enabling DND did not stop the sound");
            AndroidWorkerEncryptedFile file = new AndroidWorkerEncryptedFile(new File(context.getNoBackupFilesDir(), "worker-sound-volume.enc").toPath());
            check(new JSONObject(file.read()).getInt("previous") == quiet, "DND hid the volume and lost pending restoration");
        } finally {
            shell("cmd notification allow_dnd " + context.getPackageName());
            try {
                manager.setInterruptionFilter(previous);
                check(awaitInterruptionFilter(manager, previous), "Could not restore DND after interrupting sound");
            } finally {
                shell("cmd notification disallow_dnd " + context.getPackageName());
            }
        }
        Activity activity = startActivitySync(new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try { check(awaitVolume(audio, quiet), "Opening the app did not restore volume after DND"); }
        finally { runOnMainSync(activity::finish); waitForIdleSync(); }
    }

    private void shell(String command) throws Exception {
        try (InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(getUiAutomation().executeShellCommand(command))) {
            byte[] buffer = new byte[1024];
            while (input.read(buffer) != -1) { }
        }
    }

    private static boolean awaitVolume(AudioManager audio, int expected) {
        for (int attempt = 0; attempt < 70; attempt++) {
            if (audio.getStreamVolume(AudioManager.STREAM_ALARM) == expected) return true;
            SystemClock.sleep(100);
        }
        return false;
    }

    private static void stopSoundFromNotification(Context context) throws Exception {
        for (StatusBarNotification notification : context.getSystemService(NotificationManager.class).getActiveNotifications()) {
            if (notification.getId() != 27_045 || notification.getNotification().actions == null) continue;
            for (android.app.Notification.Action action : notification.getNotification().actions) {
                if ("Stop sound".contentEquals(action.title)) {
                    action.actionIntent.send();
                    return;
                }
            }
        }
        throw new AssertionError("Sound stop action is missing");
    }

    private static boolean soundPlaying(Context context) {
        for (StatusBarNotification notification : context.getSystemService(NotificationManager.class).getActiveNotifications()) {
            if (notification.getId() != 27_045 || notification.getNotification().actions == null) continue;
            for (android.app.Notification.Action action : notification.getNotification().actions) {
                if ("Stop sound".contentEquals(action.title)) return true;
            }
        }
        return false;
    }

    private static boolean awaitSoundStopped(Context context) {
        for (int attempt = 0; attempt < 70; attempt++) {
            if (!soundPlaying(context)) return true;
            SystemClock.sleep(100);
        }
        return false;
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
