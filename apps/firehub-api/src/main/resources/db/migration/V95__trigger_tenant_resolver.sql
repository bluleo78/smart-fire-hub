-- 비인증 permitAll 경로(/api/v1/triggers/api/**, /api/v1/triggers/webhook/**)는 요청 시점에
-- 테넌트를 알 수 없다. pipeline_trigger 에 RLS 가 걸리면(V96) GUC 가 비어 전 행이 차단돼
-- 모든 외부 트리거가 조용히 401/404 가 된다.
--
-- 해법: 소유자 권한으로 실행되는(=RLS 를 우회하는) 함수가 식별자 → (trigger_id, tenant_id) 만
-- 해석한다. 호출자는 그 tenant_id 로 컨텍스트를 세운 뒤 서명검증·발화를 전부 RLS 하에서 처리한다.
--
-- 이 함수는 시스템에서 RLS 를 우회하는 유일한 경로다. 그래서 최소로 설계했다:
--   * 트리거 행을 반환하지 않는다 — id 두 개뿐이라 노출면이 정수 하나로 제한된다
--   * search_path 를 고정한다 — definer 함수에서 고정하지 않으면 권한 상승 경로가 된다
--   * EXECUTE 를 PUBLIC 에서 회수하고 런타임 롤에만 부여한다 (기본값이 PUBLIC 허용이다)
--   * is_enabled 필터를 함수 안에 둔다 — 비활성 트리거는 테넌트조차 알려주지 않는다

CREATE OR REPLACE FUNCTION resolve_trigger_tenant_by_token_hash(p_token_hash TEXT)
RETURNS TABLE (trigger_id BIGINT, tenant_id BIGINT)
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
  SELECT t.id, t.tenant_id
    FROM pipeline_trigger t
   WHERE t.trigger_type = 'API'
     AND t.is_enabled = true
     AND t.config->>'tokenHash' = p_token_hash;
$$;

CREATE OR REPLACE FUNCTION resolve_trigger_tenant_by_webhook_id(p_webhook_id TEXT)
RETURNS TABLE (trigger_id BIGINT, tenant_id BIGINT)
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
  SELECT t.id, t.tenant_id
    FROM pipeline_trigger t
   WHERE t.trigger_type = 'WEBHOOK'
     AND t.is_enabled = true
     AND t.config->>'webhookId' = p_webhook_id;
$$;

REVOKE EXECUTE ON FUNCTION resolve_trigger_tenant_by_token_hash(TEXT) FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION resolve_trigger_tenant_by_webhook_id(TEXT) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION resolve_trigger_tenant_by_token_hash(TEXT) TO app_tenant;
GRANT EXECUTE ON FUNCTION resolve_trigger_tenant_by_webhook_id(TEXT) TO app_tenant;
