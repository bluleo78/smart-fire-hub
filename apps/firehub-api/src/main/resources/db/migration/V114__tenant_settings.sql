-- V114: P7-b 설정 2단 상속. system_settings 는 플랫폼 기본값으로 남고 이 테이블이 테넌트
-- 오버라이드를 담는다. 해석 규칙은 "tenant_settings 에 값이 있으면 그 값, 없으면 system_settings".
--
-- 왜 백필하지 않는가: 빈 테이블 + 폴백이 곧 "전 테넌트가 현재 값을 그대로 본다"이므로 백필은
-- 불필요하고, 공유 test DB 에는 우리가 만들지 않은 테넌트가 수백 개라 18키×N 을 쓰면 남의 행을
-- 만든다. provision_tenant_defaults 에도 시드를 넣지 않는다 — 신규 테넌트는 빈 채로 상속한다.
--
-- 정책은 plain '=' 다. audit_log 의 IS NOT DISTINCT FROM 을 복사하면 안 된다 — 그것은 로그인
-- 시점의 NULL 테넌트 행을 허용하기 위한 예외이고, tenant_id 가 NOT NULL 인 이 테이블에 쓰면
-- 아무 이득 없이 NULL-vs-NULL 참 조건만 남는다(장래에 NULL 을 허용하면 전 테넌트에 노출된다).
--
-- NULLIF(..., '') 는 생략할 수 없다 — 풀링 커넥션에서 GUC 가 빈 문자열로 남을 수 있고
-- ''::bigint 는 에러다. current_setting 의 두 번째 인자 true 는 미설정 시 NULL 을 주므로
-- 컨텍스트가 없으면 조건이 NULL → 전 행 차단(fail-closed)이 된다.
--
-- FORCE ROW LEVEL SECURITY 는 쓰지 않는다(V99 와 같은 이유 — SECURITY DEFINER 프로비저닝
-- 함수와 트리거 해석 함수가 소유자 권한으로 읽어야 한다).
--
-- GRANT 를 쓰지 않는 이유: V83 의 ALTER DEFAULT PRIVILEGES IN SCHEMA public 이 app 롤이
-- 만드는 신규 테이블을 app_tenant 에 자동 부여한다. Step 3 이 실측으로 확인한다.

CREATE TABLE tenant_settings (
  tenant_id  BIGINT       NOT NULL REFERENCES tenant(id) ON DELETE CASCADE,
  key        VARCHAR(100) NOT NULL,
  value      TEXT         NOT NULL,
  updated_by BIGINT       REFERENCES "user"(id),
  updated_at TIMESTAMP    NOT NULL DEFAULT now(),
  PRIMARY KEY (tenant_id, key)
);

COMMENT ON TABLE tenant_settings IS
  'P7-b 테넌트 설정 오버라이드. 행이 없으면 system_settings(플랫폼 기본값)로 폴백한다.';

ALTER TABLE tenant_settings ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_settings_tenant_isolation ON tenant_settings
  USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint)
  WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint);
