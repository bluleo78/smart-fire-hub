ALTER TABLE dataset ADD COLUMN updated_by BIGINT REFERENCES "user"(id);
ALTER TABLE pipeline ADD COLUMN updated_by BIGINT REFERENCES "user"(id);
