-- Add family_id to refresh_token for token rotation with reuse detection.
-- Tokens in the same family share a family_id; reuse of a revoked token
-- triggers revocation of the entire family.

ALTER TABLE refresh_token ADD COLUMN family_id UUID;

UPDATE refresh_token SET family_id = gen_random_uuid() WHERE family_id IS NULL;

ALTER TABLE refresh_token ALTER COLUMN family_id SET NOT NULL;

CREATE INDEX idx_refresh_token_family_id ON refresh_token(family_id);
