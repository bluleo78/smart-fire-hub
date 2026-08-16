-- V107: P2-f 격리 스위치. V106 이 채널 4테이블 + oauth_state 에 tenant_id 를 심었고, Task 2~5 가
-- 그 테이블을 읽고 쓰는 리포지토리 5종과 배경 경로 4종(outbox 워커·좀비 스위퍼·보존 잡·메트릭
-- 게이지), 그리고 permitAll 경로 3종(Slack/Kakao OAuth 콜백·Slack 인바운드 웹훅)에 테넌트
-- 컨텍스트와 트랜잭션을 붙였다. 이 파일이 실제로 정책을 켠다.
--
-- 왜 컬럼과 정책을 나눴는가: 중간 커밋에서 정책이 먼저 걸리면 컨텍스트 없는 배경 경로가 전부
-- 0행이 되어 알림 배달이 통째로 멈춘다(에러 없이 조용히). 배선이 끝난 뒤에 켠다.
-- V101/V102, V103/V104 와 같은 순서다.
--
-- 전부 표준 형태 (a) 다 — V106 이 4테이블 모두 tenant_id NOT NULL 을 걸었으므로 NULL 테넌트 행이
-- 존재할 수 없다. audit_log 가 쓰는 형태 (b)(NOT (tenant_id IS DISTINCT FROM ...))는 GUC 가 비면
-- NULL-vs-NULL 이 참이 되어 fail-open 이므로 여기서는 쓰지 않는다.
--
-- NULLIF(..., '') 은 생략할 수 없다 — 풀링 커넥션에서 GUC 가 빈 문자열로 남을 수 있고
-- ''::bigint 는 에러를 낸다. current_setting 의 두 번째 인자 true 는 미설정 시 NULL 을 주므로
-- 컨텍스트가 없으면 조건이 NULL → 전 행 차단(fail-closed)이 된다.
--
-- 이 정책은 테이블 소유자(app)에게 적용되지 않는다. 강제력의 근원은 런타임이 비특권 롤
-- app_tenant(NOBYPASSRLS, V83)로 접속한다는 사실이다. DataSourceRoleTest 가 그 회귀를 막는다.
--
-- FORCE ROW LEVEL SECURITY 는 쓰지 않는다. 켜면 소유자에게까지 정책이 적용돼 SECURITY DEFINER
-- 우회가 전부 죽는데, 이 밴드는 그 우회에 특히 의존한다 — V106 이 해석기를 둘이나 새로 만들었다:
--   * outbox_tenant_ids(TEXT[])                      — 배경 경로 4종이 "일감 있는 테넌트" 를 얻는 유일한 통로
--   * resolve_slack_workspace_tenant_by_team_id(TEXT) — permitAll 웹훅이 team_id 로 테넌트를 되찾는 통로
-- FORCE 를 켜는 순간 둘 다 0행을 받아 알림 배달·좀비 회수·보존 삭제·게이지·인바운드 웹훅이
-- 한꺼번에, 에러 없이 죽는다. V98 의 provision_tenant_defaults 와 V95 의 트리거 해석기도 같다.
--
-- V104:25-27 의 "범위 주의" 문단이 지목한 것이 바로 이 파일이다 — 그 문단은 여기서 회수된다.
-- 채널 도메인 4테이블이 이 마이그레이션으로 격리되고, ProactiveJobAsyncRunner 가 outbox 에 쓰는
-- 지점은 Task 3 이 테넌트를 싣도록 배선했다.
--
-- ── oauth_state 를 제외하는 이유 (계획 R7) ─────────────────────────────────────
-- oauth_state 에도 V106 이 tenant_id NOT NULL 을 심었지만 정책은 걸지 않는다. 그 컬럼은 격리
-- 수단이 아니라 **OAuth 콜백에서 테넌트를 되찾는 운반 수단**이다. 콜백(/oauth/slack/callback,
-- /oauth/kakao/callback)은 permitAll 이라 요청 시점에 컨텍스트가 없고, 여기에 RLS 를 켜면
-- consume(state) 이 0행을 보고 테넌트를 영영 되찾지 못해 모든 채널 연동이 조용히 실패한다.
-- 노출면은 32바이트 CSPRNG state 를 이미 아는 자에게 (user_id, channel_type) 뿐이고, 그 값은
-- 콜백 당사자가 방금 발급받은 것이다. TenantSchemaConformanceTest 의 RLS_DISABLED_ALLOWLIST 에
-- **영구** 등재돼 있다(IN_FLIGHT_RLS_PENDING 이 아니다) — 빼지 말 것.
--
-- ── claimDue / countPending 의 앱 술어와 이 정책의 관계 ─────────────────────────
-- NotificationOutboxRepositoryImpl.claimDue 와 countPending 은 `tenant_id = ?` 술어를 직접 건다.
-- 정책이 켜진 지금 그 술어는 이 파일이 붙이는 술어와 같아 **중복**이다. 그래도 남긴다:
--   * 그 술어는 정책 이전 구간(V106~V107 사이 커밋)의 유일한 방어였다. 워커가 테넌트 A 컨텍스트에서
--     B 의 행을 클레임해 A 로 배달하는 창을 닫으려고 넣은 것이고, 리뷰어가 그 창을 실제로 재현했다.
--   * 지우면 정책 하나가 실수로 빠졌을 때(정책 파일 편집·DROP POLICY·미래의 롤 변경) 방어가 0 이
--     된다. 배달은 되돌릴 수 없는 부작용이라 다층 방어의 값이 특히 크다.
--   * 비용이 없다 — idx_outbox_pending_due 계획을 바꾸지 않는다.
-- **"이제 중복이니 지우자" 는 이 문단을 읽지 않은 판단이다.** 대신 알아 둘 것: 이 술어 때문에
-- claimDue/countPending 은 **정책 부재를 감지하지 못한다**. 정책 회귀를 잡는 테스트는 술어 없는
-- 경로(findByCorrelation / findStuckPending / reclaimZombies / 원시 SELECT)로 써야 한다
-- (ChannelDomainRlsTest 가 그렇게 한다).

ALTER TABLE slack_workspace ENABLE ROW LEVEL SECURITY;
CREATE POLICY slack_workspace_tenant_isolation ON slack_workspace
  USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint)
  WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint);

ALTER TABLE notification_outbox ENABLE ROW LEVEL SECURITY;
CREATE POLICY notification_outbox_tenant_isolation ON notification_outbox
  USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint)
  WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint);

ALTER TABLE user_channel_binding ENABLE ROW LEVEL SECURITY;
CREATE POLICY user_channel_binding_tenant_isolation ON user_channel_binding
  USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint)
  WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint);

ALTER TABLE user_channel_preference ENABLE ROW LEVEL SECURITY;
CREATE POLICY user_channel_preference_tenant_isolation ON user_channel_preference
  USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint)
  WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint);
