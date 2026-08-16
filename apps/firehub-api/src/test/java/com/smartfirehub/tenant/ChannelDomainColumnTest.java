package com.smartfirehub.tenant;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.support.IntegrationTestBase;
import java.util.List;
import org.jooq.DSLContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * V106 이 채널 도메인 5개 테이블에 NOT NULL + GUC DEFAULT 로 tenant_id 를 심고, 유니크를 제약(constraint)으로
 * 접고, SECURITY DEFINER 해석기 2종을 최소 권한으로 만들었는지 고정한다.
 *
 * <p>정책(RLS)은 V107 소관이라 여기서 단언하지 않는다 — 단 {@code oauth_state} 만은 "정책이 <b>영원히</b>
 * 없다"가 설계이므로(V106 [R7]) 이 테스트가 명시적으로 말한다. 누락이 아니라 결정임을 카탈로그 단언으로
 * 남겨야 다음 밴드가 "빠뜨린 테이블"로 오인해 RLS 를 켜지 않는다.
 */
class ChannelDomainColumnTest extends IntegrationTestBase {

  /** tenant_id 컬럼을 갖게 된 5테이블. 앞의 4개는 V107 에서 RLS 대상, oauth_state 는 전역. */
  private static final List<String> TABLES =
      List.of(
          "slack_workspace",
          "notification_outbox",
          "user_channel_binding",
          "user_channel_preference",
          "oauth_state");

  @Autowired private DSLContext dsl;

  @Test
  @DisplayName("5테이블 전부 tenant_id 가 NOT NULL + GUC DEFAULT + tenant FK 를 갖는다")
  void everyTableHasNotNullTenantIdWithGucDefaultAndFk() {
    for (String tableName : TABLES) {
      var row =
          dsl.fetchOne(
              "select is_nullable, column_default from information_schema.columns"
                  + " where table_schema='public' and table_name=? and column_name='tenant_id'",
              tableName);
      assertThat(row).as("%s.tenant_id 컬럼이 없다", tableName).isNotNull();
      assertThat(row.get("is_nullable", String.class))
          .as("%s.tenant_id nullable", tableName)
          .isEqualTo("NO");
      assertThat(row.get("column_default", String.class))
          .as("%s.tenant_id DEFAULT 가 GUC 를 읽어야 한다", tableName)
          .contains("app.tenant_id");

      // FK 가 없으면 존재하지 않는 테넌트 id 를 심을 수 있다. oauth_state 는 RLS 가 없으므로
      // 값의 정합성을 지키는 수단이 이 FK 뿐이다.
      Integer fkCount =
          dsl.fetchOne(
                  "select count(*) from pg_constraint c"
                      + " join pg_class t on t.oid = c.conrelid"
                      + " join pg_class r on r.oid = c.confrelid"
                      + " where c.contype='f' and t.relname=? and r.relname='tenant'",
                  tableName)
              .get(0, Integer.class);
      assertThat(fkCount).as("%s 에 tenant(id) FK 가 없다", tableName).isEqualTo(1);
    }
  }

  @Test
  @DisplayName("oauth_state 는 컬럼만 있고 RLS·정책이 없다 — 누락이 아니라 설계다")
  void oauthStateHasColumnButDeliberatelyNoRls() {
    // OAuth 콜백은 permitAll 이라 컨텍스트가 없다. RLS 를 켜면 consume(state) 이 0행을 보고
    // 테넌트를 되찾을 방법이 사라진다 — tenant_id 는 격리가 아니라 운반 수단이다(V106 [R7]).
    Boolean rlsEnabled =
        dsl.fetchOne(
                "select c.relrowsecurity from pg_class c"
                    + " join pg_namespace n on n.oid = c.relnamespace"
                    + " where n.nspname='public' and c.relname='oauth_state'")
            .get(0, Boolean.class);
    assertThat(rlsEnabled).as("oauth_state 에 RLS 가 켜졌다 — 켜면 OAuth 콜백이 fail-closed 된다").isFalse();

    Integer policyCount =
        dsl.fetchOne("select count(*) from pg_policies where schemaname='public' and tablename='oauth_state'")
            .get(0, Integer.class);
    assertThat(policyCount).as("oauth_state 에 정책이 붙었다").isZero();
  }

  @Test
  @DisplayName("접힌 유니크 3개는 인덱스가 아니라 제약이다 — ON CONFLICT ON CONSTRAINT 회귀 가드")
  void foldedUniquesAreConstraintsNotBareIndexes() {
    // V103 은 DROP CONSTRAINT → CREATE UNIQUE INDEX 였지만, 여기는 제약 '이름'으로 참조하는
    // ON CONFLICT ON CONSTRAINT 호출부가 둘 있다(NotificationOutboxRepositoryImpl,
    // UserChannelBindingRepositoryImpl). 유니크 인덱스로 바꾸면 런타임에
    // "there is no unique or exclusion constraint matching the ON CONFLICT specification" 이 난다.
    assertConstraintDef("uk_outbox_idempotency", "UNIQUE (tenant_id, idempotency_key)");
    assertConstraintDef("uk_user_channel", "UNIQUE (tenant_id, user_id, channel_type, workspace_id)");
    assertConstraintDef("uk_preference", "UNIQUE (tenant_id, user_id, channel_type)");
  }

  @Test
  @DisplayName("slack_workspace.team_id 와 oauth_state.state 는 전역 유니크로 남는다")
  void externalIdentifierUniquesAreNotFolded() {
    // team_id 를 접으면 같은 Slack 팀이 두 테넌트에 설치돼 team_id → tenant 해석이 다의가 되고
    // findByTeamId 의 fetchOptional() 이 2행에서 터진다(V106 [R1]).
    assertConstraintDef("slack_workspace_team_id_key", "UNIQUE (team_id)");
    // state 는 32바이트 CSPRNG hex 이고, 접으면 consume(state) 이 아직 모르는 테넌트를 알아야 한다.
    assertConstraintDef("oauth_state_state_key", "UNIQUE (state)");
  }

  @Test
  @DisplayName("SECURITY DEFINER 해석기 2종은 최소 권한이다 — PUBLIC 회수 + app_tenant 부여 + search_path 고정")
  void resolverFunctionsAreLockedDown() {
    for (String fn : List.of("outbox_tenant_ids", "resolve_slack_workspace_tenant_by_team_id")) {
      var row =
          dsl.fetchOne(
              "select p.prosecdef, p.provolatile, p.proconfig,"
                  + " pg_get_function_result(p.oid) result,"
                  + " array_to_string(p.proacl, ',') acl"
                  + " from pg_proc p join pg_namespace n on n.oid = p.pronamespace"
                  + " where n.nspname='public' and p.proname=?",
              fn);
      assertThat(row).as("함수 %s 가 없다", fn).isNotNull();
      assertThat(row.get("prosecdef", Boolean.class))
          .as("%s 가 SECURITY DEFINER 가 아니다 — 배경 스레드에서 RLS 를 우회하지 못한다", fn)
          .isTrue();

      // R3 의 "읽기 전용"을 실효적으로 고정하는 단언이다. 지우지 말 것.
      // STABLE('s')이면 Postgres 가 함수 본문의 데이터 변경문을 거부한다. 이 단언이 없으면
      // 누군가 outbox_tenant_ids 를 VOLATILE + UPDATE ... SKIP LOCKED 클레임으로 재작성해도
      // 테스트가 초록이고, 그 순간 AnalyticsQueryExecutionService.executeDirectly(사용자 SQL 을
      // app_tenant 롤로 그대로 실행, 차단은 문자열 매칭뿐)를 통해 쿼리 UI 를 쓸 수 있는 아무
      // 사용자나 전 테넌트 outbox 행을 SENDING 으로 뒤집을 수 있다 — R3 가 실측으로 기각한 설계다.
      assertThat(row.get("provolatile", String.class))
          .as("%s 가 STABLE 이 아니다 — 쓰기 가능한 definer 함수는 쿼리 UI 를 통한 전 테넌트 조작 경로가 된다(R3)", fn)
          .isEqualTo("s");
      assertThat(row.get("proconfig", String[].class))
          .as("%s 에 search_path 고정이 없다 — definer 함수의 권한 상승 경로가 된다", fn)
          .isNotNull()
          .anyMatch(cfg -> cfg.startsWith("search_path="));

      String acl = row.get("acl", String.class);
      assertThat(acl).as("%s 의 ACL 이 비어 기본값(PUBLIC 허용)이다", fn).isNotBlank();
      // ACL 항목의 grantee 가 빈 문자열이면 PUBLIC 이다("=X/owner" 형태).
      assertThat(acl.split(",")).as("%s 의 EXECUTE 가 PUBLIC 에 남아 있다", fn).noneMatch(e -> e.startsWith("="));
      assertThat(acl).as("%s 에 app_tenant EXECUTE 가 없다 — 런타임 롤이 호출하지 못한다", fn).contains("app_tenant=X");

      // 반환 형태 고정 — 노출면이 정수뿐임을 못박는다. V95 가 "트리거 행을 반환하지 않는다,
      // id 두 개뿐이라 노출면이 정수 하나로 제한된다"를 원칙으로 세웠고 이 두 함수도 같다.
      // 이 단언이 없으면 "편의상 봇 토큰·서명 시크릿도 함께 반환하자"는 확장이 조용히 통과한다 —
      // 그 순간 definer 함수가 RLS 를 우회해 타 테넌트 자격증명을 내주는 통로가 된다.
      String expectedResult =
          fn.equals("outbox_tenant_ids")
              ? "SETOF bigint"
              : "TABLE(workspace_id bigint, tenant_id bigint)";
      assertThat(row.get("result", String.class))
          .as("%s 의 반환 형태가 바뀌었다 — definer 함수의 노출면은 id 뿐이어야 한다", fn)
          .isEqualTo(expectedResult);
    }
  }

  @Test
  @DisplayName("idx_outbox_pending_due 는 테넌트 선행으로 접히지 않았다 — definer 스캔의 성능 전제")
  void pendingDueIndexStaysGlobal() {
    // V106 주석이 이 인덱스의 원형 유지를 outbox_tenant_ids 의 성능 전제로 선언한다:
    // DISTINCT tenant_id 스캔에는 tenant_id 술어가 없으므로 (tenant_id, ...) 로 접으면
    // 부분 인덱스의 선택성이 사라져 전 테이블 스캔이 된다. 다른 outbox 인덱스를 전부
    // 테넌트 선행으로 바꾼 밴드였으므로, 이 하나만 예외라는 결정을 테스트가 말하게 한다.
    var row =
        dsl.fetchOne(
            "select indexdef from pg_indexes where schemaname='public'"
                + " and tablename='notification_outbox' and indexname='idx_outbox_pending_due'");
    assertThat(row).as("idx_outbox_pending_due 가 존재하지 않는다").isNotNull();
    String def = row.get("indexdef", String.class);
    assertThat(def)
        .as("idx_outbox_pending_due 가 테넌트 선행으로 접혔다 — definer 스캔이 인덱스를 타지 못한다")
        .contains("(next_attempt_at)")
        .doesNotContain("tenant_id");
    assertThat(def).as("idx_outbox_pending_due 의 PENDING 부분 조건이 사라졌다").contains("PENDING");
  }

  /** 제약 정의를 읽어 형태를 단언한다. 제약이 아니라 인덱스로 바뀌면 여기서 "존재하지 않는다"로 잡힌다. */
  private void assertConstraintDef(String constraintName, String expectedDef) {
    var row =
        dsl.fetchOne(
            "select c.contype, pg_get_constraintdef(c.oid) def from pg_constraint c"
                + " join pg_namespace n on n.oid = c.connamespace"
                + " where n.nspname='public' and c.conname=?",
            constraintName);
    assertThat(row).as("제약 %s 가 존재하지 않는다 (유니크 인덱스로 바뀌었는가?)", constraintName).isNotNull();
    assertThat(row.get("contype", String.class)).as("%s 가 유니크 제약이 아니다", constraintName).isEqualTo("u");
    assertThat(row.get("def", String.class)).as("%s 정의", constraintName).isEqualTo(expectedDef);
  }
}
