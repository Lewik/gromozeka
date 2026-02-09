-- Add stride_enabled flag to conversations table
ALTER TABLE conversations ADD COLUMN stride_enabled INTEGER NOT NULL DEFAULT 0;
