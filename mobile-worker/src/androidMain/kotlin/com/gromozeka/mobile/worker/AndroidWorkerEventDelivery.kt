package com.gromozeka.mobile.worker

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import com.gromozeka.domain.model.WorkerAppState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** One foreground delivery loop shared by independent location and telemetry collectors. */
internal object AndroidWorkerEventDelivery {
    private val owners = mutableSetOf<Any>()
    private var session: Session? = null
    private val mutableState = MutableStateFlow("")
    val state = mutableState.asStateFlow()

    @Synchronized
    fun acquire(context: Context, owner: Any) {
        owners += owner
        if (session != null) { wake(); return }
        val current = Session()
        session = current
        val application = context.applicationContext
        current.scope.launch {
            val runtime = AndroidMobileWorkerRuntimeFactory.create(application)
            val manager = application.getSystemService(ConnectivityManager::class.java)
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) { current.signal.trySend(Unit) }
            }
            try {
                runCatching { manager?.registerDefaultNetworkCallback(callback) }
                current.signal.trySend(Unit)
                while (isActive) {
                    withTimeoutOrNull(30_000) { current.signal.receive() }
                    try {
                        do {
                            val status = runtime.synchronize(WorkerAppState.BACKGROUND)
                            mutableState.value = "Last delivery: ${status.lastSynchronizedAt} · pending: ${status.pendingEventCount}"
                        } while (status.pendingEventCount > 0 && isActive)
                    } catch (error: CancellationException) { throw error }
                    catch (_: Exception) {
                        mutableState.value = "Waiting for delivery; recorded events stay on this device"
                    }
                }
            } finally {
                runCatching { manager?.unregisterNetworkCallback(callback) }
                runtime.close()
            }
        }
    }

    @Synchronized
    fun wake(): Boolean = session?.let { it.signal.trySend(Unit); true } ?: false

    @Synchronized
    fun release(owner: Any) {
        owners -= owner
        if (owners.isEmpty()) {
            session?.scope?.cancel()
            session = null
        }
    }

    private class Session {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val signal = Channel<Unit>(Channel.CONFLATED)
    }
}
