ALTER TABLE context_state_events DROP CONSTRAINT chk_context_state_source_kind;
UPDATE context_state_events SET source_kind = 'WORKER', source_json = jsonb_set(source_json, '{type}', '"worker"')
WHERE source_kind = 'MOBILE_WORKER';
ALTER TABLE context_state_events ADD CONSTRAINT chk_context_state_source_kind
    CHECK (source_kind IN ('WORKER', 'CLIENT', 'USER', 'SERVER'));

ALTER TABLE mobile_worker_contact_observations RENAME TO worker_contact_observations;
ALTER TABLE mobile_worker_presence RENAME TO worker_presence;
