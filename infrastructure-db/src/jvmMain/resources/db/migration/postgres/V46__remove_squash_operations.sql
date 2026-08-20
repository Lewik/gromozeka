DROP TABLE IF EXISTS squash_operations;

ALTER TABLE messages
    DROP COLUMN IF EXISTS squash_operation_id;
