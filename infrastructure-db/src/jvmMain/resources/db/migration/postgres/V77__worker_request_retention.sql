ALTER TABLE worker_requests ADD COLUMN execution_deadline TIMESTAMPTZ;
ALTER TABLE worker_requests ADD COLUMN completion_projected_at TIMESTAMPTZ;

-- Existing completed requests were delivered by the previous completion path.
-- Do not replay historical results as new user notifications on upgrade.
UPDATE worker_requests SET completion_projected_at = completed_at WHERE completed_at IS NOT NULL;

CREATE INDEX worker_requests_execution_deadline_pending_idx
    ON worker_requests (execution_deadline, id) WHERE completed_at IS NULL;
CREATE INDEX worker_requests_delivery_deadline_pending_idx
    ON worker_requests (start_deadline, id) WHERE completed_at IS NULL AND dispatched_at IS NULL;
CREATE INDEX worker_requests_projection_pending_idx
    ON worker_requests (completed_at, id) WHERE completed_at IS NOT NULL AND completion_projected_at IS NULL;
CREATE INDEX worker_requests_retention_idx
    ON worker_requests (completed_at, id) WHERE completion_projected_at IS NOT NULL;
