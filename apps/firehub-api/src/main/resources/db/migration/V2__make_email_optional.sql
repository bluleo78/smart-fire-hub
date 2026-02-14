ALTER TABLE "user" ALTER COLUMN email DROP NOT NULL;
ALTER TABLE "user" DROP CONSTRAINT user_email_key;
CREATE UNIQUE INDEX idx_user_email_unique ON "user"(email) WHERE email IS NOT NULL;
DROP INDEX IF EXISTS idx_user_email;
