ALTER TABLE dataset ADD COLUMN status VARCHAR(20) DEFAULT 'NONE'
  CHECK (status IN ('NONE', 'CERTIFIED', 'DEPRECATED'));
ALTER TABLE dataset ADD COLUMN status_note TEXT;
ALTER TABLE dataset ADD COLUMN status_updated_by BIGINT REFERENCES "user"(id);
ALTER TABLE dataset ADD COLUMN status_updated_at TIMESTAMP;
