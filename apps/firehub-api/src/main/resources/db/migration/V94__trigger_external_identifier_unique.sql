-- 외부 트리거 식별자(tokenHash / webhookId)는 config JSONB 안에 있고 유니크 제약이 전혀 없었다.
-- SECURITY DEFINER 해석 함수(V95)가 이 값으로 테넌트를 결정하므로, 중복이 존재하면 해석이
-- 모호해진다(현재 코드도 fetchOptional 이라 중복 시 런타임 예외).
--
-- **반드시 전역 유니크여야 한다.** 인증 전에는 테넌트를 알 수 없으므로 tenant_id 를 접으면
-- 두 테넌트가 같은 토큰을 가질 수 있고 해석 함수가 두 행을 만난다. 이는 V87 이
-- idx_dataset_table_name 을 전역으로 남긴 것과 같은 판단이다. TriggerTenantResolverTest 가 고정한다.
--
-- 부수 효과로 조회 성능도 개선된다 — 현재 두 조회는 풀스캔이다.

CREATE UNIQUE INDEX uq_trigger_api_token_hash
  ON pipeline_trigger ((config->>'tokenHash'))
  WHERE trigger_type = 'API';

CREATE UNIQUE INDEX uq_trigger_webhook_id
  ON pipeline_trigger ((config->>'webhookId'))
  WHERE trigger_type = 'WEBHOOK';
