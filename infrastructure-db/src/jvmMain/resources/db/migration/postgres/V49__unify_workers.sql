ALTER TABLE workers
    DROP CONSTRAINT chk_workers_kind,
    DROP CONSTRAINT chk_workers_subject,
    DROP COLUMN kind,
    ADD COLUMN platform VARCHAR(64) NULL;

ALTER TABLE device_connections
    ADD COLUMN worker_bind_to_user BOOLEAN NOT NULL DEFAULT FALSE;

UPDATE device_connections SET worker_bind_to_user = TRUE WHERE worker_kind = 'MOBILE_DEVICE';

ALTER TABLE device_connections
    DROP CONSTRAINT chk_device_connections_worker,
    DROP COLUMN worker_kind,
    ADD CONSTRAINT chk_device_connections_worker_binding
        CHECK (NOT worker_bind_to_user OR worker_id IS NOT NULL);
