-- WD-152: 외부 트리거(API·웹훅)를 맞는 토큰/웹훅 id 로 불렀는데 트리거가 꺼져 있으면, 지금까지는 V95 해석 함수가
-- is_enabled 로 걸러 버려 "토큰이 틀렸다"(API 401) / "웹훅이 없다"(웹훅 404)와 구별할 수 없었다.
-- 호출자에게 "트리거가 꺼져 있어 실행하지 않았습니다"(409)를 알려 주려면, 꺼진 트리거도 테넌트를 해석해
-- 컨텍스트를 세운 뒤 RLS 아래에서 트리거를 읽어야 한다.
--
-- 그래서 두 함수에서 is_enabled 필터만 뺀다. 나머지 설계(V95)는 그대로다:
--   * 반환은 여전히 (trigger_id, tenant_id) 두 정수뿐이며 이 값은 외부 응답에 나가지 않는다
--   * 서명·반환형·search_path·SECURITY DEFINER 를 바꾸지 않는다 — CREATE OR REPLACE 는 반환형이 같으면
--     소유자와 EXECUTE 권한(PUBLIC 회수, app_tenant 부여)을 유지한다
--   * 켜짐 판정은 TriggerService.fireTrigger 한 곳에서 하고, 웹훅은 서명검증을 통과한 뒤에만 "꺼져 있음"을 응답한다

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
     AND t.config->>'webhookId' = p_webhook_id;
$$;
