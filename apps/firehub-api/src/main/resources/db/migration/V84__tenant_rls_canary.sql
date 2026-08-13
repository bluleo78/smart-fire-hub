-- V84: RLS 격리 증명용 카나리 테이블. 운영 도메인이 아니다.
-- 두 가지를 동시에 증명한다: (1) app_tenant 로 접속하면 GUC 기준으로 행이 격리된다,
-- (2) V83 의 ALTER DEFAULT PRIVILEGES 가 실제로 동작한다(이 테이블에 명시 GRANT 를 하지 않는다).
CREATE TABLE tenant_canary (
    id        BIGSERIAL   PRIMARY KEY,
    tenant_id BIGINT      NOT NULL,
    val       VARCHAR(64) NOT NULL
);
CREATE INDEX idx_tenant_canary_tenant ON tenant_canary(tenant_id);

-- INSERT 시 tenant_id 를 명시하지 않아도 GUC 에서 자동으로 채워진다 → jOOQ 코드는 tenant_id 를 모른다.
ALTER TABLE tenant_canary ALTER COLUMN tenant_id
  SET DEFAULT NULLIF(current_setting('app.tenant_id', true), '')::bigint;

ALTER TABLE tenant_canary ENABLE ROW LEVEL SECURITY;

-- GUC 해석 규칙(P2 의 모든 정책에 그대로 적용):
--   current_setting(...,true) 는 "한 번도 설정 안 됨"이면 NULL 이지만, set_config 가 한 번이라도
--   호출된 풀 커넥션에서는 트랜잭션 종료 후 빈 문자열('')로 남는다. ''::bigint 는 에러(fail-ERROR)라
--   NULLIF(...,'') 로 빈 문자열을 NULL 로 환산해 비교를 NULL(=행 비가시, fail-closed)로 만든다.
-- WITH CHECK: 현재 GUC 와 다른 tenant_id 삽입/수정을 차단한다.
CREATE POLICY tenant_canary_isolation ON tenant_canary
  USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint)
  WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint);

COMMENT ON TABLE tenant_canary IS 'RLS 격리 회귀 가드. 삭제하지 말 것 — RlsIsolationTest 가 사용한다';
