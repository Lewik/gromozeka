package com.gromozeka.infrastructure.runtime

import com.gromozeka.domain.model.mcp.McpServerId
import com.gromozeka.domain.service.ConversationRuntimeWorkerId
import com.gromozeka.domain.service.ConversationRuntimeWorkerIdentity
import com.gromozeka.domain.service.ConversationRuntimeWorkerSessionId
import com.gromozeka.domain.service.WorkerControlHandler
import com.gromozeka.domain.service.WorkerControlRequest
import com.gromozeka.domain.service.WorkerControlResult
import com.gromozeka.shared.uuid.uuid7
import kotlinx.coroutines.runBlocking
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory
import org.springframework.amqp.rabbit.core.RabbitAdmin
import org.springframework.amqp.rabbit.core.RabbitTemplate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class RabbitWorkerControlTest {
    @Test
    fun `routing key identifies an exact worker session without exposing raw ids`() {
        val topology = RabbitWorkerControlTopology(
            connectionFactory = CachingConnectionFactory(),
            exchangeName = "test.control",
            queuePrefix = "test.control",
        )
        val first = identity("worker/a", "session/1")
        val second = identity("worker/a", "session/2")

        assertNotEquals(topology.routingKey(first), topology.routingKey(second))
        assertTrue(first.workerId.value !in topology.routingKey(first))
        assertTrue(first.sessionId.value !in topology.routingKey(first))
        assertTrue(topology.routingKey(first).length < 255)
    }

    @Test
    fun `rabbit control request reaches only the addressed worker session and returns correlated result`() = runBlocking {
        if (System.getenv("GROMOZEKA_RABBIT_RUNTIME_TEST") != "true") {
            return@runBlocking
        }

        val connectionFactory = CachingConnectionFactory("localhost", 5672)
        val exchangeName = "gromozeka.worker.control.test.${uuid7()}"
        val queuePrefix = "gromozeka.worker.control.test.${uuid7()}"
        val topology = RabbitWorkerControlTopology(
            connectionFactory = connectionFactory,
            exchangeName = exchangeName,
            queuePrefix = queuePrefix,
        )
        val identity = identity("test-worker", "test-session")
        var received: WorkerControlRequest? = null
        val handler = WorkerControlHandler { request ->
            received = request
            WorkerControlResult(
                requestId = request.id,
                status = WorkerControlResult.Status.DELETED,
            )
        }
        val consumer = RabbitWorkerControlConsumer(
            connectionFactory = connectionFactory,
            rabbitTemplate = RabbitTemplate(connectionFactory),
            topology = topology,
            workerIdentity = identity,
            handler = handler,
        )
        val client = RabbitWorkerControlClient(
            connectionFactory = connectionFactory,
            topology = topology,
            timeoutMillis = 5_000,
        )
        val request = WorkerControlRequest(
            id = WorkerControlRequest.Id(uuid7()),
            target = identity,
            command = WorkerControlRequest.Command.DeleteMcpServer(
                serverId = McpServerId("test_server"),
                expectedRevision = 1,
            ),
        )

        try {
            consumer.start()

            val result = client.execute(request)

            assertEquals(request, received)
            assertEquals(request.id, result.requestId)
            assertEquals(WorkerControlResult.Status.DELETED, result.status)
        } finally {
            consumer.stop()
            RabbitAdmin(connectionFactory).deleteExchange(exchangeName)
            connectionFactory.destroy()
        }
    }

    private fun identity(
        workerId: String,
        sessionId: String,
    ): ConversationRuntimeWorkerIdentity =
        ConversationRuntimeWorkerIdentity(
            workerId = ConversationRuntimeWorkerId(workerId),
            sessionId = ConversationRuntimeWorkerSessionId(sessionId),
        )
}
