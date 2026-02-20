CREATE TABLE dataset_favorite (
  id BIGSERIAL PRIMARY KEY,
  user_id BIGINT NOT NULL REFERENCES "user"(id),
  dataset_id BIGINT NOT NULL REFERENCES dataset(id) ON DELETE CASCADE,
  created_at TIMESTAMP NOT NULL DEFAULT NOW(),
  UNIQUE(user_id, dataset_id)
);
CREATE INDEX idx_dataset_favorite_user ON dataset_favorite(user_id);
