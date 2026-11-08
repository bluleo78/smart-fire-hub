-- V156: 로그인 IP 기준 제한(비밀번호 스프레이 차단)용 실패 기록 테이블 (WD-578).
--
-- 왜 필요한가: 계정 잠금(login_attempts, 5회/15분)은 한 계정에 대한 무차별 대입만 막는다. 같은 IP 에서 계정을 바꿔 가며
-- 흔한 비밀번호를 하나씩 넣어 보는 스프레이는 계정마다 실패가 1~2회라 잠금에 걸리지 않는다.
-- 그래서 (IP 키, 계정) 쌍마다 행 하나를 두고, 15분 안에 한 IP 키에서 실패한 서로 다른 계정이 10개 이상이면 그 IP 의 로그인을 거절한다.
--
-- ip_key: IPv4 는 주소 하나, IPv6 는 /64 로 묶은 접두(예 2001:db8:1:2::/64) — LoginIpThrottleService 가 만든다.
-- expires_at: 같은 계정이 다시 실패하면 행을 새로 만들지 않고 만료만 now() + 15분으로 미룬다(UPSERT). 15분은
--   JooqLoginIpFailureRepository 의 SQL 에 인라인돼 있다 — 바꿀 때 함께 고친다.
-- 만료 행은 lazy expiry(expires_at > now())로 무시되고, LoginAttemptCleanupScheduler 가 1시간 주기로 지운다.
-- 테넌트 개념이 없는 전역 테이블이라 RLS 를 두지 않는다(login_attempts 와 같음). app_tenant 권한은 V83 의 DEFAULT PRIVILEGES 가 준다.
CREATE TABLE login_ip_failures (
  ip_key      VARCHAR(64)  NOT NULL,
  username    VARCHAR(255) NOT NULL,
  expires_at  TIMESTAMP    NOT NULL,
  PRIMARY KEY (ip_key, username)
);

CREATE INDEX idx_login_ip_failures_expires_at ON login_ip_failures(expires_at);

COMMENT ON TABLE login_ip_failures IS '로그인 IP 기준 제한용 실패 기록. (IP 키, 계정) 쌍마다 한 행, 15분 만료(WD-578).';
COMMENT ON COLUMN login_ip_failures.ip_key IS 'IPv4 주소 또는 IPv6 /64 접두';
COMMENT ON COLUMN login_ip_failures.username IS '로그인에 실패한 계정(정규화된 아이디)';
COMMENT ON COLUMN login_ip_failures.expires_at IS '이 실패 기록이 집계에서 빠지는 시각(DB 시계 기준)';
