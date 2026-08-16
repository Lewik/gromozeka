package com.gromozeka.client

import com.gromozeka.remote.protocol.DeclarativeStateRevisionPayload
import com.gromozeka.remote.protocol.DeclarativeStateRevisionQuery
import com.gromozeka.remote.protocol.RemoteDeclarativeStateResource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

internal fun <T> GromozekaWsClient.observeDeclarativeState(
    resource: RemoteDeclarativeStateResource,
    scopeId: String? = null,
    load: suspend () -> T,
): Flow<T> = observeState(DeclarativeStateRevisionQuery(resource, scopeId)).map { snapshot ->
    check(snapshot.value === DeclarativeStateRevisionPayload) {
        "Unexpected declarative state payload: ${snapshot.value::class.simpleName}"
    }
    load()
}
