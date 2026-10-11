-- An automatic request keeps its original identity and no exact target while queued.
-- Concrete grants still use the existing constrained slot_leases table and transactional outbox.
ALTER TABLE slot_requests ALTER COLUMN slot_number DROP NOT NULL;
ALTER TABLE slot_requests ADD COLUMN actor_user_id VARCHAR(255);
UPDATE slot_requests SET actor_user_id = record_json ->> 'actorUserId';
ALTER TABLE slot_requests ALTER COLUMN actor_user_id SET NOT NULL;
CREATE INDEX slot_requests_personal_queue ON slot_requests(actor_user_id, requested_at, id)
    WHERE state = 'PENDING';
-- Retain slot_requests_pending_conversation: exact-slot duplicate requests are still rejected.
-- NULL automatic targets are not fake slot numbers and deliberately do not collide in that index.
