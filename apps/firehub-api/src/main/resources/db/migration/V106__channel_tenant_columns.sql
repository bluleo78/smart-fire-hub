-- 멀티 테넌시 P2-f: 채널 4테이블(slack_workspace, notification_outbox, user_channel_binding,
-- user_channel_preference)에 tenant_id 를 추가하고, oauth_state 에는 컬럼만 추가한다.
-- 정책(RLS)은 V107 에서 일괄로 켠다 — 중간 커밋에서 알림 발송이 통째로 멈추지 않게 하기 위함이다.
-- (리포지토리·배경 경로 배선이 Task 2·3 이라, 여기서 정책을 켜면 아웃박스 워커가 전 행을 못 본다.)
--
-- 순서는 협상 대상이 아니다: nullable 추가 → 백필 → DEFAULT → NOT NULL → FK → 유니크 접기 → 인덱스.
-- DEFAULT 를 백필보다 먼저 넣으면 Flyway(소유자 롤, GUC 없음)에서 NULLIF(...) 가 NULL 을 내
-- NOT NULL 위반으로 마이그레이션이 깨진다. P1~P2-e 에서 다섯 번 확인한 레시피다.
--
-- 백필 원칙: 테넌트가 여럿인 환경에서도 옳아야 하므로 `SET tenant_id = 1` 로 때우지 않고
-- 부모/멤버십 조인으로 유도한다. 유도가 **모호한**(= ACTIVE 멤버십이 2개 이상인) 행은 일부러
-- NULL 로 남겨 SET NOT NULL 이 배포를 멈추게 한다. 조용히 때우면 남의 테넌트로 데이터가 샌다.
--
-- ── 이 마이그레이션이 선례(V103)와 다른 네 지점과 그 근거 ────────────────────────────────
--
-- [R1] slack_workspace.team_id UNIQUE 는 **접지 않는다.**
--   접는 순간 같은 Slack 팀이 테넌트 A·B 에 각각 설치될 수 있고, 그러면 인바운드 웹훅의
--   team_id → tenant 해석이 원리적으로 다의(多義)가 되며 봇 토큰·서명 시크릿이 갈라져 저장된다.
--   코드 쪽으로도 SlackWorkspaceRepositoryImpl.findByTeamId 의 fetchOptional() 이 2행에서
--   예외를 던진다. V94 가 pipeline_trigger 의 외부 식별자를 전역 유니크로 남긴 것과 같은 판단이며,
--   그 부수 이득으로 upsertFromOAuth 의 .onConflict(TEAM_ID) 가 무변경이 된다.
--
-- [R2] 유니크 접기를 `CREATE UNIQUE INDEX` 가 아니라 `ADD CONSTRAINT` 로 한다.
--   V103:124-136 의 레시피(DROP CONSTRAINT → CREATE UNIQUE INDEX)를 그대로 쓰면
--   NotificationOutboxRepositoryImpl:43 과 UserChannelBindingRepositoryImpl:69 의
--   `ON CONFLICT ON CONSTRAINT <name>` 이 런타임에 깨진다 — 유니크 *인덱스* 는 제약이 아니라
--   이름으로 참조할 수 없고 Postgres 가 "there is no unique or exclusion constraint matching
--   the ON CONFLICT specification" 을 던진다. 그래서 **제약 이름을 그대로 유지한 채** 제약으로 다시 만든다.
--
-- [R5] 공유 테스트 DB 의 notification_outbox 잔재 행은 지우지 않는다 — 백필이 전부 흡수한다.
--   실측(2026-08-16, smartfirehub_test, 이 마이그레이션 작성 직전 재확인):
--     total = 3860, ACTIVE 멤버십 정확히 1개 = 342, 모호(2개 이상) = 0,
--     나머지 3518 = recipient_user_id 가 NULL 이거나 ACTIVE 멤버십 0개 → 고아 마감이 흡수한다.
--   (dev 는 0행, prod 는 0행. 백필 리스크는 prod 가 아니라 공유 테스트 DB 에 있다.)
--   이 행들은 실데이터가 아니라 비-@Transactional 통합 테스트가 흘린 잔재지만, "본인이 만들지
--   않은 행을 지우지 말라"가 우선하므로 삭제하지 않고 구성상 옳게 흡수한다.
--
-- [R6] outbox 백필에 V103 의 "execution 우선" 규칙을 복제하지 않는다.
--   V103:47-53 이 proactive_message 에 확립한 "execution_id 가 non-null 이면 그쪽이 권위" 를
--   여기 가져오지 않는 이유는 두 가지다: (1) outbox 의 executionId 는 정규화된 컬럼이 아니라
--   payload->'metadata'->>'executionId' JSONB 경로에 있는 **신뢰할 수 없는 입력**이고,
--   (2) 실측 3860행 중 그 값을 가진 행이 **0건**이라 우선 UPDATE 를 넣어도 0행에 작용한다.
--   컬럼으로 승격되면 그때 우선 규칙을 넣는다.
--
-- [R7] oauth_state 는 tenant_id 를 NOT NULL 로 두되 **RLS 는 걸지 않는다.**
--   이 컬럼은 격리 수단이 아니라 **OAuth 콜백에서 테넌트를 되찾는 운반 수단**이다.
--   issue() 는 인증된 HTTP 경로에서만 불려 GUC DEFAULT 가 채워지므로 NOT NULL 이 가능하고,
--   빈 값이면 콜백이 fail-closed 되어야 하므로 NOT NULL 이 옳다. 반대로 RLS 가 **없어야**
--   컨텍스트 없는 permitAll 콜백에서 consume(state) 이 성립한다 — 스펙의 "전역 테이블" 분류는
--   우연이 아니라 이 흐름의 전제다. state 유니크도 접지 않는다(32바이트 CSPRNG hex).
--
-- [폴백 테넌트] V103 은 고아 마감을 `SET tenant_id = 1` 로 하드코딩했으나, 여기서는
--   "가장 낮은 id 를 가진 ACTIVE 테넌트" 서브쿼리로 유도한다. dev/test/prod 의 기본 테넌트 id 가
--   같다는 보장이 없기 때문이다. ACTIVE 테넌트가 하나도 없으면 아래 가드가 마이그레이션을
--   중단시킨다(fail-closed) — 그런 DB 에서 고아 마감은 어떤 값을 골라도 틀리다.

DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM tenant WHERE status = 'ACTIVE') THEN
    RAISE EXCEPTION 'ACTIVE 테넌트가 없어 고아 행 마감 대상을 정할 수 없다 — V106 을 중단한다';
  END IF;
END $$;

-- 1) nullable 로 컬럼 추가
ALTER TABLE slack_workspace         ADD COLUMN tenant_id bigint;
ALTER TABLE notification_outbox     ADD COLUMN tenant_id bigint;
ALTER TABLE user_channel_binding    ADD COLUMN tenant_id bigint;
ALTER TABLE user_channel_preference ADD COLUMN tenant_id bigint;
ALTER TABLE oauth_state             ADD COLUMN tenant_id bigint;

-- 2) 백필 — 순서가 중요하다. slack_workspace 가 **완전히** 채워진 뒤에야
--    user_channel_binding 이 부모를 승계할 수 있다(고아 마감까지 끝난 뒤여야 한다).

-- 2-a) slack_workspace: 설치자(installed_by_user_id)의 ACTIVE 멤버십에서 유도.
--      c = 1 조건이 "모호하면 실패시킨다"를 구현한다(멤버십 2개 이상이면 NULL 로 남음).
UPDATE slack_workspace w SET tenant_id = m.tenant_id
  FROM (SELECT user_id, MIN(tenant_id) AS tenant_id, COUNT(*) AS c
          FROM membership WHERE status = 'ACTIVE' GROUP BY user_id) m
 WHERE m.user_id = w.installed_by_user_id AND m.c = 1 AND w.tenant_id IS NULL;

-- installed_by_user_id 는 nullable 이라 고아 마감이 필수다. 부재(0개)만 쓸어담고
-- 모호(2개 이상)는 NULL 로 남긴다 — 부재는 유도할 근거가 없는 행이고, 모호는 잘못 고르면
-- 남의 테넌트로 새는 행이다. (V101:45-48 이 조건 없이 삼켰던 점을 V103:66 이 고쳤고 여기도 따른다.)
UPDATE slack_workspace w SET tenant_id = (SELECT MIN(id) FROM tenant WHERE status = 'ACTIVE')
 WHERE w.tenant_id IS NULL
   AND NOT EXISTS (SELECT 1 FROM membership m
                    WHERE m.user_id = w.installed_by_user_id AND m.status = 'ACTIVE');

-- 2-b) user_channel_binding: **부모 우선.** workspace_id 가 non-null 이면 그 슬랙 워크스페이스가
--      권위다(그 워크스페이스에 속한 연동이므로). null 이면(EMAIL/KAKAO) 멤버십으로 폴백한다.
--      순서를 뒤집으면 우선 규칙이 조용히 무력화된다(V103:47-58 과 같은 패턴·같은 경고).
UPDATE user_channel_binding b SET tenant_id = w.tenant_id
  FROM slack_workspace w
 WHERE w.id = b.workspace_id AND b.tenant_id IS NULL;

UPDATE user_channel_binding b SET tenant_id = m.tenant_id
  FROM (SELECT user_id, MIN(tenant_id) AS tenant_id, COUNT(*) AS c
          FROM membership WHERE status = 'ACTIVE' GROUP BY user_id) m
 WHERE m.user_id = b.user_id AND m.c = 1 AND b.tenant_id IS NULL;

UPDATE user_channel_binding b SET tenant_id = (SELECT MIN(id) FROM tenant WHERE status = 'ACTIVE')
 WHERE b.tenant_id IS NULL
   AND b.workspace_id IS NULL
   AND NOT EXISTS (SELECT 1 FROM membership m
                    WHERE m.user_id = b.user_id AND m.status = 'ACTIVE');

-- 2-c) user_channel_preference: 부모가 없어 멤버십 단독.
UPDATE user_channel_preference p SET tenant_id = m.tenant_id
  FROM (SELECT user_id, MIN(tenant_id) AS tenant_id, COUNT(*) AS c
          FROM membership WHERE status = 'ACTIVE' GROUP BY user_id) m
 WHERE m.user_id = p.user_id AND m.c = 1 AND p.tenant_id IS NULL;

UPDATE user_channel_preference p SET tenant_id = (SELECT MIN(id) FROM tenant WHERE status = 'ACTIVE')
 WHERE p.tenant_id IS NULL
   AND NOT EXISTS (SELECT 1 FROM membership m
                    WHERE m.user_id = p.user_id AND m.status = 'ACTIVE');

-- 2-d) notification_outbox: 수신자(recipient_user_id)의 ACTIVE 멤버십. 위 [R6] 대로
--      execution 우선 규칙은 복제하지 않는다. recipient_user_id 는 nullable 이고 FK 도 없어
--      고아 마감이 필수다 — 실측 3518행이 여기서 마감된다.
UPDATE notification_outbox o SET tenant_id = m.tenant_id
  FROM (SELECT user_id, MIN(tenant_id) AS tenant_id, COUNT(*) AS c
          FROM membership WHERE status = 'ACTIVE' GROUP BY user_id) m
 WHERE m.user_id = o.recipient_user_id AND m.c = 1 AND o.tenant_id IS NULL;

UPDATE notification_outbox o SET tenant_id = (SELECT MIN(id) FROM tenant WHERE status = 'ACTIVE')
 WHERE o.tenant_id IS NULL
   AND NOT EXISTS (SELECT 1 FROM membership m
                    WHERE m.user_id = o.recipient_user_id AND m.status = 'ACTIVE');

-- 2-e) oauth_state: 멤버십 단독. TTL 10분짜리 CSRF 스토어라 만료 행이 섞여 있지만,
--      "본인이 만들지 않은 행을 지우지 말 것" 규칙에 따라 DELETE 하지 않고 백필로 흡수한다
--      (만료 행 정리는 NotificationRetentionJob 의 몫이다).
UPDATE oauth_state s SET tenant_id = m.tenant_id
  FROM (SELECT user_id, MIN(tenant_id) AS tenant_id, COUNT(*) AS c
          FROM membership WHERE status = 'ACTIVE' GROUP BY user_id) m
 WHERE m.user_id = s.user_id AND m.c = 1 AND s.tenant_id IS NULL;

UPDATE oauth_state s SET tenant_id = (SELECT MIN(id) FROM tenant WHERE status = 'ACTIVE')
 WHERE s.tenant_id IS NULL
   AND NOT EXISTS (SELECT 1 FROM membership m
                    WHERE m.user_id = s.user_id AND m.status = 'ACTIVE');

-- 3) DEFAULT — 반드시 백필 뒤에. 앱은 tenant_id 를 쓰지 않고 GUC 에서 받는다.
ALTER TABLE slack_workspace         ALTER COLUMN tenant_id SET DEFAULT NULLIF(current_setting('app.tenant_id', true), '')::bigint;
ALTER TABLE notification_outbox     ALTER COLUMN tenant_id SET DEFAULT NULLIF(current_setting('app.tenant_id', true), '')::bigint;
ALTER TABLE user_channel_binding    ALTER COLUMN tenant_id SET DEFAULT NULLIF(current_setting('app.tenant_id', true), '')::bigint;
ALTER TABLE user_channel_preference ALTER COLUMN tenant_id SET DEFAULT NULLIF(current_setting('app.tenant_id', true), '')::bigint;
ALTER TABLE oauth_state             ALTER COLUMN tenant_id SET DEFAULT NULLIF(current_setting('app.tenant_id', true), '')::bigint;

-- 4) NOT NULL — 유도가 모호했던 행이 남아 있으면 여기서 배포가 멈춘다(의도된 fail-closed).
ALTER TABLE slack_workspace         ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE notification_outbox     ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE user_channel_binding    ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE user_channel_preference ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE oauth_state             ALTER COLUMN tenant_id SET NOT NULL;

-- 5) FK → tenant(id). oauth_state 도 RLS 는 없지만 값의 정합성은 지켜야 하므로 FK 는 건다.
ALTER TABLE slack_workspace         ADD CONSTRAINT slack_workspace_tenant_id_fkey         FOREIGN KEY (tenant_id) REFERENCES tenant(id);
ALTER TABLE notification_outbox     ADD CONSTRAINT notification_outbox_tenant_id_fkey     FOREIGN KEY (tenant_id) REFERENCES tenant(id);
ALTER TABLE user_channel_binding    ADD CONSTRAINT user_channel_binding_tenant_id_fkey    FOREIGN KEY (tenant_id) REFERENCES tenant(id);
ALTER TABLE user_channel_preference ADD CONSTRAINT user_channel_preference_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenant(id);
ALTER TABLE oauth_state             ADD CONSTRAINT oauth_state_tenant_id_fkey             FOREIGN KEY (tenant_id) REFERENCES tenant(id);

-- 6) 유니크 접기 — 유니크 제약은 RLS 와 무관하게 전역 적용된다. 접지 않으면 다른 테넌트의
--    보이지 않는 행과 키가 충돌해, 존재 여부가 누출되고 정상 생성이 원인 불명으로 거부된다.
--    [R2] 대로 전부 CREATE UNIQUE INDEX 가 아니라 ADD CONSTRAINT 이며, 이름은 그대로 둔다.

-- 멱등키는 IdempotencyKeyGenerator.generate(correlationId, channel, userId) 로 만들어져
-- 테넌트가 들어가지 않는다. 접지 않으면 A 테넌트의 보이지 않는 행과 충돌해 B 테넌트의 정상
-- enqueue 가 doNothing() 으로 조용히 삼켜진다 — 알림이 예외 없이 사라지는 형태다.
ALTER TABLE notification_outbox DROP CONSTRAINT uk_outbox_idempotency;
ALTER TABLE notification_outbox ADD  CONSTRAINT uk_outbox_idempotency UNIQUE (tenant_id, idempotency_key);

-- 같은 사용자가 두 테넌트에 소속돼 각 테넌트의 Slack 워크스페이스에 따로 연동할 수 있어야 한다.
-- 주의(기존 결함, 이 밴드 소관 아님): workspace_id 가 nullable 이고 Postgres 는 UNIQUE 에서
-- NULL 을 서로 구별하므로 이 제약은 접은 뒤에도 EMAIL/KAKAO 의 사용자당 중복 바인딩을 막지 못한다.
-- 접기가 그 구멍을 사주지는 않는다 — 고치려면 NULLS NOT DISTINCT 또는 부분 유니크 두 벌이 필요하다.
ALTER TABLE user_channel_binding DROP CONSTRAINT uk_user_channel;
ALTER TABLE user_channel_binding ADD  CONSTRAINT uk_user_channel UNIQUE (tenant_id, user_id, channel_type, workspace_id);

-- 사용자가 테넌트마다 다른 채널 선호를 가질 수 있어야 한다. CHECK(chat_always_enabled)은
-- 테넌트와 무관하므로 손대지 않는다.
-- ※ UserChannelPreferenceRepositoryImpl:47 은 컬럼목록 추론형 ON CONFLICT 라 목록에 TENANT_ID 를
--    넣는 코드 수정이 Task 2 에서 함께 가야 한다(제약이든 인덱스든 무관하게 필요한 수정).
ALTER TABLE user_channel_preference DROP CONSTRAINT uk_preference;
ALTER TABLE user_channel_preference ADD  CONSTRAINT uk_preference UNIQUE (tenant_id, user_id, channel_type);

-- slack_workspace.team_id / oauth_state.state 는 [R1]·[R7] 대로 전역 유니크로 남긴다.

-- 7) 테넌트 선행 인덱스 — 정책이 모든 쿼리에 tenant_id 술어를 붙인다.
--    단 idx_outbox_pending_due 는 **그대로 둔다**: outbox_tenant_ids definer 함수의
--    DISTINCT tenant_id 스캔이 이 부분 인덱스를 타야 하고, 그 스캔에는 tenant_id 술어가 없다.
DROP INDEX idx_outbox_recipient;
CREATE INDEX idx_outbox_recipient ON notification_outbox(tenant_id, recipient_user_id, created_at DESC);

DROP INDEX idx_outbox_correlation;
CREATE INDEX idx_outbox_correlation ON notification_outbox(tenant_id, correlation_id);

-- 좀비 리퍼가 outbox_tenant_ids 로 테넌트별 회수를 돌게 되므로(계획 R4) 테넌트 선행이 옳다.
DROP INDEX idx_outbox_zombie;
CREATE INDEX idx_outbox_zombie ON notification_outbox(tenant_id, claimed_at) WHERE status = 'SENDING';

-- 8) SECURITY DEFINER 해석기 2종 — V95__trigger_tenant_resolver.sql 의 형태를 그대로 복제한다.

-- 아웃박스 워커·좀비 리퍼·보존 잡·메트릭 게이지는 컨텍스트 없는 배경 스레드에서 돈다.
-- V107 로 정책을 켜면 GUC 가 비어 전 행이 차단돼 알림이 조용히 영구 정지한다.
--
-- 해법: 소유자 권한으로 실행되는(=RLS 를 우회하는) 함수가 "일감이 있는 테넌트 id" 만 알려준다.
-- 호출자는 그 목록으로 테넌트마다 컨텍스트를 열고, 실제 클레임·회수·삭제는 전부 RLS 하에서 한다.
-- 상태 인자로 용도를 가른다: 클레임 {PENDING}, 좀비 리퍼 {SENDING}, 보존 삭제
-- {SENT, PERMANENT_FAILURE}, 메트릭 게이지 {PENDING}.
--
-- TenantScopedRunner.forEachActiveTenant 를 쓰지 않는 이유: 그쪽은 ACTIVE 테넌트만 돌아
-- 비활성·정지 테넌트의 outbox 행이 영원히 좀비 회수도 보존 삭제도 되지 않는 누수가 생긴다.
-- 이 함수는 테이블에 실제로 존재하는 테넌트에서 유도하므로 그 구멍이 원천적으로 없다.
--
-- 이 함수를 **클레임까지 시키지 않은 이유가 중요하다**: AnalyticsQueryExecutionService.executeDirectly
-- 가 사용자 SQL 을 런타임 롤(app_tenant)로 그대로 실행하고 차단이 문자열 매칭뿐이라, 쓰기를 하는
-- definer 함수에 EXECUTE 를 주면 쿼리 UI 를 쓸 수 있는 아무 사용자나 전 테넌트 outbox 행을
-- SENDING 으로 뒤집을 수 있다. 읽기 전용 + 정수 목록으로 축소해 노출면을 V95 수준으로 유지한다
-- (최악의 유출은 "어느 테넌트에 특정 상태의 outbox 행이 있는가" 뿐이다).
CREATE OR REPLACE FUNCTION outbox_tenant_ids(p_statuses TEXT[])
RETURNS SETOF BIGINT
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
  SELECT DISTINCT o.tenant_id
    FROM notification_outbox o
   WHERE o.status = ANY(p_statuses);
$$;

-- EXECUTE 의 기본값이 PUBLIC 허용이므로 반드시 회수하고 런타임 롤에만 부여한다.
REVOKE EXECUTE ON FUNCTION outbox_tenant_ids(TEXT[]) FROM PUBLIC;
GRANT  EXECUTE ON FUNCTION outbox_tenant_ids(TEXT[]) TO app_tenant;

COMMENT ON FUNCTION outbox_tenant_ids(TEXT[]) IS
  '주어진 상태의 아웃박스 행을 가진 테넌트 id 목록(RLS 우회, 읽기 전용). 호출자는 이 목록으로 테넌트별 컨텍스트를 열고 실제 작업은 RLS 하에서 한다.';

-- Slack 인바운드 웹훅(permitAll)은 요청 시점에 테넌트를 알 수 없다. 페이로드의 team_id 만이
-- 유일한 단서이므로, 그것을 (workspace_id, tenant_id) 로 해석해 주는 최소 함수를 둔다.
--
-- V95 와 같은 최소 설계 원칙을 따른다:
--   * 워크스페이스 행을 반환하지 않는다 — id 두 개뿐이라 노출면이 정수 둘로 제한된다.
--     특히 **봇 토큰·서명 시크릿은 반환하지 않는다**(해석 뒤 RLS 하에서 다시 읽으면 된다).
--   * search_path 를 고정한다 — definer 함수에서 고정하지 않으면 권한 상승 경로가 된다.
--   * EXECUTE 를 PUBLIC 에서 회수하고 런타임 롤에만 부여한다.
--   * revoked_at IS NULL 필터를 함수 안에 둔다(V95 의 is_enabled = true 자리) —
--     해지된 워크스페이스는 테넌트조차 알려주지 않는다.
-- team_id 가 전역 유니크로 남아 있으므로([R1]) 이 해석은 항상 0행 아니면 1행이다.
CREATE OR REPLACE FUNCTION resolve_slack_workspace_tenant_by_team_id(p_team_id TEXT)
RETURNS TABLE (workspace_id BIGINT, tenant_id BIGINT)
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
  SELECT w.id, w.tenant_id
    FROM slack_workspace w
   WHERE w.team_id = p_team_id
     AND w.revoked_at IS NULL;
$$;

REVOKE EXECUTE ON FUNCTION resolve_slack_workspace_tenant_by_team_id(TEXT) FROM PUBLIC;
GRANT  EXECUTE ON FUNCTION resolve_slack_workspace_tenant_by_team_id(TEXT) TO app_tenant;

COMMENT ON FUNCTION resolve_slack_workspace_tenant_by_team_id(TEXT) IS
  'Slack team_id → (workspace_id, tenant_id) 해석(RLS 우회, 읽기 전용). 해지된 워크스페이스는 해석하지 않는다.';

COMMENT ON COLUMN oauth_state.tenant_id IS
  'OAuth 콜백에서 테넌트를 되찾기 위한 운반 값. 이 테이블은 의도적으로 RLS 미적용(전역)이다 — V106 [R7] 참조';
