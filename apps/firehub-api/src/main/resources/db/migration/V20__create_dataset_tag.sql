CREATE TABLE dataset_tag (
  id BIGSERIAL PRIMARY KEY,
  dataset_id BIGINT NOT NULL REFERENCES dataset(id) ON DELETE CASCADE,
  tag_name VARCHAR(50) NOT NULL,
  created_by BIGINT REFERENCES "user"(id),
  created_at TIMESTAMP NOT NULL DEFAULT NOW(),
  UNIQUE(dataset_id, tag_name)
);
CREATE INDEX idx_dataset_tag_name ON dataset_tag(tag_name);
CREATE INDEX idx_dataset_tag_dataset ON dataset_tag(dataset_id);
