-- 로그인 실패 카운터 영속 테이블. 브루트포스 잠금이 재시작·멀티 인스턴스에서 일관되게 동작하도록 한다(#144).
-- expires_at 만료 시 lazy expiry로 무시되며, LoginAttemptCleanupScheduler가 1시간 주기로 정리한다.
CREATE TABLE login_attempts (
  username    VARCHAR(255) PRIMARY KEY,
  attempts    INT NOT NULL CHECK (attempts > 0),
  expires_at  TIMESTAMP NOT NULL,
  updated_at  TIMESTAMP NOT NULL DEFAULT now()
);

CREATE INDEX idx_login_attempts_expires_at ON login_attempts(expires_at);

COMMENT ON TABLE login_attempts IS '로그인 실패 카운터. 브루트포스 잠금용 영속 저장소(#144).';
