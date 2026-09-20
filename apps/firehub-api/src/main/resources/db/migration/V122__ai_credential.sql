-- V122: 옛 3키(ai.api_key / ai.cli_oauth_token / ai.agent_type)를 유형별 문서 ai.credential
-- 하나로 합친다. Task 1~4(AiCredentialDocument / AiCredentialService / 4개 소비처 전환)가 이미
-- 커밋돼 있고, 이 마이그레이션은 그 "저장 형태({v,agentType,payload,secret})"에 맞춰 기존 행을
-- 옮기기만 한다 — 새 읽기·쓰기 규칙은 만들지 않는다.
--
-- 처리 대상: 3키 중 하나라도 있는 (tenant_id 또는 플랫폼) 행 그룹. 없는 키는 1단계 번들 채움
-- 규칙("자격증명은 빈 문자열, agent_type 은 sdk")과 같은 기본값으로 채운다.
--
-- ai.model 은 건드리지 않는다 — 자격증명이 아니라 평면 설정 키로 남는다(스펙 §마이그레이션
-- §변환: "케이스는 둘뿐이다, 모델만 재정의한 테넌트 난제는 사라진다").
--
-- 옛 3키는 지우지 않는다. Flyway community 는 undo 가 없어 이 변환은 forward-only 이고,
-- providerId/baseURL/reasoningEffort 는 옛 3키로부터 되돌릴 수 없다 — 안정화 후 별도
-- 마이그레이션으로 지운다(스펙 §롤백).
--
-- (Ruling #20) 스냅샷 선행 조건 — 스펙 §롤백이 요구하는데 브리프에도 이 파일에도 없었다.
-- Flyway community 는 undo 가 없으므로, 이 마이그레이션을 배포하기 전에 **tenant_settings 와
-- system_settings 두 테이블 전체를 반드시 스냅샷(pg_dump 등)해 둔다.** 스냅샷 없이 돌리면:
-- sdk/cli/cli-api 자격증명(암호문 그대로)은 새로 생긴 ai.credential.secret 에서 재구성할 수
-- 있지만, opencode 의 providerId/baseURL/reasoningEffort 는 옛 3키 어디에도 없던 값이라(이
-- 마이그레이션 자체가 opencode 를 거부하므로 이번 배포에서는 안 만들어지지만, 이후 화면에서
-- 테넌트가 저장하는 순간부터는 존재한다) 그 문서를 잘못 지우거나 이후 마이그레이션이 잘못
-- 덮어쓰면 스냅샷 없이는 영영 복구할 수 없다.
--
-- 빈 비밀의 두 형태(V31/V41 이 시드한 리터럴 '' vs encryptIfSecret 가 암호화해 남긴 빈 문자열의
-- 암호문)를 이 마이그레이션은 구분하지 않는다 — 값을 그대로 복사만 한다. 복호화는 앱 안에만
-- 있어 순수 SQL 로는 두 형태를 구분할 수 없다(스펙 §마이그레이션 "빈 비밀의 두 형태에 주의").
-- 대신 AiCredentialDocument.secretNames 와 AiCredentialService.decryptOrEmpty(ForDisplay)(이미
-- Task 1/3 커밋)가 "키가 있는지"가 아니라 "복호화 결과가 비어 있지 않은지"로 설정 여부를
-- 판정한다 — decryptOrEmpty 는 isBlank() 인 값은 복호화를 시도하지 않고 그대로 ""로 취급하므로
-- 평문 ''를 암호문으로 오인해 복호화를 시도하지도 않는다. 그래서 순수 SQL 로 두 형태를 구분하지
-- 않고 그대로 옮겨도 조회 결과는 항상 "미설정"으로 올바르게 수렴한다(스펙이 제시한 두 해법 중
-- 두 번째 — 이 저장소는 이미 그 계약으로 구현돼 있어 마이그레이션이 새로 만들 것이 없다).
--
-- opencode 가드: 설계 전제가 "opencode 를 쓰는 테넌트가 없다"다. 깨지면 payload 가 빈
-- Opencode 레코드가 만들어져(providerId/baseURL 없음) 그 테넌트의 AI 기능이 전부 죽는다 —
-- 그래서 하나라도 있으면 마이그레이션 자체를 중단한다.
--
-- (Ruling #21) 가드는 opencode 하나만으로는 부족하다. ai.agent_type 이 빈 문자열이거나(그대로
-- agentType:"" 으로 옮겨진다) 오타(예: 언더스코어 'cli_api')여도 AiCredentialService.resolve()
-- 의 switch 는 알려진 값이 아니면 UnknownAgentTypeException 을 던지고 **플랫폼 값으로 폴백하지
-- 않는다**(6b1c6383 과금 회귀를 막으려던 그 fail-closed 설계 그대로) — 그 테넌트의 AI 기능이
-- 되돌릴 방법 없이 멈춘다(GET/DELETE 는 여전히 동작해 관리자가 값을 볼 수는 있지만, 고치려면
-- 관리자가 다시 저장해야 한다). tenant_settings.value 는 NOT NULL 이라 coalesce 로는 이 값을
-- 못 잡는다(NULL 이 애초에 나오지 않는다) — 그래서 값 자체를 화이트리스트로 검사한다.
-- forward-only 세계에서는 "마이그레이션이 시끄럽게 거부"가 "테넌트 하나가 조용히 죽는다"보다
-- 항상 낫다.
--
-- (전체 브랜치 리뷰 C1) 위 두 가드는 원래 tenant_settings 만 봤다 — system_settings 는 무방비였다.
-- 배포측 PVC(opencode.jsonc)가 플랫폼 기본값을 opencode 로 강하게 시사하는데, 여기를 안 막으면
-- 플랫폼 행이 agentType:"opencode",payload:{} 로 변환돼 그 값을 상속하는 **모든** 테넌트가 빈
-- Opencode("","","","") 를 받는다 — 채팅은 missingCredential, 분류는 자격증명 조립 단계에서
-- throw, proactive 는 400. 알 수 없는/빈 값이면 UnknownAgentTypeException. forward-only 라
-- 배포 시점부터 관리자가 다시 저장할 때까지 AI 기능이 전면 중단된다. Ruling #21 이 테넌트 평면에
-- 대해 쓴 논리가 플랫폼 행을 제외할 이유는 없다 — 그래서 두 가드 모두 system_settings 도 본다.
--
-- (Ruling #22) 두 INSERT(아래) 모두 ON CONFLICT 를 쓰지 않는다 — 의도적이다. ai.credential
-- 행이 이미 있는 상태에서 이 마이그레이션이 실행될 일은 forward-only 에서 원래 없어야 하지만
-- (Flyway 는 버전당 정확히 한 번만 적용한다), 그런 상태를 만나면 ON CONFLICT DO NOTHING 으로
-- 조용히 건너뛰는 대신 PK 충돌로 마이그레이션 자체를 실패시킨다 — 그래야 관리자가 "이미 있던
-- 행과 이 마이그레이션이 만들려던 값이 다를 수 있다"는 사실을 보고 판단할 기회가 생긴다.
-- UPSERT 로 조용히 덮어쓰면 그 차이를 아무도 모른다. 맨 끝의 변환 행 수 단언도 같은 이유로
-- "이 마이그레이션이 새로 만든 행 수"가 아니라 "현재 ai.credential 전체 행 수"를 센다 — 사전에
-- ai.credential 행이 하나라도 있으면 그 자체가 비정상이라 어차피 위 INSERT 에서 먼저 실패한다.
--
-- RLS: 이 마이그레이션은 소유자 app 으로 돌고 V114 가 tenant_settings 에 FORCE ROW LEVEL
-- SECURITY 를 걸지 않아 GUC 없이 전 행을 본다(소유자는 RLS 를 우회한다). 나중에 FORCE 가
-- 추가되면 소유자도 정책 대상이 되어 이 마이그레이션이 조용히 0행만 변환하게 된다 — 그 실패를
-- 가리기 위해 맨 끝에서 변환 행 수를 대상 테넌트 수와 대조해 단언한다.

DO $$
DECLARE
  opencode_count int;
  invalid_count int;
BEGIN
  SELECT count(*) INTO opencode_count
    FROM tenant_settings WHERE key = 'ai.agent_type' AND value = 'opencode';
  IF opencode_count > 0 THEN
    RAISE EXCEPTION
      'opencode 를 쓰는 테넌트가 % 개 있다 — 설계 전제(사용자 없음)가 깨졌으므로 중단한다.',
      opencode_count;
  END IF;

  -- 플랫폼 평면(system_settings)도 같은 이유로 막는다(전체 브랜치 리뷰 C1) — key 가 PK 라
  -- 테넌트처럼 여러 행이 아니라 최대 1행이므로 count 대신 EXISTS 로 충분하다.
  IF EXISTS (SELECT 1 FROM system_settings WHERE key = 'ai.agent_type' AND value = 'opencode') THEN
    RAISE EXCEPTION
      '플랫폼 기본 ai.agent_type 이 opencode 다 — 설계 전제(사용자 없음)가 깨졌으므로 중단한다.';
  END IF;

  SELECT count(*) INTO invalid_count
    FROM tenant_settings
   WHERE key = 'ai.agent_type' AND value NOT IN ('sdk', 'cli', 'cli-api');
  IF invalid_count > 0 THEN
    RAISE EXCEPTION
      '알 수 없는 ai.agent_type 값(빈 문자열/오타 등)을 가진 테넌트가 % 개 있다 — 변환하면 '
      'fail-closed 로 영구히 멈추는 테넌트가 생기므로 중단한다.',
      invalid_count;
  END IF;

  -- 플랫폼 평면도 동일하게 화이트리스트로 검사한다 — 여기를 통과시키면 상속 중인 테넌트
  -- 전부가 UnknownAgentTypeException 으로 fail-closed 된다(테넌트 하나가 아니라 전체가 죽는다는
  -- 점에서 오히려 더 심각하다).
  IF EXISTS (
    SELECT 1 FROM system_settings
     WHERE key = 'ai.agent_type' AND value NOT IN ('sdk', 'cli', 'cli-api')
  ) THEN
    RAISE EXCEPTION
      '알 수 없는 플랫폼 ai.agent_type 값(빈 문자열/오타 등)이다 — 상속 중인 테넌트 전부가 '
      'fail-closed 로 영구히 멈추므로 중단한다.';
  END IF;
END $$;

-- 테넌트 평면. 3키 중 하나라도 있는 tenant_id 만 그룹으로 묶여 ai.credential 한 행이 된다.
-- 없는 키는 ''로 채운다(1단계 채움 규칙과 동일한 값) — jsonb_strip_nulls 로 "키 자체를 지운다"가
-- 아니라 명시적으로 빈 문자열을 채운다. 두 형태 모두 애플리케이션은 똑같이 "미설정"으로 읽으므로
-- 최종 동작은 같지만, 명시적으로 채우면 마이그레이션 결과 JSON 만 봐도 의도("자격증명은 빈
-- 문자열")가 드러난다.
INSERT INTO tenant_settings (tenant_id, key, value, updated_by, updated_at)
SELECT
  t.tenant_id,
  'ai.credential',
  jsonb_build_object(
    'v', 1,
    'agentType', coalesce(max(t.value) FILTER (WHERE t.key = 'ai.agent_type'), 'sdk'),
    'payload', '{}'::jsonb,
    'secret', jsonb_build_object(
      'apiKey',     coalesce(max(t.value) FILTER (WHERE t.key = 'ai.api_key'), ''),
      'oauthToken', coalesce(max(t.value) FILTER (WHERE t.key = 'ai.cli_oauth_token'), '')
    )
  )::text,
  max(t.updated_by),
  max(t.updated_at)
FROM tenant_settings t
WHERE t.key IN ('ai.api_key', 'ai.cli_oauth_token', 'ai.agent_type')
GROUP BY t.tenant_id;

-- 플랫폼 평면. system_settings 는 tenant_settings 와 달리 그룹핑 축(tenant_id)이 없는 단일 행
-- 집합이다(key 가 PK, 테넌트당 여러 행이 아니라 전체에 최대 1행). 그래서 브리프 스케치의
-- "GROUP BY 없이 HAVING count(*)>0" 집계 트릭을 쓰지 않는다 — 대신 스칼라 서브쿼리 +
-- WHERE EXISTS 로 쓴다(각 서브쿼리는 key 가 PK 라 최대 1행만 돌려주므로 안전하다). GROUP BY 가
-- 애초에 필요 없는 자리에서 "집계 없이 집계 함수를 쓰는" 형태를 피하는 쪽이 의도가 분명하다.
INSERT INTO system_settings (key, value, description)
SELECT
  'ai.credential',
  jsonb_build_object(
    'v', 1,
    'agentType', coalesce((SELECT value FROM system_settings WHERE key = 'ai.agent_type'), 'sdk'),
    'payload', '{}'::jsonb,
    'secret', jsonb_build_object(
      'apiKey',     coalesce((SELECT value FROM system_settings WHERE key = 'ai.api_key'), ''),
      'oauthToken', coalesce((SELECT value FROM system_settings WHERE key = 'ai.cli_oauth_token'), '')
    )
  )::text,
  'AI 자격증명(유형별 구조)'
WHERE EXISTS (
  SELECT 1 FROM system_settings WHERE key IN ('ai.api_key', 'ai.cli_oauth_token', 'ai.agent_type')
);

-- 변환 행 수 단언 — 위 RLS 주석 참고. 대상 테넌트 수(3키 중 하나라도 가진 서로 다른 tenant_id
-- 수)와 실제로 생긴 ai.credential 행 수가 달라지면(예: FORCE RLS 로 일부가 안 보이게 됐거나,
-- WHERE 절 실수로 대상 집합이 갈리면) 마이그레이션 자체를 실패시켜 조용한 부분 변환을 막는다.
DO $$
DECLARE
  expected_tenants int;
  converted int;
BEGIN
  SELECT count(DISTINCT tenant_id) INTO expected_tenants
    FROM tenant_settings WHERE key IN ('ai.api_key', 'ai.cli_oauth_token', 'ai.agent_type');
  SELECT count(*) INTO converted FROM tenant_settings WHERE key = 'ai.credential';
  IF converted <> expected_tenants THEN
    RAISE EXCEPTION
      'ai.credential 변환 행(%) 이 대상 테넌트 수(%) 와 다르다 — 부분 변환 의심.',
      converted, expected_tenants;
  END IF;
END $$;
