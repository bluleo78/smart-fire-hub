-- V115: tenant_settings.tenant_id 에 GUC DEFAULT 를 붙인다. V114 가 빠뜨렸다.
--
-- 왜 별도 마이그레이션인가: V114 는 이미 dev·test DB 양쪽에 적용돼 체크섬이 잠겼다. 편집하면
-- 양쪽 부팅이 깨진다. 그래서 누락은 항상 새 파일로 고친다.
--
-- 왜 필요한가: 이 프로젝트의 RLS 테이블은 예외 없이 tenant_id DEFAULT 로 GUC 를 읽는다
-- (TenantSchemaConformanceTest.tenantColumnDefaultReadsGuc 가 전수 검사한다). 앱 코드가
-- tenant_id 를 직접 넣지 않는 것이 기본 전제이고, DEFAULT 가 없으면 그런 INSERT 는 전부
-- NOT NULL 위반(23502)으로 깨진다. tenant_settings 만 이 규약에서 벗어나 있었다 —
-- TenantSettingsRepository.upsert 가 tenant_id 를 명시해서 지금 당장은 동작했기 때문에
-- 전체 스위트를 돌릴 때까지 드러나지 않았다.
--
-- 리포지토리의 명시 지정은 그대로 둔다. 둘은 경쟁하지 않고 역할이 다르다: 명시 지정은
-- RLS 를 우회하는 소유자 권한 커넥션에서도 자기 테넌트 행만 쓰게 하는 방어이고(delete 와
-- 같은 이유), DEFAULT 는 tenant_id 를 생략한 새 INSERT 경로가 나중에 추가될 때의 안전망이다.
--
-- 백필은 필요 없다 — DEFAULT 는 이후 INSERT 에만 적용되고, 이 테이블은 기존 행이 있어도
-- tenant_id 가 이미 NOT NULL 로 채워져 있다.

ALTER TABLE tenant_settings
  ALTER COLUMN tenant_id
  SET DEFAULT NULLIF(current_setting('app.tenant_id', true), '')::bigint;
