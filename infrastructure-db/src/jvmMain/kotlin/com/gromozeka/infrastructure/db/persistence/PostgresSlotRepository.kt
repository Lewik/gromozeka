package com.gromozeka.infrastructure.db.persistence

import com.gromozeka.domain.model.*
import com.gromozeka.domain.slot.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.springframework.context.annotation.DependsOn
import org.springframework.stereotype.Service
import java.sql.Connection
import javax.sql.DataSource

@Service
@DependsOn("postgresFlyway")
class PostgresSlotRepository(private val dataSource: DataSource) : SlotRepository {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }
    private suspend fun <T> transaction(action: (Connection) -> T): T = withContext(Dispatchers.IO) {
        dataSource.connection.use { c ->
            c.autoCommit = false
            try { action(c).also { c.commit() } }
            catch (e: Throwable) { c.rollback(); throw e }
        }
    }
    private fun Connection.rows(sql: String, vararg args: Any?): List<String> = prepareStatement(sql).use { s ->
        args.forEachIndexed { i, v -> s.setObject(i + 1, v) }
        s.executeQuery().use { r -> buildList { while (r.next()) add(r.getString(1)) } }
    }
    private fun Connection.exec(sql: String, vararg args: Any?): Int = prepareStatement(sql).use { s ->
        args.forEachIndexed { i, v -> s.setObject(i + 1, v) }; s.executeUpdate()
    }
    override suspend fun create(slot: Slot): Slot = transaction { c ->
        val number = c.rows("SELECT nextval(pg_get_serial_sequence('development_slots', 'number'))").single().toLong()
        val saved = slot.copy(number = number)
        c.exec("INSERT INTO development_slots(number,id,user_id,workspace_id,retired,record_json) VALUES (?,?,?,?,?,?::jsonb)",
            number, saved.id, saved.userId.value, saved.workspaceId.value, saved.retired, json.encodeToString(saved))
        saved
    }
    override suspend fun list(userId: User.Id): List<Slot> = transaction { c ->
        c.rows("SELECT record_json::text FROM development_slots WHERE user_id=? AND NOT retired ORDER BY number", userId.value)
            .map { json.decodeFromString<Slot>(it) }
    }
    override suspend fun find(number: Long): Slot? = transaction { it.slot(number) }
    private fun Connection.slot(number: Long): Slot? = rows("SELECT record_json::text FROM development_slots WHERE number=?", number)
        .singleOrNull()?.let { json.decodeFromString<Slot>(it) }
    override suspend fun findByWorkspace(workspaceId: Workspace.Id): Slot? = transaction { c ->
        c.rows("SELECT record_json::text FROM development_slots WHERE workspace_id=? AND NOT retired", workspaceId.value)
            .singleOrNull()?.let { json.decodeFromString<Slot>(it) }
    }
    override suspend fun findLease(id: String): SlotLease? = transaction { c ->
        c.rows("SELECT record_json::text FROM slot_leases WHERE id=?", id).singleOrNull()?.let { json.decodeFromString<SlotLease>(it) }
    }
    override suspend fun findRequest(id: String): SlotRequest? = transaction { c ->
        c.rows("SELECT record_json::text FROM slot_requests WHERE id=?", id).singleOrNull()?.let { json.decodeFromString<SlotRequest>(it) }
    }
    override suspend fun snapshot(number: Long): SlotSnapshot? = transaction { c ->
        // The same row lock used by writes gives a consistent slot/lease/request view.
        c.rows("SELECT number FROM development_slots WHERE number=? FOR SHARE", number).singleOrNull() ?: return@transaction null
        val tx = Session(c, requireNotNull(c.slot(number)))
        SlotSnapshot(tx.slot, tx.activeLeases(), tx.pendingRequests())
    }
    override suspend fun pendingSlots(afterNumber: Long, limit: Int): List<Long> = transaction { c ->
        c.rows("SELECT DISTINCT slot_number FROM slot_requests WHERE state='PENDING' AND slot_number>? ORDER BY slot_number LIMIT ?", afterNumber, limit)
            .map(String::toLong)
    }
    override suspend fun pendingEvents(afterSequence: Long, limit: Int): List<SlotEvent> = transaction { c ->
        c.prepareStatement("SELECT sequence,record_json::text FROM slot_events WHERE NOT finished AND sequence>? ORDER BY sequence LIMIT ?").use { s ->
            s.setLong(1, afterSequence); s.setInt(2, limit)
            s.executeQuery().use { r -> buildList { while (r.next()) add(json.decodeFromString<SlotEvent>(r.getString(2)).copy(sequence = r.getLong(1))) } }
        }
    }
    override suspend fun settleEvent(id: String, error: String?) { transaction { c ->
        c.exec("UPDATE slot_events SET finished=TRUE,error=? WHERE id=? AND NOT finished", error, id)
    } }
    override suspend fun <T> locked(number: Long, action: (SlotTransaction) -> T): T = transaction { c ->
        require(c.rows("SELECT number FROM development_slots WHERE number=? FOR UPDATE", number).isNotEmpty()) { "Slot not found" }
        action(Session(c, requireNotNull(c.slot(number))))
    }
    private inner class Session(private val c: Connection, override var slot: Slot) : SlotTransaction {
        override fun activeLeases(): List<SlotLease> = c.rows("SELECT record_json::text FROM slot_leases WHERE slot_number=? AND released_at IS NULL ORDER BY id", slot.number)
            .map { json.decodeFromString<SlotLease>(it) }
        override fun pendingRequests(): List<SlotRequest> = c.rows("SELECT record_json::text FROM slot_requests WHERE slot_number=? AND state='PENDING' ORDER BY requested_at,id", slot.number)
            .map { json.decodeFromString<SlotRequest>(it) }
        override fun lease(id: String): SlotLease? = c.rows("SELECT record_json::text FROM slot_leases WHERE slot_number=? AND id=?", slot.number, id)
            .singleOrNull()?.let { json.decodeFromString<SlotLease>(it) }
        override fun request(id: String): SlotRequest? = c.rows("SELECT record_json::text FROM slot_requests WHERE slot_number=? AND id=?", slot.number, id)
            .singleOrNull()?.let { json.decodeFromString<SlotRequest>(it) }
        override fun requestByKey(key: String): SlotRequest? = c.rows("SELECT record_json::text FROM slot_requests WHERE idempotency_key=?", key)
            .singleOrNull()?.let { json.decodeFromString<SlotRequest>(it) }
        override fun save(slot: Slot) {
            require(slot.id == this.slot.id && slot.number == this.slot.number && slot.userId == this.slot.userId && slot.workspaceId == this.slot.workspaceId && slot.mountId == this.slot.mountId)
            c.exec("UPDATE development_slots SET retired=?,record_json=?::jsonb WHERE number=?", slot.retired, json.encodeToString(slot), slot.number)
            this.slot = slot
        }
        override fun save(lease: SlotLease) {
            require(lease.slotNumber == slot.number)
            c.exec("INSERT INTO slot_leases(id,slot_number,conversation_id,access,released_at,record_json) VALUES (?,?,?,?,?::timestamptz,?::jsonb) ON CONFLICT(id) DO UPDATE SET released_at=EXCLUDED.released_at,record_json=EXCLUDED.record_json",
                lease.id, lease.slotNumber, lease.conversationId.value, lease.access.name, lease.releasedAt?.toString(), json.encodeToString(lease))
        }
        override fun save(request: SlotRequest) {
            require(request.slotNumber == slot.number)
            c.exec("INSERT INTO slot_requests(id,slot_number,conversation_id,idempotency_key,requested_at,state,record_json) VALUES (?,?,?,?,?::timestamptz,?,?::jsonb) ON CONFLICT(id) DO UPDATE SET state=EXCLUDED.state,record_json=EXCLUDED.record_json",
                request.id, request.slotNumber, request.conversationId.value, request.idempotencyKey, request.requestedAt.toString(), request.state.name, json.encodeToString(request))
        }
        override fun emit(event: SlotEvent) {
            require(event.slotNumber == slot.number)
            c.exec("INSERT INTO slot_events(id,record_json) VALUES (?,?::jsonb) ON CONFLICT(id) DO NOTHING", event.id, json.encodeToString(event))
        }
    }
}
