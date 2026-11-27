-- Review continuation limits apply only to the active Runtime turn. Existing rows belong
-- to completed turns, so no canonical message backfill or JSON inspection is required.
ALTER TABLE messages
    ADD COLUMN response_review_continuation BOOLEAN NOT NULL DEFAULT FALSE;
