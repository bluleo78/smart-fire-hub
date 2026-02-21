-- Add is_primary_key flag to dataset_column for user-defined primary/composite key support
ALTER TABLE dataset_column ADD COLUMN is_primary_key BOOLEAN NOT NULL DEFAULT FALSE;
