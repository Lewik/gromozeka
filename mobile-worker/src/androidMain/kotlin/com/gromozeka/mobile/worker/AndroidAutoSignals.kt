package com.gromozeka.mobile.worker

import android.content.Context
import androidx.car.app.connection.CarConnection
import androidx.lifecycle.Observer
import com.gromozeka.domain.model.VehicleSystem
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal object AndroidAutoSignals {
    suspend fun capture(context: Context, runtime: MobileWorkerRuntime): Boolean {
        val connectionType = readConnectionType(context) ?: return false
        val pendingBefore = runtime.status().pendingEventCount
        runtime.recordVehicleConnection(
            system = VehicleSystem.ANDROID_AUTO,
            connected = connectionType == CarConnection.CONNECTION_TYPE_PROJECTION,
        )
        return runtime.status().pendingEventCount > pendingBefore
    }

    private suspend fun readConnectionType(context: Context): Int? {
        val result = CompletableDeferred<Int>()
        lateinit var observer: Observer<Int>
        val connectionType = withContext(Dispatchers.Main.immediate) {
            CarConnection(context.applicationContext).type.also { liveData ->
                observer = Observer { value -> value?.let(result::complete) }
                liveData.observeForever(observer)
            }
        }
        return try {
            withTimeoutOrNull(CONNECTION_QUERY_TIMEOUT_MILLIS) { result.await() }
        } finally {
            withContext(NonCancellable + Dispatchers.Main.immediate) {
                connectionType.removeObserver(observer)
            }
        }
    }

    private const val CONNECTION_QUERY_TIMEOUT_MILLIS = 5_000L
}
