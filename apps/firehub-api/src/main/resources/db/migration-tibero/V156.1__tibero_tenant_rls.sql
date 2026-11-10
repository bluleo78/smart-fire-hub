-- V156.1: Tibero 플랫폼 DB 테넌트 격리 — 컨텍스트 + DBMS_RLS (WD-492, WD-70 2단계).
-- 설계: docs/superpowers/specs/2026-11-10-wd492-tibero-tenant-rls-design.md
--
-- PG 짝이 없는 Tibero 전용 버전이다(설계 §6). PG 는 RLS 정책·DEFAULT·런타임 롤을 V83 등 여러 마이그레이션에 걸쳐 이미 가졌고,
-- Tibero 기준선(V155)은 이것들을 이 이슈로 미뤄 두었다. 기준선 시드 V155.1 과 같은 규칙으로 소수 버전을 써서 PG 번호와 겹치지 않게
-- 한다 — MigrationParityTest 의 TIBERO_ONLY 허용 목록에 이 버전이 있다.
--
-- 자리표시자(TiberoTenantMigration.configure 가 넣는다):
--   tenantContext — 컨텍스트 이름. 인스턴스 전역이라 소유자 스키마 해시로 만든다(FH_TENANT_xxxxxxxx).
--   ownerSchema   — 소유자 스키마(대문자). 정책 함수가 소유자 세션은 걸러내지 않으려고 SESSION_USER 와 비교한다.
-- 런타임 계정 권한(테이블 DML·FH_TENANT EXECUTE)은 이 파일이 아니라 Flyway afterMigrate 콜백(TiberoRuntimeGrantCallback)이 준다.
--
-- 다시 돌려도 안전하게 쓴다: Tibero DDL 은 암묵 커밋이라 중간에 실패하면 반쯤 적용된 상태가 남는다. 그래서 모든 문장을 CREATE OR REPLACE
-- (같은 이름 CREATE CONTEXT 를 다시 하면 -7102 — OR REPLACE 는 된다, 실측) 또는 사전 뷰로 존재를 본 뒤 만드는 블록으로 쓴다.
-- 고친 뒤 repair → migrate 로 다시 돌리면 이미 만든 객체는 덮어쓰거나 건너뛴다.

-- 1. 컨텍스트 — 이 소유자 스키마의 FH_TENANT 패키지만 값을 바꿀 수 있다. 앱(런타임 계정)이 DBMS_SESSION.SET_CONTEXT 를
--    직접 부르면 -17004 로 거부된다(실측). 패키지는 아래에서 만든다 — 패키지가 없어도 컨텍스트는 만들어진다(실측).
CREATE OR REPLACE CONTEXT ${tenantContext} USING FH_TENANT;

-- 2. 테넌트 패키지 — 런타임 계정이 테넌트를 정하고 지우는 유일한 길(EXECUTE 는 콜백이 준다).
--    BYPASS 를 켜는 공개 루틴은 두지 않는다 — 앱이 임의로 정책을 피할 길이 없게(설계 §3). 테넌트 간 함수가 본문 안의 비공개
--    bypass_on/bypass_off 를 쓴다.
--
--    테넌트 간 함수 — PG 의 SECURITY DEFINER 함수(app_tenant 에만 EXECUTE)를 대신한다. 정의자 권한 함수도 정책을 피하지 못하므로
--    (실측) 시작에서 BYPASS 를 켜고, 정상 끝과 예외(WHEN OTHERS — 지운 뒤 다시 던짐) 모두에서 끈다. PG 의 7개 중
--    tenant_default_security_level_id 는 열 DEFAULT 라 여기 없고 보안 등급 트리거(아래 5절)가 맡는다 — 그래서 6개다.
--    집합을 돌려주던 함수는 SYS_REFCURSOR 를 돌려준다: BYPASS 를 켠 채 OPEN 하고, 돌려주기 전에 끈다. 정책 술어는 OPEN 때 정해지므로
--    호출자가 나중에 읽어도 다른 테넌트 행이 나온다(TiberoTenantCrossFunctionsLiveTest 가 서버 쪽 FETCH 로 실측해 고정). 파이프라인
--    함수는 쓰지 않는다 — 호출자가 끝까지 읽지 않으면 끝부분의 BYPASS 해제가 돌지 않는다.
--    모든 함수는 PG 판과 두 벌이다 — 바꿀 때 둘 다(함수마다 짝 마이그레이션 번호를 본문 주석에 적었다).
CREATE OR REPLACE PACKAGE FH_TENANT AS
  -- 세션의 테넌트를 정한다. NULL 은 거부한다(-20001) — "테넌트 없음"은 clear_tenant 로만 만든다.
  PROCEDURE set_tenant(p_tenant_id NUMBER);
  -- 세션의 테넌트와 우회 표시를 모두 지운다. 컨텍스트는 세션 범위라 커넥션을 풀로 돌려주기 전에 부른다.
  PROCEDURE clear_tenant;

  -- 주어진 상태의 아웃박스 행을 가진 테넌트 id(DISTINCT, 열 tenant_id). p_statuses 는 쉼표 구분('PENDING,FAILED').
  FUNCTION outbox_tenant_ids(p_statuses VARCHAR2) RETURN SYS_REFCURSOR;
  -- 활성(PENDING·RUNNING) 스마트 작업 실행이 있는 테넌트 id(DISTINCT, 열 tenant_id).
  FUNCTION proactive_active_execution_tenant_ids RETURN SYS_REFCURSOR;
  -- API 트리거 토큰 해시 → 테넌트 id(꺼진 트리거 포함, 없으면 NULL).
  FUNCTION resolve_trigger_tenant_by_token_hash(p_token_hash VARCHAR2) RETURN NUMBER;
  -- 웹훅 id → 테넌트 id(꺼진 트리거 포함, 없으면 NULL).
  FUNCTION resolve_trigger_tenant_by_webhook_id(p_webhook_id VARCHAR2) RETURN NUMBER;
  -- Slack team_id → 테넌트 id(해지된 워크스페이스는 NULL).
  FUNCTION resolve_slack_workspace_tenant_by_team_id(p_team_id VARCHAR2) RETURN NUMBER;
  -- 새 테넌트의 기본값(보안 등급·시스템 역할·권한·양식·분류·OWNER 의 ADMIN)을 만든다. 다시 불러도 늘지 않는다.
  PROCEDURE provision_tenant_defaults(p_tenant_id NUMBER);
END FH_TENANT;
/

CREATE OR REPLACE PACKAGE BODY FH_TENANT AS
  -- 정책 우회 켜기/끄기 — 공개 명세에 없는 비공개 루틴. 테넌트 간 함수가 시작에서 켜고 끝·예외에서 끈다.
  PROCEDURE bypass_on IS
  BEGIN
    DBMS_SESSION.SET_CONTEXT('${tenantContext}', 'BYPASS', 'Y');
  END bypass_on;

  PROCEDURE bypass_off IS
  BEGIN
    DBMS_SESSION.CLEAR_CONTEXT('${tenantContext}', NULL, 'BYPASS');
  END bypass_off;

  PROCEDURE set_tenant(p_tenant_id NUMBER) IS
  BEGIN
    IF p_tenant_id IS NULL THEN
      RAISE_APPLICATION_ERROR(-20001, 'FH_TENANT.set_tenant: 테넌트 id 가 NULL 입니다');
    END IF;
    DBMS_SESSION.SET_CONTEXT('${tenantContext}', 'TENANT_ID', TO_CHAR(p_tenant_id));
  END set_tenant;

  PROCEDURE clear_tenant IS
  BEGIN
    DBMS_SESSION.CLEAR_CONTEXT('${tenantContext}', NULL, 'TENANT_ID');
    bypass_off;
  END clear_tenant;

  -- PG 판과 두 벌 — 바꿀 때 둘 다: V106 outbox_tenant_ids(p_statuses TEXT[]). 이후 마이그레이션은 이 함수를 다시 정의하지 않았다.
  -- 컬렉션 타입은 만들 권한이 없어(CREATE TYPE -17004) 상태 목록을 쉼표 구분 문자열로 받고 ',목록,' 안에 ',상태,' 가 있는지로 본다.
  -- 상태 값에는 쉼표·공백이 없다(chk_outbox_status). 공백은 지워 'PENDING, SENT' 도 받는다.
  FUNCTION outbox_tenant_ids(p_statuses VARCHAR2) RETURN SYS_REFCURSOR IS
    v_cur SYS_REFCURSOR;
  BEGIN
    bypass_on;
    OPEN v_cur FOR
      SELECT DISTINCT o.tenant_id
        FROM notification_outbox o
       WHERE INSTR(',' || REPLACE(p_statuses, ' ', '') || ',', ',' || o.status || ',') > 0;
    bypass_off;
    RETURN v_cur;
  EXCEPTION
    WHEN OTHERS THEN
      bypass_off;
      RAISE;
  END outbox_tenant_ids;

  -- PG 판과 두 벌 — 바꿀 때 둘 다: V149 proactive_active_execution_tenant_ids(). 정리기(컨텍스트 없는 스케줄 스레드)가 일감 있는
  -- 테넌트를 찾는 데 쓴다. 실행 표에서 유도하므로 정지된 워크스페이스의 고아 실행도 빠지지 않는다.
  FUNCTION proactive_active_execution_tenant_ids RETURN SYS_REFCURSOR IS
    v_cur SYS_REFCURSOR;
  BEGIN
    bypass_on;
    OPEN v_cur FOR
      SELECT DISTINCT e.tenant_id
        FROM proactive_job_execution e
       WHERE e.status IN ('PENDING', 'RUNNING');
    bypass_off;
    RETURN v_cur;
  EXCEPTION
    WHEN OTHERS THEN
      bypass_off;
      RAISE;
  END proactive_active_execution_tenant_ids;

  -- PG 판과 두 벌 — 바꿀 때 둘 다: V140 resolve_trigger_tenant_by_token_hash(꺼진 트리거도 해석 — 켜짐 판정은 호출자가 RLS 아래에서).
  -- PG 판은 (trigger_id, tenant_id) 를 돌려주지만 여기서는 테넌트 id 하나만 돌려준다 — 호출자는 테넌트를 세운 뒤 RLS 아래에서 트리거를
  -- 다시 읽는다. 조건식은 기준선의 유일 함수 색인 uq_trigger_api_token_hash 와 같은 모양이라 색인을 탄다(0행 아니면 1행).
  FUNCTION resolve_trigger_tenant_by_token_hash(p_token_hash VARCHAR2) RETURN NUMBER IS
    v_tenant NUMBER;
  BEGIN
    bypass_on;
    -- 0행이면 MAX 가 NULL 을 돌려준다(NO_DATA_FOUND 없음). 유일 색인이라 많아야 1행이다.
    SELECT MAX(t.tenant_id) INTO v_tenant
      FROM pipeline_trigger t
     WHERE (CASE WHEN t.trigger_type = 'API' THEN JSON_VALUE(t.config, '$.tokenHash') END) = p_token_hash;
    bypass_off;
    RETURN v_tenant;
  EXCEPTION
    WHEN OTHERS THEN
      bypass_off;
      RAISE;
  END resolve_trigger_tenant_by_token_hash;

  -- PG 판과 두 벌 — 바꿀 때 둘 다: V140 resolve_trigger_tenant_by_webhook_id(꺼진 트리거도 해석). 반환은 위와 같이 테넌트 id 하나.
  -- 조건식은 유일 함수 색인 uq_trigger_webhook_id 와 같은 모양이다.
  FUNCTION resolve_trigger_tenant_by_webhook_id(p_webhook_id VARCHAR2) RETURN NUMBER IS
    v_tenant NUMBER;
  BEGIN
    bypass_on;
    -- 0행이면 MAX 가 NULL 을 돌려준다(NO_DATA_FOUND 없음). 유일 색인이라 많아야 1행이다.
    SELECT MAX(t.tenant_id) INTO v_tenant
      FROM pipeline_trigger t
     WHERE (CASE WHEN t.trigger_type = 'WEBHOOK' THEN JSON_VALUE(t.config, '$.webhookId') END) = p_webhook_id;
    bypass_off;
    RETURN v_tenant;
  EXCEPTION
    WHEN OTHERS THEN
      bypass_off;
      RAISE;
  END resolve_trigger_tenant_by_webhook_id;

  -- PG 판과 두 벌 — 바꿀 때 둘 다: V106 resolve_slack_workspace_tenant_by_team_id. 해지된 워크스페이스는 테넌트조차 알려주지 않는다.
  -- PG 판은 (workspace_id, tenant_id) 지만 여기서는 테넌트 id 하나 — 봇 토큰·서명 시크릿은 물론 워크스페이스 id 도 RLS 아래에서
  -- 다시 읽는다. team_id 는 전역 유일(slack_workspace_team_id_key)이라 0행 아니면 1행이다.
  FUNCTION resolve_slack_workspace_tenant_by_team_id(p_team_id VARCHAR2) RETURN NUMBER IS
    v_tenant NUMBER;
  BEGIN
    bypass_on;
    -- 0행이면 MAX 가 NULL 을 돌려준다(NO_DATA_FOUND 없음). 유일 색인이라 많아야 1행이다.
    SELECT MAX(w.tenant_id) INTO v_tenant
      FROM slack_workspace w
     WHERE w.team_id = p_team_id
       AND w.revoked_at IS NULL;
    bypass_off;
    RETURN v_tenant;
  EXCEPTION
    WHEN OTHERS THEN
      bypass_off;
      RAISE;
  END resolve_slack_workspace_tenant_by_team_id;

  -- PG 판과 두 벌 — 바꿀 때 둘 다: provision_tenant_defaults 누적 최종판 V133(V98 → V100 → V113 → V121 → V133).
  -- 원본 테넌트 1 의 시스템 역할·그 권한·공용 양식(user_id NULL)을 복사하고, 기본 4등급·기본 분류 3개를 만들고, OWNER 멤버에게 ADMIN 을
  -- 준다. PG 의 ON CONFLICT DO NOTHING·WHERE NOT EXISTS 는 모두 NOT EXISTS 로 옮겼다 — 다시 불러도 늘지 않는다.
  -- 등급을 먼저 넣고 역할에 자격을 명시한다(ADMIN = 최상위, 그 외 = 기본). 그래서 이 경로는 role 보안 등급 트리거에 기대지 않는다.
  -- 원본 테넌트(1)면 아무것도 하지 않는다 — BYPASS 를 켜기 전에 돌아간다.
  -- 동시성: user_role 은 NOT EXISTS 로 거르므로 같은 테넌트를 두 세션이 동시에 프로비저닝하면 고유 키 위반이 날 수 있다(PG 의
  -- ON CONFLICT 는 건너뛴다). 프로비저닝은 테넌트마다 한 번만 돌아 실제로는 겹치지 않는다.
  PROCEDURE provision_tenant_defaults(p_tenant_id NUMBER) IS
    c_source_tenant CONSTANT NUMBER := 1;
    v_default_level NUMBER;
    v_top_level NUMBER;
  BEGIN
    IF p_tenant_id = c_source_tenant THEN
      RETURN;
    END IF;
    bypass_on;

    -- (V133) 기본 4등급. 이름·순위 단위로 건너뛴다 — 기본 등급 하나만 먼저 심은 테넌트에도 나머지가 채워진다.
    INSERT INTO security_level
      (tenant_id, rank, name, is_default, allowlist_required, admin_bypass,
       export_policy, ai_policy, share_policy, audit_access)
    SELECT p_tenant_id, s.rank, s.name, s.is_default, s.allowlist_required, 0,
           s.export_policy, s.ai_policy, s.share_policy, s.audit_access
      FROM (SELECT 1 AS rank, '공개' AS name, 0 AS is_default, 0 AS allowlist_required,
                   'ALLOW' AS export_policy, 'ALL' AS ai_policy, 'ALLOW' AS share_policy, 0 AS audit_access FROM DUAL
            UNION ALL
            SELECT 2, '내부', 1, 0, 'ALLOW', 'ALL', 'ALLOW', 0 FROM DUAL
            UNION ALL
            SELECT 3, '민감', 0, 0, 'PERMISSION', 'SELF_HOSTED_ONLY', 'ALLOW', 1 FROM DUAL
            UNION ALL
            SELECT 4, '기밀', 0, 1, 'DENY', 'SELF_HOSTED_ONLY', 'DENY', 1 FROM DUAL) s
     WHERE NOT EXISTS (
       SELECT 1 FROM security_level x
        WHERE x.tenant_id = p_tenant_id AND (x.name = s.name OR x.rank = s.rank));

    -- PG 의 SELECT INTO 는 행이 없으면 NULL 을 둔다 — 같은 의미로 MAX(테넌트당 기본 등급은 하나)와 NO_DATA_FOUND → NULL 을 쓴다.
    SELECT MAX(id) INTO v_default_level
      FROM security_level
     WHERE tenant_id = p_tenant_id AND is_default = 1;
    BEGIN
      SELECT id INTO v_top_level
        FROM (SELECT id FROM security_level WHERE tenant_id = p_tenant_id ORDER BY rank DESC)
       WHERE ROWNUM = 1;
    EXCEPTION
      WHEN NO_DATA_FOUND THEN
        v_top_level := NULL;
    END;

    -- 시스템 역할 복제(V98~) + (V133) 자격 명시.
    INSERT INTO role (tenant_id, name, description, is_system, max_security_level_id)
    SELECT p_tenant_id, r.name, r.description, r.is_system,
           CASE WHEN r.name = 'ADMIN' THEN v_top_level ELSE v_default_level END
      FROM role r
     WHERE r.tenant_id = c_source_tenant
       AND r.is_system = 1
       AND NOT EXISTS (
         SELECT 1 FROM role x WHERE x.tenant_id = p_tenant_id AND x.name = r.name);

    INSERT INTO role_permission (tenant_id, role_id, permission_id)
    SELECT p_tenant_id, tgt.id, srp.permission_id
      FROM role_permission srp
      JOIN role src ON src.id = srp.role_id AND src.tenant_id = c_source_tenant AND src.is_system = 1
      JOIN role tgt ON tgt.tenant_id = p_tenant_id AND tgt.name = src.name
     WHERE NOT EXISTS (
       SELECT 1 FROM role_permission x
        WHERE x.role_id = tgt.id AND x.permission_id = srp.permission_id);

    -- (V100) 공용 양식 복제 — style 포함.
    INSERT INTO report_template (tenant_id, user_id, name, description, sections, style, created_at, updated_at)
    SELECT p_tenant_id, NULL, t.name, t.description, t.sections, t.style, LOCALTIMESTAMP, LOCALTIMESTAMP
      FROM report_template t
     WHERE t.tenant_id = c_source_tenant
       AND t.user_id IS NULL
       AND NOT EXISTS (
         SELECT 1 FROM report_template x
          WHERE x.tenant_id = p_tenant_id AND x.user_id IS NULL AND x.name = t.name);

    -- (V113) 기본 분류 3개 — 이름만(PG 와 같이 설명은 복사하지 않는다).
    INSERT INTO dataset_category (tenant_id, name, created_at, updated_at)
    SELECT p_tenant_id, c.name, LOCALTIMESTAMP, LOCALTIMESTAMP
      FROM (SELECT '행정' AS name FROM DUAL
            UNION ALL SELECT '운영' FROM DUAL
            UNION ALL SELECT '통계' FROM DUAL) c
     WHERE NOT EXISTS (
       SELECT 1 FROM dataset_category x
        WHERE x.tenant_id = p_tenant_id AND x.name = c.name);

    -- (V121) OWNER 멤버에게 이 테넌트의 ADMIN 역할. PG 의 ON CONFLICT (user_id, role_id) DO NOTHING 을 NOT EXISTS 로.
    INSERT INTO user_role (user_id, role_id, tenant_id)
    SELECT m.user_id, r.id, p_tenant_id
      FROM membership m
      JOIN role r ON r.tenant_id = p_tenant_id AND r.name = 'ADMIN'
     WHERE m.tenant_id = p_tenant_id
       AND m.role = 'OWNER'
       AND NOT EXISTS (
         SELECT 1 FROM user_role x WHERE x.user_id = m.user_id AND x.role_id = r.id);

    bypass_off;
  EXCEPTION
    WHEN OTHERS THEN
      bypass_off;
      RAISE;
  END provision_tenant_defaults;
END FH_TENANT;
/

-- 3. 정책 함수(설계 §4) — DBMS_RLS 가 문장마다 불러 WHERE 술어를 받는다.
--    소유자 세션(Flyway·설치·백필)이거나 테넌트 간 함수가 BYPASS 를 켠 동안은 NULL(걸러내지 않음). Tibero 에는 EXEMPT ACCESS
--    POLICY 가 없고(-7001) 정의자 권한 함수도 정책을 피하지 못하므로(실측) 이 두 분기로 PG 의 소유자 우회·SECURITY DEFINER 를 대신한다.
--    그 밖에는 컨텍스트 테넌트의 행만. 테넌트가 없으면 SYS_CONTEXT 가 NULL 이라 0행이다(fail-closed).
CREATE OR REPLACE FUNCTION FH_TENANT_POLICY(p_schema VARCHAR2, p_object VARCHAR2) RETURN VARCHAR2 IS
BEGIN
  IF SYS_CONTEXT('USERENV', 'SESSION_USER') = '${ownerSchema}'
     OR SYS_CONTEXT('${tenantContext}', 'BYPASS') = 'Y' THEN
    RETURN NULL;
  END IF;
  RETURN 'tenant_id = SYS_CONTEXT(''${tenantContext}'',''TENANT_ID'')';
END FH_TENANT_POLICY;
/

-- audit_log 전용 — PG 의 IS NOT DISTINCT FROM 의미: 테넌트가 없으면 tenant_id 가 NULL 인 행(로그인 실패 등 테넌트 밖 감사)만 보고 쓴다.
CREATE OR REPLACE FUNCTION FH_TENANT_POLICY_NULLABLE(p_schema VARCHAR2, p_object VARCHAR2) RETURN VARCHAR2 IS
BEGIN
  IF SYS_CONTEXT('USERENV', 'SESSION_USER') = '${ownerSchema}'
     OR SYS_CONTEXT('${tenantContext}', 'BYPASS') = 'Y' THEN
    RETURN NULL;
  END IF;
  IF SYS_CONTEXT('${tenantContext}', 'TENANT_ID') IS NULL THEN
    RETURN 'tenant_id IS NULL';
  END IF;
  RETURN 'tenant_id = SYS_CONTEXT(''${tenantContext}'',''TENANT_ID'')';
END FH_TENANT_POLICY_NULLABLE;
/

-- 4. tenant_id DEFAULT(설계 §5) — PG 의 DEFAULT NULLIF(current_setting('app.tenant_id', true), '')::bigint 에 대응한다.
--    대상은 PG 에서 이 DEFAULT 를 가진 tenant_id 62곳 중 Tibero 기준선에 없는 벡터 테이블 4개(WD-495)를 뺀 58곳이다(정책 57개 테이블
--    + 정책 없이 DEFAULT 만 있는 oauth_state). NOT NULL 은 그대로 둔다 — 테넌트 없이 tenant_id 를 빼고 넣으면 NOT NULL 위반으로
--    실패한다(fail-closed). 같은 DEFAULT 로 다시 MODIFY 해도 오류가 없어 다시 돌려도 안전하다. 목록은 알파벳 순이고, 컬렉션 타입은
--    만들 권한이 없어(CREATE TYPE -17004) 정책 블록처럼 지역 프로시저를 줄마다 부른다.
--    TiberoTenantPolicyCatalogLiveTest 가 PG(jOOQ 생성 코드)의 DEFAULT 목록과 이 목록이 같은지 고정한다.
DECLARE
  -- 테이블 하나의 tenant_id DEFAULT 를 컨텍스트 테넌트로 바꾼다. 이름은 아래 목록의 고정 문자열뿐이다(외부 입력 없음).
  PROCEDURE set_default(p_table VARCHAR2) IS
  BEGIN
    EXECUTE IMMEDIATE 'ALTER TABLE ' || p_table
      || ' MODIFY (tenant_id DEFAULT SYS_CONTEXT(''${tenantContext}'',''TENANT_ID''))';
  END set_default;
BEGIN
  set_default('ai_inference_cache');
  set_default('ai_session');
  set_default('analytics_query_run');
  set_default('anomaly_event');
  set_default('api_connection');
  set_default('async_job');
  set_default('audit_log');
  set_default('chart');
  set_default('dashboard');
  set_default('dashboard_widget');
  set_default('dataset');
  set_default('dataset_access_grant');
  set_default('dataset_category');
  set_default('dataset_column');
  set_default('dataset_embedding');
  set_default('dataset_favorite');
  set_default('dataset_graph_ingest');
  set_default('dataset_mapping');
  set_default('dataset_ontology');
  set_default('dataset_search_index');
  set_default('dataset_tag');
  set_default('document_chunk');
  set_default('document_file');
  set_default('embedding_reembed_state');
  set_default('file_dataset_config');
  set_default('graph_ontology_source');
  set_default('graph_review_item');
  set_default('metric_snapshot');
  set_default('notification_outbox');
  set_default('oauth_state');
  set_default('ontology');
  set_default('ontology_entity_property');
  set_default('ontology_entity_type');
  set_default('ontology_relation');
  set_default('pipeline');
  set_default('pipeline_execution');
  set_default('pipeline_step');
  set_default('pipeline_step_dependency');
  set_default('pipeline_step_execution');
  set_default('pipeline_step_input');
  set_default('pipeline_trigger');
  set_default('proactive_job');
  set_default('proactive_job_execution');
  set_default('proactive_message');
  set_default('query_history');
  set_default('report_template');
  set_default('role');
  set_default('role_permission');
  set_default('saved_query');
  set_default('security_level');
  set_default('slack_workspace');
  set_default('tenant_canary');
  set_default('tenant_settings');
  set_default('trigger_event');
  set_default('uploaded_files');
  set_default('user_channel_binding');
  set_default('user_channel_preference');
  set_default('user_role');
END;
/

-- 5. 보안 등급 기본값 트리거(설계 §3 마지막 항목) — PG 는 dataset.security_level_id·role.max_security_level_id 의 열 DEFAULT 가
--    tenant_default_security_level_id()(V133: 그 테넌트의 is_default 등급 id)를 부른다. Tibero 열 DEFAULT 는 사용자 함수를 부르지
--    못하므로 BEFORE INSERT 행 트리거로 같은 의미를 만든다.
--    - 값이 NULL 일 때만 채운다. NULL 이 아닌 명시 값은 그대로 둔다. 다만 명시적 NULL 도 채운다는 점은 PG 와 다르다(PG 는 열
--      DEFAULT 가 명시적 NULL 에 쓰이지 않아 NOT NULL 위반이 난다). 앱은 이 열을 INSERT 에서 빼므로 이 차이는 해가 없다.
--    - 조회 키는 :NEW.tenant_id 다. 열 DEFAULT 가 트리거보다 먼저 채워지므로(실측) tenant_id 를 뺀 INSERT 도 여기서는 이미 컨텍스트
--      테넌트다. 테넌트가 정해지지 않았거나 기본 등급이 없으면 NULL 로 두어 NOT NULL 위반으로 실패하게 한다(PG 함수가 NULL 을 돌려주는
--      것과 같다 — 트리거 오류로 바꾸지 않는다).
--    - 런타임 세션에서는 이 조회도 security_level 정책에 걸려 컨텍스트 테넌트 행만 본다. 정상 경로(컨텍스트 테넌트 = :NEW.tenant_id)
--      에서는 결과가 같고, 다른 테넌트로 쓰는 문장은 어차피 update_check 로 거부된다. 테넌트 간 작업(provision_tenant_defaults)은
--      BYPASS 를 켠 채 돌아 걸러지지 않는다.
--    - 테넌트마다 기본 등급은 하나다(uq_security_level_default) — MAX 는 그 하나다.
--    - 조회는 소유자 함수 FH_DEFAULT_SECURITY_LEVEL 하나에 둔다(두 트리거가 같은 술어). 런타임 계정에 EXECUTE 를 주지 않는다 — 트리거는
--      소유자 권한으로 돈다. FH_TENANT 명세에도 넣지 않는다(공개 루틴 집합을 그대로 둔다).
CREATE OR REPLACE FUNCTION FH_DEFAULT_SECURITY_LEVEL(p_tenant_id NUMBER) RETURN NUMBER IS
  v_level NUMBER;
BEGIN
  -- 기본 등급이 없으면 MAX 가 NULL — 호출 트리거가 NULL 로 두어 NOT NULL 위반으로 실패하게 한다.
  SELECT MAX(id) INTO v_level
    FROM security_level
   WHERE tenant_id = p_tenant_id
     AND is_default = 1;
  RETURN v_level;
END FH_DEFAULT_SECURITY_LEVEL;
/

CREATE OR REPLACE TRIGGER trg_dataset_default_security_level
BEFORE INSERT ON dataset
FOR EACH ROW
BEGIN
  IF :NEW.security_level_id IS NULL THEN
    :NEW.security_level_id := FH_DEFAULT_SECURITY_LEVEL(:NEW.tenant_id);
  END IF;
END;
/

CREATE OR REPLACE TRIGGER trg_role_default_security_level
BEFORE INSERT ON role
FOR EACH ROW
BEGIN
  IF :NEW.max_security_level_id IS NULL THEN
    :NEW.max_security_level_id := FH_DEFAULT_SECURITY_LEVEL(:NEW.tenant_id);
  END IF;
END;
/

-- 6. 테넌트 격리 정책 57개(설계 §4) — PG 정책 61개에서 벡터 테이블 4개(dataset_embedding_vec_1024·_1536, document_chunk_vec_1024·
--    _1536 — Tibero 기준선에 없음, WD-495)를 뺐다. 이름은 PG 정책 이름 그대로(대문자)다 — 41자까지 되는 것을 실측했으므로 줄이지 않는다.
--    PG 이름 규칙(<table>_tenant_isolation)의 예외는 tenant_canary_isolation 하나다. 그래서 정책 이름을 테이블 이름에서 만들지 않고
--    줄마다 적는다.
--    - audit_log 만 FH_TENANT_POLICY_NULLABLE(테넌트가 없으면 tenant_id IS NULL 행) — PG 의 IS NOT DISTINCT FROM 정책과 같다.
--    - statement_types 네 문장 + update_check — INSERT·UPDATE 뒤의 행도 술어를 만족해야 한다(PG WITH CHECK). 위반은 -10067(실측).
--    - static_policy => FALSE(동적) — 술어가 SESSION_USER·BYPASS 에 따라 달라지므로 문장마다 함수를 다시 불러야 한다.
--    정책 없이 두는 tenant_id 테이블 4개(PG 와 같음): membership, oauth_state, tenant_data_store, tenant_data_store_account — 로그인·
--    테넌트 해석이 테넌트를 넘어 읽는다. TiberoTenantPolicyCatalogLiveTest 가 이 목록과 정책 목록이 낡지 않게 고정한다.
--    같은 정책을 다시 추가하면 -14142 이므로 USER_POLICIES 로 있는지 본 뒤 추가한다(다시 돌려도 안전).
DECLARE
  -- 정책이 없을 때만 추가한다. 테이블·정책 이름은 대문자(사전 뷰 표기)로 받는다.
  PROCEDURE add_policy(p_table VARCHAR2, p_policy VARCHAR2, p_function VARCHAR2) IS
    v_count NUMBER;
  BEGIN
    SELECT COUNT(*) INTO v_count
      FROM USER_POLICIES
     WHERE OBJECT_NAME = p_table
       AND POLICY_NAME = p_policy;
    IF v_count = 0 THEN
      DBMS_RLS.ADD_POLICY(
        object_schema   => '${ownerSchema}',
        object_name     => p_table,
        policy_name     => p_policy,
        function_schema => '${ownerSchema}',
        policy_function => p_function,
        statement_types => 'SELECT,INSERT,UPDATE,DELETE',
        update_check    => TRUE,
        static_policy   => FALSE);
    END IF;
  END add_policy;
BEGIN
  add_policy('AI_INFERENCE_CACHE',          'AI_INFERENCE_CACHE_TENANT_ISOLATION',         'FH_TENANT_POLICY');
  add_policy('AI_SESSION',                  'AI_SESSION_TENANT_ISOLATION',                 'FH_TENANT_POLICY');
  add_policy('ANALYTICS_QUERY_RUN',         'ANALYTICS_QUERY_RUN_TENANT_ISOLATION',        'FH_TENANT_POLICY');
  add_policy('ANOMALY_EVENT',               'ANOMALY_EVENT_TENANT_ISOLATION',              'FH_TENANT_POLICY');
  add_policy('API_CONNECTION',              'API_CONNECTION_TENANT_ISOLATION',             'FH_TENANT_POLICY');
  add_policy('ASYNC_JOB',                   'ASYNC_JOB_TENANT_ISOLATION',                  'FH_TENANT_POLICY');
  add_policy('AUDIT_LOG',                   'AUDIT_LOG_TENANT_ISOLATION',                  'FH_TENANT_POLICY_NULLABLE');
  add_policy('CHART',                       'CHART_TENANT_ISOLATION',                      'FH_TENANT_POLICY');
  add_policy('DASHBOARD',                   'DASHBOARD_TENANT_ISOLATION',                  'FH_TENANT_POLICY');
  add_policy('DASHBOARD_WIDGET',            'DASHBOARD_WIDGET_TENANT_ISOLATION',           'FH_TENANT_POLICY');
  add_policy('DATASET_ACCESS_GRANT',        'DATASET_ACCESS_GRANT_TENANT_ISOLATION',       'FH_TENANT_POLICY');
  add_policy('DATASET_CATEGORY',            'DATASET_CATEGORY_TENANT_ISOLATION',           'FH_TENANT_POLICY');
  add_policy('DATASET_COLUMN',              'DATASET_COLUMN_TENANT_ISOLATION',             'FH_TENANT_POLICY');
  add_policy('DATASET_EMBEDDING',           'DATASET_EMBEDDING_TENANT_ISOLATION',          'FH_TENANT_POLICY');
  add_policy('DATASET_FAVORITE',            'DATASET_FAVORITE_TENANT_ISOLATION',           'FH_TENANT_POLICY');
  add_policy('DATASET_GRAPH_INGEST',        'DATASET_GRAPH_INGEST_TENANT_ISOLATION',       'FH_TENANT_POLICY');
  add_policy('DATASET_MAPPING',             'DATASET_MAPPING_TENANT_ISOLATION',            'FH_TENANT_POLICY');
  add_policy('DATASET_ONTOLOGY',            'DATASET_ONTOLOGY_TENANT_ISOLATION',           'FH_TENANT_POLICY');
  add_policy('DATASET_SEARCH_INDEX',        'DATASET_SEARCH_INDEX_TENANT_ISOLATION',       'FH_TENANT_POLICY');
  add_policy('DATASET_TAG',                 'DATASET_TAG_TENANT_ISOLATION',                'FH_TENANT_POLICY');
  add_policy('DATASET',                     'DATASET_TENANT_ISOLATION',                    'FH_TENANT_POLICY');
  add_policy('DOCUMENT_CHUNK',              'DOCUMENT_CHUNK_TENANT_ISOLATION',             'FH_TENANT_POLICY');
  add_policy('DOCUMENT_FILE',               'DOCUMENT_FILE_TENANT_ISOLATION',              'FH_TENANT_POLICY');
  add_policy('EMBEDDING_REEMBED_STATE',     'EMBEDDING_REEMBED_STATE_TENANT_ISOLATION',    'FH_TENANT_POLICY');
  add_policy('FILE_DATASET_CONFIG',         'FILE_DATASET_CONFIG_TENANT_ISOLATION',        'FH_TENANT_POLICY');
  add_policy('GRAPH_ONTOLOGY_SOURCE',       'GRAPH_ONTOLOGY_SOURCE_TENANT_ISOLATION',      'FH_TENANT_POLICY');
  add_policy('GRAPH_REVIEW_ITEM',           'GRAPH_REVIEW_ITEM_TENANT_ISOLATION',          'FH_TENANT_POLICY');
  add_policy('METRIC_SNAPSHOT',             'METRIC_SNAPSHOT_TENANT_ISOLATION',            'FH_TENANT_POLICY');
  add_policy('NOTIFICATION_OUTBOX',         'NOTIFICATION_OUTBOX_TENANT_ISOLATION',        'FH_TENANT_POLICY');
  add_policy('ONTOLOGY_ENTITY_PROPERTY',    'ONTOLOGY_ENTITY_PROPERTY_TENANT_ISOLATION',   'FH_TENANT_POLICY');
  add_policy('ONTOLOGY_ENTITY_TYPE',        'ONTOLOGY_ENTITY_TYPE_TENANT_ISOLATION',       'FH_TENANT_POLICY');
  add_policy('ONTOLOGY_RELATION',           'ONTOLOGY_RELATION_TENANT_ISOLATION',          'FH_TENANT_POLICY');
  add_policy('ONTOLOGY',                    'ONTOLOGY_TENANT_ISOLATION',                   'FH_TENANT_POLICY');
  add_policy('PIPELINE_EXECUTION',          'PIPELINE_EXECUTION_TENANT_ISOLATION',         'FH_TENANT_POLICY');
  add_policy('PIPELINE_STEP_DEPENDENCY',    'PIPELINE_STEP_DEPENDENCY_TENANT_ISOLATION',   'FH_TENANT_POLICY');
  add_policy('PIPELINE_STEP_EXECUTION',     'PIPELINE_STEP_EXECUTION_TENANT_ISOLATION',    'FH_TENANT_POLICY');
  add_policy('PIPELINE_STEP_INPUT',         'PIPELINE_STEP_INPUT_TENANT_ISOLATION',        'FH_TENANT_POLICY');
  add_policy('PIPELINE_STEP',               'PIPELINE_STEP_TENANT_ISOLATION',              'FH_TENANT_POLICY');
  add_policy('PIPELINE',                    'PIPELINE_TENANT_ISOLATION',                   'FH_TENANT_POLICY');
  add_policy('PIPELINE_TRIGGER',            'PIPELINE_TRIGGER_TENANT_ISOLATION',           'FH_TENANT_POLICY');
  add_policy('PROACTIVE_JOB_EXECUTION',     'PROACTIVE_JOB_EXECUTION_TENANT_ISOLATION',    'FH_TENANT_POLICY');
  add_policy('PROACTIVE_JOB',               'PROACTIVE_JOB_TENANT_ISOLATION',              'FH_TENANT_POLICY');
  add_policy('PROACTIVE_MESSAGE',           'PROACTIVE_MESSAGE_TENANT_ISOLATION',          'FH_TENANT_POLICY');
  add_policy('QUERY_HISTORY',               'QUERY_HISTORY_TENANT_ISOLATION',              'FH_TENANT_POLICY');
  add_policy('REPORT_TEMPLATE',             'REPORT_TEMPLATE_TENANT_ISOLATION',            'FH_TENANT_POLICY');
  add_policy('ROLE_PERMISSION',             'ROLE_PERMISSION_TENANT_ISOLATION',            'FH_TENANT_POLICY');
  add_policy('ROLE',                        'ROLE_TENANT_ISOLATION',                       'FH_TENANT_POLICY');
  add_policy('SAVED_QUERY',                 'SAVED_QUERY_TENANT_ISOLATION',                'FH_TENANT_POLICY');
  add_policy('SECURITY_LEVEL',              'SECURITY_LEVEL_TENANT_ISOLATION',             'FH_TENANT_POLICY');
  add_policy('SLACK_WORKSPACE',             'SLACK_WORKSPACE_TENANT_ISOLATION',            'FH_TENANT_POLICY');
  add_policy('TENANT_CANARY',               'TENANT_CANARY_ISOLATION',                     'FH_TENANT_POLICY');
  add_policy('TENANT_SETTINGS',             'TENANT_SETTINGS_TENANT_ISOLATION',            'FH_TENANT_POLICY');
  add_policy('TRIGGER_EVENT',               'TRIGGER_EVENT_TENANT_ISOLATION',              'FH_TENANT_POLICY');
  add_policy('UPLOADED_FILES',              'UPLOADED_FILES_TENANT_ISOLATION',             'FH_TENANT_POLICY');
  add_policy('USER_CHANNEL_BINDING',        'USER_CHANNEL_BINDING_TENANT_ISOLATION',       'FH_TENANT_POLICY');
  add_policy('USER_CHANNEL_PREFERENCE',     'USER_CHANNEL_PREFERENCE_TENANT_ISOLATION',    'FH_TENANT_POLICY');
  add_policy('USER_ROLE',                   'USER_ROLE_TENANT_ISOLATION',                  'FH_TENANT_POLICY');
END;
/
