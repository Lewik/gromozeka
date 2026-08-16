package com.gromozeka.mobile.worker

import android.Manifest
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import com.gromozeka.domain.model.MobileWorkerAppState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class MobileWorkerBleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val errorCode = intent.getIntExtra(BluetoothLeScanner.EXTRA_ERROR_CODE, 0)
        if (errorCode != 0) {
            androidMobileWorkerLog.warn("BLE scan callback failed with code $errorCode")
            return
        }
        val callbackType = intent.getIntExtra(
            BluetoothLeScanner.EXTRA_CALLBACK_TYPE,
            ScanSettings.CALLBACK_TYPE_ALL_MATCHES,
        )
        val present = callbackType != ScanSettings.CALLBACK_TYPE_MATCH_LOST
        val results = intent.scanResults()
        if (results.isEmpty()) return
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            val runtime = AndroidMobileWorkerRuntimeFactory.create(context.applicationContext)
            try {
                val targets = AndroidMobileWorkerConfigurationStore(context).read().bleDevices
                results.forEach { result ->
                    targets.filter { it.matches(result, context) }.forEach { target ->
                        runtime.recordBlePresence(target.id, target.displayName, present)
                    }
                }
                runtime.synchronize(MobileWorkerAppState.BACKGROUND)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                androidMobileWorkerLog.warn(error, "Failed to store BLE presence event")
            } finally {
                runtime.close()
                pendingResult.finish()
            }
        }
    }

    private fun Intent.scanResults(): List<ScanResult> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableArrayListExtra(BluetoothLeScanner.EXTRA_LIST_SCAN_RESULT, ScanResult::class.java).orEmpty()
        } else {
            @Suppress("DEPRECATION")
            getParcelableArrayListExtra<ScanResult>(BluetoothLeScanner.EXTRA_LIST_SCAN_RESULT).orEmpty()
        }

    private fun ConfiguredBleDevice.matches(result: ScanResult, context: Context): Boolean {
        val addressMatches = address?.let { expected ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
            ) {
                false
            } else {
                result.device.address.equals(expected, ignoreCase = true)
            }
        } ?: false
        val serviceMatches = serviceUuid?.let { expected ->
            result.scanRecord?.serviceUuids?.any { it.uuid.toString().equals(expected, ignoreCase = true) } == true
        } ?: false
        return addressMatches || serviceMatches
    }
}
