package com.gromozeka.worker

import org.springframework.context.annotation.Primary
import org.springframework.stereotype.Service

@Service
@Primary
class WorkerGatewayOutbound(
    properties: ConversationRuntimeWorkerProperties,
) : com.gromozeka.worker.runtime.WorkerGatewayOutbound(properties.capabilities)
