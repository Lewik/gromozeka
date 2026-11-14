package com.gromozeka.domain.service

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Presentation provenance for this call, not authorization or provider-session identity. */
class AiAdmissionOriginContext(val origin: AiAdmissionOrigin?) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<AiAdmissionOriginContext>
}
