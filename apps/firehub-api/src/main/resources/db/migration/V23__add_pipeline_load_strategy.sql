-- Add load_strategy to pipeline_step for configurable ETL load behavior
ALTER TABLE pipeline_step ADD COLUMN load_strategy VARCHAR(20) NOT NULL DEFAULT 'REPLACE';
