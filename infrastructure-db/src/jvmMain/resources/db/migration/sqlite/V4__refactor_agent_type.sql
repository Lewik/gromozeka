-- Refactor Agent model: remove usageCount, replace isBuiltin with type enum
-- All existing agents will be set to BUILTIN type

-- Add new type column with default value
ALTER TABLE agents ADD COLUMN type VARCHAR(50) DEFAULT 'BUILTIN' NOT NULL;

-- Update all existing rows to BUILTIN (redundant due to DEFAULT, but explicit)
UPDATE agents SET type = 'BUILTIN';

-- Drop old columns
ALTER TABLE agents DROP COLUMN is_builtin;
ALTER TABLE agents DROP COLUMN usage_count;
