package com.gromozeka.mobile.worker;

import android.Manifest;
import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.os.Build;
import android.os.LocaleList;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;
import org.json.JSONObject;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Locale;

/** Disposable-emulator test. Exercises actual Compose buttons, not just pre-seeded enabled settings. */
final class WorkerUiSmoke {
    private final Instrumentation test;
    private final Context context;
    private final AndroidMobileWorkerStorage storage;
    private final WorkerStrings strings;

    WorkerUiSmoke(Instrumentation test, Context context, AndroidMobileWorkerStorage storage) {
        this.test = test;
        this.context = context;
        this.storage = storage;
        this.strings = WorkerUiKt.workerStrings(context);
    }

    void run() throws Exception {
        check(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk"), "Run only on a disposable emulator");
        android.accessibilityservice.AccessibilityServiceInfo info = test.getUiAutomation().getServiceInfo();
        info.flags |= android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                | android.accessibilityservice.AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS;
        test.getUiAutomation().setServiceInfo(info);
        verifyLocales();
        verifySettingsFallback();
        if (Build.VERSION.SDK_INT <= 30) {
            String[] declared = context.getPackageManager().getPackageInfo(context.getPackageName(), PackageManager.GET_PERMISSIONS).requestedPermissions;
            check(Arrays.asList(declared).contains(Manifest.permission.BLUETOOTH), "Missing legacy BLUETOOTH declaration");
            check(Arrays.asList(declared).contains(Manifest.permission.BLUETOOTH_ADMIN), "Missing legacy BLUETOOTH_ADMIN declaration");
            check(context.checkSelfPermission(Manifest.permission.BLUETOOTH) == PackageManager.PERMISSION_GRANTED, "Legacy Bluetooth not granted at install");
        }
        AndroidMobileWorkerSensors sensors = new AndroidMobileWorkerSensors(context);
        sensors.bluetoothEnabled(); // Must not throw with denied nearby-device access or legacy Android.
        check(sensors.enableBlePresenceUpdates(), "Unconfigured BLE must not require permissions");
        sensors.disableBackgroundSignals();
        storage.writeCredential("ui-smoke-no-real-server");
        storage.writeState("{\"serverUrl\":\"https://127.0.0.1:1\",\"workerId\":\"ui-smoke\","
                + "\"telemetryConfiguration\":{\"enabled\":false,\"applicationUsageEnabled\":true,\"intervalSeconds\":10},"
                + "\"outbox\":{\"streamId\":\"ui-smoke-stream\",\"pending\":[],\"latest\":{}}}");
        if (Build.VERSION.SDK_INT >= 33) shell("pm grant " + context.getPackageName() + " android.permission.POST_NOTIFICATIONS");
        shell("appops set " + context.getPackageName() + " GET_USAGE_STATS deny");
        shell("input keyevent KEYCODE_WAKEUP");
        shell("wm dismiss-keyguard");
        Activity activity = open();
        click(strings.invoke("enable"));
        check(awaitText(strings.invoke("usageRequired")), "Missing visible usage-access error dialog");
        click(strings.invoke("close"));
        click(strings.invoke("usageAccess"));
        SystemClock.sleep(500);
        AccessibilityNodeInfo root = root();
        check(root != null && !context.getPackageName().contentEquals(root.getPackageName()), "Usage-access button did not leave the app");
        test.getUiAutomation().performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK);
        shell("appops set " + context.getPackageName() + " GET_USAGE_STATS allow");
        SystemClock.sleep(2500);
        click(strings.invoke("enable"));
        check(awaitEnabled(true), "Enable button did not persist telemetry opt-in");
        check(awaitText(strings.invoke("disable")), "Enabled collection has no stop button");
        click(strings.invoke("disable"));
        check(awaitEnabled(false), "Disable button did not persist telemetry opt-out");
        test.runOnMainSync(activity::finish);
        test.waitForIdleSync();
    }

    private void verifyLocales() {
        for (String tag : new String[]{"en-US", "ru-RU", "he-IL", "es", "pt-BR", "ja", "zh-TW", "zh-CN", "de", "fr", "ko", "ar", "id"}) {
            Configuration configuration = new Configuration(context.getResources().getConfiguration());
            configuration.setLocales(new LocaleList(Locale.forLanguageTag(tag)));
            WorkerStrings t = WorkerUiKt.workerStrings(context.createConfigurationContext(configuration));
            for (WorkerPhase phase : WorkerPhase.values()) check(!t.invoke(phase.getKey()).isEmpty(), "Missing phase translation " + tag);
            if (tag.equals("ru-RU")) check(t.invoke("location").equals("Геолокация"), "Android locale was not used");
            if (tag.equals("he-IL")) check(t.getRtl(), "Hebrew direction is not RTL");
        }
        check(new WorkerStrings("xx-ZZ").invoke("settings").equals("Settings"), "Unsupported locale fallback failed");
    }

    private void verifySettingsFallback() {
        java.util.ArrayList<Intent> calls = new java.util.ArrayList<>();
        Context fake = new ContextWrapper(context) {
            @Override public void startActivity(Intent intent) {
                calls.add(intent);
                if (calls.size() == 1) throw new android.content.ActivityNotFoundException("OEM lacks package page");
            }
        };
        WorkerUiKt.openWorkerSettings(fake, android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS, true, intent -> kotlin.Unit.INSTANCE);
        check(calls.size() == 2 && calls.get(1).getData() == null, "No generic usage-access fallback");
    }

    private Activity open() {
        Activity activity = test.startActivitySync(new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        test.waitForIdleSync();
        check(awaitText(strings.invoke("telemetry")), "Localized home screen did not appear; expected=" + strings.invoke("telemetry") + "; tree=" + labels(root()));
        return activity;
    }

    private boolean awaitEnabled(boolean enabled) throws Exception {
        for (int i = 0; i < 100; i++) {
            if (new JSONObject(storage.readState()).getJSONObject("telemetryConfiguration").getBoolean("enabled") == enabled) return true;
            SystemClock.sleep(100);
        }
        return false;
    }

    private boolean awaitText(String text) {
        for (int i = 0; i < 200; i++) {
            if (find(root(), text) != null) return true;
            SystemClock.sleep(150);
        }
        return false;
    }

    private void click(String text) {
        for (int i = 0; i < 12; i++) {
            AccessibilityNodeInfo root = root();
            AccessibilityNodeInfo node = find(root, text);
            if (node != null) {
                node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.getId());
                SystemClock.sleep(200);
                node = find(root(), text);
                while (node != null) {
                    if (node.isClickable() && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                        SystemClock.sleep(350);
                        return;
                    }
                    node = node.getParent();
                }
            }
            AccessibilityNodeInfo scroll = scrollable(root);
            if (scroll != null) scroll.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
            SystemClock.sleep(250);
        }
        throw new AssertionError("Could not click: " + text);
    }

    private AccessibilityNodeInfo root() {
        AccessibilityNodeInfo root = test.getUiAutomation().getRootInActiveWindow();
        if (root != null) return root;
        // Some Android versions briefly have no active accessibility window after launch.
        for (android.view.accessibility.AccessibilityWindowInfo window : test.getUiAutomation().getWindows()) {
            if (window.getType() == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION) {
                AccessibilityNodeInfo candidate = window.getRoot();
                if (candidate != null) return candidate;
            }
        }
        return null;
    }

    private static String labels(AccessibilityNodeInfo node) {
        if (node == null) return "<null>";
        StringBuilder result = new StringBuilder();
        if (node.getText() != null) result.append(node.getText()).append(" | ");
        for (int i = 0; i < node.getChildCount(); i++) result.append(labels(node.getChild(i)));
        return result.toString();
    }

    private static AccessibilityNodeInfo find(AccessibilityNodeInfo node, String text) {
        if (node == null) return null;
        if (text.contentEquals(node.getText() == null ? "" : node.getText())) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo found = find(node.getChild(i), text);
            if (found != null) return found;
        }
        return null;
    }

    private static AccessibilityNodeInfo scrollable(AccessibilityNodeInfo node) {
        if (node == null) return null;
        if (node.isScrollable()) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo found = scrollable(node.getChild(i));
            if (found != null) return found;
        }
        return null;
    }

    private void shell(String command) throws Exception {
        try (ParcelFileDescriptor descriptor = test.getUiAutomation().executeShellCommand(command);
             InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(descriptor)) {
            while (input.read() != -1) { /* Drain command output. */ }
        }
    }

    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
