-- 멀티 테넌시 P2-e: 프로액티브 5테이블 + AI 2테이블에 tenant_id 를 추가한다.
-- 정책(RLS)은 V104 에서 일괄로 켠다 — 중간 커밋에서 프로액티브 잡·AI 챗이 죽지 않게 하기 위함.
--
-- 순서는 협상 대상이 아니다: nullable 추가 → 백필 → DEFAULT → NOT NULL → FK → 유니크 접기 → 인덱스.
-- DEFAULT 를 백필보다 먼저 넣으면 Flyway(소유자 롤, GUC 없음)에서 NULLIF(...) 가 NULL 을 내
-- NOT NULL 위반으로 마이그레이션이 깨진다. P1~P2-d 에서 네 번 확인한 레시피다.
--
-- 백필 원칙(계획서 R1): 이 마이그레이션은 테넌트가 여럿인 환경(향후 prod)에서도 옳아야 하므로
-- `SET tenant_id = 1` 로 때우지 않고 부모/멤버십 조인으로 유도한다. 유도가 **모호한**
-- (= ACTIVE 멤버십이 2개 이상인) 행은 일부러 NULL 로 남겨 SET NOT NULL 이 배포를 멈추게 한다.
-- 조용히 1 로 때우면 남의 테넌트에 남의 데이터가 들어간다.

-- 1) nullable 로 컬럼 추가
ALTER TABLE proactive_job           ADD COLUMN tenant_id bigint;
ALTER TABLE proactive_job_execution ADD COLUMN tenant_id bigint;
ALTER TABLE proactive_message       ADD COLUMN tenant_id bigint;
ALTER TABLE metric_snapshot         ADD COLUMN tenant_id bigint;
ALTER TABLE anomaly_event           ADD COLUMN tenant_id bigint;
ALTER TABLE ai_session              ADD COLUMN tenant_id bigint;
ALTER TABLE ai_inference_cache      ADD COLUMN tenant_id bigint;

-- 2) 백필 — 순서가 중요하다. proactive_job 이 먼저 채워져야 자식 3테이블이 그것을 읽을 수 있고,
--    proactive_job_execution 이 채워져야 proactive_message 의 우선 경로가 성립한다.

-- 2-a) proactive_job / ai_session: 소유자 user_id 의 ACTIVE 멤버십에서 유도.
--      c = 1 조건이 "모호하면 실패시킨다"를 구현한다(멤버십 2개 이상이면 NULL 로 남음).
UPDATE proactive_job j SET tenant_id = m.tenant_id
  FROM (SELECT user_id, MIN(tenant_id) AS tenant_id, COUNT(*) AS c
          FROM membership WHERE status = 'ACTIVE' GROUP BY user_id) m
 WHERE m.user_id = j.user_id AND m.c = 1 AND j.tenant_id IS NULL;

UPDATE ai_session s SET tenant_id = m.tenant_id
  FROM (SELECT user_id, MIN(tenant_id) AS tenant_id, COUNT(*) AS c
          FROM membership WHERE status = 'ACTIVE' GROUP BY user_id) m
 WHERE m.user_id = s.user_id AND m.c = 1 AND s.tenant_id IS NULL;

-- 2-b) 잡의 자식 3테이블: job_id 로 부모의 테넌트를 그대로 승계.
UPDATE proactive_job_execution e SET tenant_id = j.tenant_id
  FROM proactive_job j WHERE j.id = e.job_id AND e.tenant_id IS NULL;

UPDATE metric_snapshot s SET tenant_id = j.tenant_id
  FROM proactive_job j WHERE j.id = s.job_id AND s.tenant_id IS NULL;

UPDATE anomaly_event a SET tenant_id = j.tenant_id
  FROM proactive_job j WHERE j.id = a.job_id AND a.tenant_id IS NULL;

-- 2-c) proactive_message 는 테넌트 출처가 둘이고 서로 어긋날 수 있다(R1).
--      execution_id 가 non-null 이면 그쪽이 권위다 — 메시지를 만들어낸 잡의 테넌트가
--      그 메시지의 테넌트다. user_id(수신자) 폴백은 execution_id 가 null 일 때만.
--      순서를 뒤집으면 execution 우선 규칙이 조용히 무력화된다.
UPDATE proactive_message pm SET tenant_id = e.tenant_id
  FROM proactive_job_execution e
 WHERE e.id = pm.execution_id AND pm.tenant_id IS NULL;

UPDATE proactive_message pm SET tenant_id = m.tenant_id
  FROM (SELECT user_id, MIN(tenant_id) AS tenant_id, COUNT(*) AS c
          FROM membership WHERE status = 'ACTIVE' GROUP BY user_id) m
 WHERE m.user_id = pm.user_id AND m.c = 1 AND pm.tenant_id IS NULL;

-- 2-d) 멤버십이 **0개**인 고아 행만 기본 테넌트로 마감한다.
--      0개(부재)와 2개 이상(모호)은 다르다: 부재는 유도할 근거가 없는 잔재 행이고,
--      모호는 잘못 고르면 남의 테넌트로 새는 행이다. 여기서는 부재만 쓸어담고
--      모호는 일부러 NULL 로 남겨 아래 SET NOT NULL 이 배포를 멈추게 한다.
--      실측(2026-08-15): dev DB 는 해당 0건, 공유 테스트 DB 는 다른 세션의 픽스처 잔재
--      106행(proactive_message)이 여기 해당한다. V101:45-48 의 고아 마감 패턴과 같되,
--      V101 이 조건 없이 쓸어담아 모호까지 삼켰던 점만 고쳤다.
UPDATE proactive_job j SET tenant_id = 1
 WHERE j.tenant_id IS NULL
   AND NOT EXISTS (SELECT 1 FROM membership m WHERE m.user_id = j.user_id AND m.status = 'ACTIVE');

UPDATE ai_session s SET tenant_id = 1
 WHERE s.tenant_id IS NULL
   AND NOT EXISTS (SELECT 1 FROM membership m WHERE m.user_id = s.user_id AND m.status = 'ACTIVE');

UPDATE proactive_message pm SET tenant_id = 1
 WHERE pm.tenant_id IS NULL
   AND pm.execution_id IS NULL
   AND NOT EXISTS (SELECT 1 FROM membership m WHERE m.user_id = pm.user_id AND m.status = 'ACTIVE');

-- 잡이 위 고아 마감으로 뒤늦게 채워졌을 수 있으므로 자식 승계를 한 번 더 흘린다.
UPDATE proactive_job_execution e SET tenant_id = j.tenant_id
  FROM proactive_job j WHERE j.id = e.job_id AND e.tenant_id IS NULL;
UPDATE metric_snapshot s SET tenant_id = j.tenant_id
  FROM proactive_job j WHERE j.id = s.job_id AND s.tenant_id IS NULL;
UPDATE anomaly_event a SET tenant_id = j.tenant_id
  FROM proactive_job j WHERE j.id = a.job_id AND a.tenant_id IS NULL;
UPDATE proactive_message pm SET tenant_id = e.tenant_id
  FROM proactive_job_execution e
 WHERE e.id = pm.execution_id AND pm.tenant_id IS NULL;

-- ai_inference_cache 는 실측 0행이라 백필이 없다(dev·test 양쪽).

-- 3) DEFAULT — 반드시 백필 뒤에. 앱은 tenant_id 를 쓰지 않고 GUC 에서 받는다.
ALTER TABLE proactive_job           ALTER COLUMN tenant_id SET DEFAULT NULLIF(current_setting('app.tenant_id', true), '')::bigint;
ALTER TABLE proactive_job_execution ALTER COLUMN tenant_id SET DEFAULT NULLIF(current_setting('app.tenant_id', true), '')::bigint;
ALTER TABLE proactive_message       ALTER COLUMN tenant_id SET DEFAULT NULLIF(current_setting('app.tenant_id', true), '')::bigint;
ALTER TABLE metric_snapshot         ALTER COLUMN tenant_id SET DEFAULT NULLIF(current_setting('app.tenant_id', true), '')::bigint;
ALTER TABLE anomaly_event           ALTER COLUMN tenant_id SET DEFAULT NULLIF(current_setting('app.tenant_id', true), '')::bigint;
ALTER TABLE ai_session              ALTER COLUMN tenant_id SET DEFAULT NULLIF(current_setting('app.tenant_id', true), '')::bigint;
ALTER TABLE ai_inference_cache      ALTER COLUMN tenant_id SET DEFAULT NULLIF(current_setting('app.tenant_id', true), '')::bigint;

-- 4) NOT NULL — 유도가 모호했던 행이 남아 있으면 여기서 배포가 멈춘다(의도된 fail-closed).
ALTER TABLE proactive_job           ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE proactive_job_execution ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE proactive_message       ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE metric_snapshot         ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE anomaly_event           ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE ai_session              ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE ai_inference_cache      ALTER COLUMN tenant_id SET NOT NULL;

-- 5) FK
ALTER TABLE proactive_job           ADD CONSTRAINT proactive_job_tenant_id_fkey           FOREIGN KEY (tenant_id) REFERENCES tenant(id);
ALTER TABLE proactive_job_execution ADD CONSTRAINT proactive_job_execution_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenant(id);
ALTER TABLE proactive_message       ADD CONSTRAINT proactive_message_tenant_id_fkey       FOREIGN KEY (tenant_id) REFERENCES tenant(id);
ALTER TABLE metric_snapshot         ADD CONSTRAINT metric_snapshot_tenant_id_fkey         FOREIGN KEY (tenant_id) REFERENCES tenant(id);
ALTER TABLE anomaly_event           ADD CONSTRAINT anomaly_event_tenant_id_fkey           FOREIGN KEY (tenant_id) REFERENCES tenant(id);
ALTER TABLE ai_session              ADD CONSTRAINT ai_session_tenant_id_fkey              FOREIGN KEY (tenant_id) REFERENCES tenant(id);
ALTER TABLE ai_inference_cache      ADD CONSTRAINT ai_inference_cache_tenant_id_fkey      FOREIGN KEY (tenant_id) REFERENCES tenant(id);

-- 6) 유니크 접기 — 유니크 인덱스는 RLS 와 무관하게 전역 적용된다.
--    접지 않으면 다른 테넌트의 보이지 않는 행과 키가 충돌해, 존재 여부가 누출되고
--    정상 생성이 원인 불명으로 거부된다.
--    프로액티브 5테이블에는 접을 유니크가 없다(PK 뿐).
ALTER TABLE ai_session DROP CONSTRAINT ai_session_session_id_key;
CREATE UNIQUE INDEX ai_session_session_id_key ON ai_session(tenant_id, session_id);

DROP INDEX uk_ai_session_slack_thread;
CREATE UNIQUE INDEX uk_ai_session_slack_thread
  ON ai_session(tenant_id, slack_team_id, slack_channel_id, slack_thread_ts)
  WHERE channel_source = 'SLACK';

-- ai_inference_cache 는 테넌트별로 파티션한다(R2). row_hash 는 분류 대상 **행 내용**의 해시라
-- 캐시를 공유하면 A 테넌트 행의 존재 여부와 추론 결과가 B 테넌트의 캐시 히트로 관측된다.
ALTER TABLE ai_inference_cache DROP CONSTRAINT ai_inference_cache_row_hash_prompt_version_key;
CREATE UNIQUE INDEX ai_inference_cache_row_hash_prompt_version_key
  ON ai_inference_cache(tenant_id, row_hash, prompt_version);

-- 위 유니크 인덱스와 컬럼 순서까지 완전히 중복이므로 제거한다(R7). 실측 0행이라 위험이 없다.
DROP INDEX idx_ai_inference_cache_lookup;

-- 7) 테넌트 선행 인덱스 — 정책이 모든 쿼리에 tenant_id 술어를 붙인다.
--    proactive_job 은 PK 외에 인덱스가 하나도 없고, 부팅 시 스케줄 재등록이
--    tenant_id + enabled 로 전수 조회하므로 이 한 건만 추가한다.
--    나머지 테이블은 기존 인덱스가 job_id/user_id 선행이라 충분히 선택적이다.
CREATE INDEX idx_proactive_job_tenant_enabled ON proactive_job(tenant_id, enabled);
