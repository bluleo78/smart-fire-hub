-- P2-b: pipeline · trigger · api_connection · async_job · uploaded_files 도메인 테넌트 컬럼.
--
-- 순서가 중요하다: nullable 추가 → 백필 → DEFAULT → NOT NULL → FK → 인덱스.
-- DEFAULT 를 먼저 넣으면 Flyway 는 GUC 없이 도는 소유자 롤이라 current_setting 이 NULL 을 주고
-- NOT NULL 위반으로 마이그레이션이 깨진다.
--
-- 루트(pipeline, api_connection, async_job, uploaded_files)는 부모가 없으므로 기본 테넌트
-- (V81 이 시드한 id=1)로 백필한다. 전환 시점에 테넌트는 하나뿐이라 이것이 정확한 값이다.
-- 이 4개가 참조하는 "user" 는 P1 에서 테넌트 경계 위의 전역 테이블로 남았으므로 백필 경로가 아니다.
--
-- 이 파일에는 정책이 없다 — 정책은 V96 이며, 그 사이 태스크들이 배경·비인증 경로를 배선한다.

---------------------------------------------------------------------------
-- L1: 루트
---------------------------------------------------------------------------

ALTER TABLE pipeline ADD COLUMN tenant_id BIGINT;
UPDATE pipeline SET tenant_id = 1 WHERE tenant_id IS NULL;
ALTER TABLE pipeline ALTER COLUMN tenant_id
  SET DEFAULT NULLIF(current_setting('app.tenant_id', true), '')::bigint;
ALTER TABLE pipeline ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE pipeline ADD CONSTRAINT fk_pipeline_tenant FOREIGN KEY (tenant_id) REFERENCES tenant(id);
CREATE INDEX idx_pipeline_tenant ON pipeline(tenant_id);

ALTER TABLE api_connection ADD COLUMN tenant_id BIGINT;
UPDATE api_connection SET tenant_id = 1 WHERE tenant_id IS NULL;
ALTER TABLE api_connection ALTER COLUMN tenant_id
  SET DEFAULT NULLIF(current_setting('app.tenant_id', true), '')::bigint;
ALTER TABLE api_connection ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE api_connection ADD CONSTRAINT fk_api_connection_tenant FOREIGN KEY (tenant_id) REFERENCES tenant(id);
CREATE INDEX idx_api_connection_tenant ON api_connection(tenant_id);

ALTER TABLE async_job ADD COLUMN tenant_id BIGINT;
UPDATE async_job SET tenant_id = 1 WHERE tenant_id IS NULL;
ALTER TABLE async_job ALTER COLUMN tenant_id
  SET DEFAULT NULLIF(current_setting('app.tenant_id', true), '')::bigint;
ALTER TABLE async_job ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE async_job ADD CONSTRAINT fk_async_job_tenant FOREIGN KEY (tenant_id) REFERENCES tenant(id);
CREATE INDEX idx_async_job_tenant ON async_job(tenant_id);

ALTER TABLE uploaded_files ADD COLUMN tenant_id BIGINT;
UPDATE uploaded_files SET tenant_id = 1 WHERE tenant_id IS NULL;
ALTER TABLE uploaded_files ALTER COLUMN tenant_id
  SET DEFAULT NULLIF(current_setting('app.tenant_id', true), '')::bigint;
ALTER TABLE uploaded_files ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE uploaded_files ADD CONSTRAINT fk_uploaded_files_tenant FOREIGN KEY (tenant_id) REFERENCES tenant(id);
CREATE INDEX idx_uploaded_files_tenant ON uploaded_files(tenant_id);

---------------------------------------------------------------------------
-- L2: pipeline 의 직계 자식 (pipeline_id 가 전부 NOT NULL)
---------------------------------------------------------------------------

ALTER TABLE pipeline_step ADD COLUMN tenant_id BIGINT;
UPDATE pipeline_step c SET tenant_id = p.tenant_id FROM pipeline p WHERE c.pipeline_id = p.id;
ALTER TABLE pipeline_step ALTER COLUMN tenant_id
  SET DEFAULT NULLIF(current_setting('app.tenant_id', true), '')::bigint;
ALTER TABLE pipeline_step ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE pipeline_step ADD CONSTRAINT fk_pipeline_step_tenant FOREIGN KEY (tenant_id) REFERENCES tenant(id);
CREATE INDEX idx_pipeline_step_tenant ON pipeline_step(tenant_id);

ALTER TABLE pipeline_execution ADD COLUMN tenant_id BIGINT;
UPDATE pipeline_execution c SET tenant_id = p.tenant_id FROM pipeline p WHERE c.pipeline_id = p.id;
ALTER TABLE pipeline_execution ALTER COLUMN tenant_id
  SET DEFAULT NULLIF(current_setting('app.tenant_id', true), '')::bigint;
ALTER TABLE pipeline_execution ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE pipeline_execution ADD CONSTRAINT fk_pipeline_execution_tenant FOREIGN KEY (tenant_id) REFERENCES tenant(id);
CREATE INDEX idx_pipeline_execution_tenant ON pipeline_execution(tenant_id);

ALTER TABLE pipeline_trigger ADD COLUMN tenant_id BIGINT;
UPDATE pipeline_trigger c SET tenant_id = p.tenant_id FROM pipeline p WHERE c.pipeline_id = p.id;
ALTER TABLE pipeline_trigger ALTER COLUMN tenant_id
  SET DEFAULT NULLIF(current_setting('app.tenant_id', true), '')::bigint;
ALTER TABLE pipeline_trigger ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE pipeline_trigger ADD CONSTRAINT fk_pipeline_trigger_tenant FOREIGN KEY (tenant_id) REFERENCES tenant(id);
CREATE INDEX idx_pipeline_trigger_tenant ON pipeline_trigger(tenant_id);

-- trigger_event 는 trigger_id 와 pipeline_id 를 둘 다 NOT NULL 로 갖지만 pipeline 경유로 백필한다.
-- 경로가 짧고 pipeline_trigger 에 대한 순서 의존성이 사라진다. execution_id 는 nullable 이라 경로가 아니다.
ALTER TABLE trigger_event ADD COLUMN tenant_id BIGINT;
UPDATE trigger_event c SET tenant_id = p.tenant_id FROM pipeline p WHERE c.pipeline_id = p.id;
ALTER TABLE trigger_event ALTER COLUMN tenant_id
  SET DEFAULT NULLIF(current_setting('app.tenant_id', true), '')::bigint;
ALTER TABLE trigger_event ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE trigger_event ADD CONSTRAINT fk_trigger_event_tenant FOREIGN KEY (tenant_id) REFERENCES tenant(id);
CREATE INDEX idx_trigger_event_tenant ON trigger_event(tenant_id);

---------------------------------------------------------------------------
-- L3: L2 의 자식
---------------------------------------------------------------------------

-- pipeline_step_input / pipeline_step_dependency 는 서로게이트 PK 없이 복합 PK 만 있다.
-- tenant_id 는 추가하되 PK 는 건드리지 않는다 — PK 컬럼이 이미 부모 스코프라 크로스테넌트 충돌이 불가능하다.
ALTER TABLE pipeline_step_input ADD COLUMN tenant_id BIGINT;
UPDATE pipeline_step_input c SET tenant_id = p.tenant_id FROM pipeline_step p WHERE c.step_id = p.id;
ALTER TABLE pipeline_step_input ALTER COLUMN tenant_id
  SET DEFAULT NULLIF(current_setting('app.tenant_id', true), '')::bigint;
ALTER TABLE pipeline_step_input ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE pipeline_step_input ADD CONSTRAINT fk_pipeline_step_input_tenant FOREIGN KEY (tenant_id) REFERENCES tenant(id);
CREATE INDEX idx_pipeline_step_input_tenant ON pipeline_step_input(tenant_id);

ALTER TABLE pipeline_step_dependency ADD COLUMN tenant_id BIGINT;
UPDATE pipeline_step_dependency c SET tenant_id = p.tenant_id FROM pipeline_step p WHERE c.step_id = p.id;
ALTER TABLE pipeline_step_dependency ALTER COLUMN tenant_id
  SET DEFAULT NULLIF(current_setting('app.tenant_id', true), '')::bigint;
ALTER TABLE pipeline_step_dependency ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE pipeline_step_dependency ADD CONSTRAINT fk_pipeline_step_dependency_tenant FOREIGN KEY (tenant_id) REFERENCES tenant(id);
CREATE INDEX idx_pipeline_step_dependency_tenant ON pipeline_step_dependency(tenant_id);

ALTER TABLE pipeline_step_execution ADD COLUMN tenant_id BIGINT;
UPDATE pipeline_step_execution c SET tenant_id = p.tenant_id FROM pipeline_execution p WHERE c.execution_id = p.id;
ALTER TABLE pipeline_step_execution ALTER COLUMN tenant_id
  SET DEFAULT NULLIF(current_setting('app.tenant_id', true), '')::bigint;
ALTER TABLE pipeline_step_execution ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE pipeline_step_execution ADD CONSTRAINT fk_pipeline_step_execution_tenant FOREIGN KEY (tenant_id) REFERENCES tenant(id);
CREATE INDEX idx_pipeline_step_execution_tenant ON pipeline_step_execution(tenant_id);

---------------------------------------------------------------------------
-- 유니크 인덱스 접기
--
-- 유니크 인덱스는 RLS 와 무관하게 전역 적용된다. 접지 않으면 두 테넌트가 같은 이름을 못 쓴다.
-- 접을 대상은 정확히 2개다(2026-08-14 라이브 전수 확인):
--   * idx_pipeline_name          — 순수 UNIQUE 인덱스이므로 DROP INDEX (CONSTRAINT 가 아니다)
--   * idx_async_job_active_unique — 부분 유니크. WHERE 절을 반드시 유지한다
-- 건드리지 않는 것:
--   * pipeline_step_pipeline_id_name_key — 이미 부모(pipeline_id) 스코프
--   * pipeline_step_input / pipeline_step_dependency 의 복합 PK — 이미 부모 스코프
--   * api_connection.name — 유니크 제약이 애초에 없다
--   * pipeline_trigger 의 외부 식별자 — V94 에서 '전역' 유니크로 새로 만든다(인증 전에는 테넌트를 모른다)
---------------------------------------------------------------------------

DROP INDEX idx_pipeline_name;
CREATE UNIQUE INDEX idx_pipeline_name ON pipeline(tenant_id, name);

-- resource_id 는 dataset id 같은 테넌트 로컬 식별자라, 접지 않으면 두 번째 테넌트의 임포트가
-- 중복 잡 에러로 실패한다. WHERE 절(진행 중인 잡에만 적용)은 원본 그대로 유지한다.
DROP INDEX idx_async_job_active_unique;
CREATE UNIQUE INDEX idx_async_job_active_unique
  ON async_job (tenant_id, job_type, resource, resource_id)
  WHERE stage NOT IN ('COMPLETED', 'FAILED');
